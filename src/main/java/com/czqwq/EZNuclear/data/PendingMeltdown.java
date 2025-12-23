package com.czqwq.EZNuclear.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.Explosion;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.world.ExplosionEvent;

import com.czqwq.EZNuclear.Config;
import com.czqwq.EZNuclear.util.Constants;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import ic2.core.ExplosionIC2;

public class PendingMeltdown {

    // Scheduled task container: tasks are executed on server thread when due
    private static final List<Scheduled> SCHEDULED = new CopyOnWriteArrayList<>();
    // Track occupied positions (avoid duplicates). Use a simple key for stable equals/hashCode.
    private static final Set<PosKey> POSITIONS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Re-entry set: positions allowed to bypass interception once
    private static final Set<PosKey> REENTRY = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Manual trigger set: positions that require manual triggering via chat command
    private static final Set<PosKey> MANUAL_TRIGGER = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // DE Manual trigger set: positions for DE explosions that require manual triggering
    private static final Set<PosKey> DE_MANUAL_TRIGGER = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Stored explosion power for manual triggers
    private static final Map<PosKey, Double> EXPLOSION_POWERS = new ConcurrentHashMap<>();
    // Flag to allow manually triggered explosions to run without being re-cancelled
    private static volatile boolean allowNextExplosion = false;

    public static boolean isAllowingNextExplosion() {
        return allowNextExplosion;
    }

    public static void resetAllowNextExplosion() {
        allowNextExplosion = false;
    }

    public static void setAllowNextExplosion() {
        allowNextExplosion = true;
    }

    // scanning interval (ticks) to check for rogue reactors; low frequency to reduce overhead
    private static final int SCAN_INTERVAL_TICKS = 20; // once per second
    private int tickCounter = 0;

    private static class Scheduled {

        final long executeAtMillis;
        final Runnable task;
        final PosKey pos;

        Scheduled(long executeAtMillis, Runnable task, PosKey pos) {
            this.executeAtMillis = executeAtMillis;
            this.task = task;
            this.pos = pos;
        }
    }

    private static class PosKey {

        final int x, y, z, dim;

        PosKey(ChunkCoordinates c) {
            this.x = c.posX;
            this.y = c.posY;
            this.z = c.posZ;
            this.dim = 0;
        }

        PosKey(ChunkCoordinates c, int dim) {
            this.x = c.posX;
            this.y = c.posY;
            this.z = c.posZ;
            this.dim = dim;
        }

        PosKey(int x, int y, int z, int dim) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dim = dim;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PosKey)) return false;
            PosKey k = (PosKey) o;
            return k.x == x && k.y == y && k.z == z && k.dim == dim;
        }

        @Override
        public int hashCode() {
            int h = x;
            h = 31 * h + y;
            h = 31 * h + z;
            h = 31 * h + dim;
            return h;
        }
    }

    // Overloads that include dimension (preferred) ------------------------------------------------
    public static boolean schedule(ChunkCoordinates pos, Runnable task, long delayMs, int dimension) {
        System.out.println("[EZNuclear] Scheduling task for position: " + pos + " with delay: " + delayMs + "ms");
        if (pos == null || task == null) return false;
        PosKey key = new PosKey(pos, dimension);
        // 不管位置是否已被标记，都添加任务
        long executeAt = System.currentTimeMillis() + Math.max(0, delayMs);
        // LOGGER.info(
        // "PendingMeltdown.schedule: scheduling task at {} dim={} delayMs={} execAt={}",
        // pos,
        // dimension,
        // delayMs,
        // executeAt);
        Scheduled scheduled = new Scheduled(executeAt, task, key);
        SCHEDULED.add(scheduled);
        System.out.println("[EZNuclear] Task added to SCHEDULED list: " + scheduled);
        return true;
    }

    public static void markReentry(ChunkCoordinates pos, int dimension) {
        if (pos == null) return;
        REENTRY.add(new PosKey(pos, dimension));
    }

    public static boolean consumeReentry(ChunkCoordinates pos, int dimension) {
        if (pos == null) return false;
        return REENTRY.remove(new PosKey(pos, dimension));
    }

    // Backwards-compatible variants (dimension 0)
    public static boolean schedule(ChunkCoordinates pos, Runnable task, long delayMs) {
        return schedule(pos, task, delayMs, 0);
    }

    public static void markReentry(ChunkCoordinates pos) {
        markReentry(pos, 0);
    }

    public static boolean consumeReentry(ChunkCoordinates pos) {
        return consumeReentry(pos, 0);
    }

    // Methods for manual trigger mechanism
    public static void markManualTrigger(ChunkCoordinates pos, int dimension) {
        System.out.println("[EZNuclear] Marking position for manual trigger: " + pos + " dimension: " + dimension);
        if (pos == null) return;
        MANUAL_TRIGGER.add(new PosKey(pos, dimension));
    }

    public static void markManualTriggerWithPower(ChunkCoordinates pos, double power) {
        System.out.println("[EZNuclear] Marking position for manual trigger with power: " + pos + " power: " + power);
        if (pos == null) return;
        // For this method, we use dimension 0 by default, but it would be better to pass dimension
        PosKey key = new PosKey(pos, 0);
        MANUAL_TRIGGER.add(key);
        EXPLOSION_POWERS.put(key, power);
    }

    public static void markManualTriggerWithPower(ChunkCoordinates pos, int dimension, double power) {
        System.out.println(
            "[EZNuclear] Marking position for manual trigger with power: " + pos
                + " dimension: "
                + dimension
                + " power: "
                + power);
        if (pos == null) return;
        PosKey key = new PosKey(pos, dimension);
        MANUAL_TRIGGER.add(key);
        EXPLOSION_POWERS.put(key, power);
    }

    public static void markDEManualTriggerWithPower(ChunkCoordinates pos, int dimension, double power) {
        System.out.println(
            "[EZNuclear] Marking position for DE manual trigger with power: " + pos
                + " dimension: "
                + dimension
                + " power: "
                + power);
        if (pos == null) return;
        PosKey key = new PosKey(pos, dimension);
        DE_MANUAL_TRIGGER.add(key);
        EXPLOSION_POWERS.put(key, power);
    }

    public static boolean consumeManualTrigger(ChunkCoordinates pos, int dimension) {
        if (pos == null) return false;
        return MANUAL_TRIGGER.remove(new PosKey(pos, dimension));
    }

    public static boolean isManualTrigger(ChunkCoordinates pos, int dimension) {
        if (pos == null) return false;
        return MANUAL_TRIGGER.contains(new PosKey(pos, dimension));
    }

    // Backwards-compatible variants (dimension 0)
    public static void markManualTrigger(ChunkCoordinates pos) {
        markManualTrigger(pos, 0);
    }

    public static boolean consumeManualTrigger(ChunkCoordinates pos) {
        return consumeManualTrigger(pos, 0);
    }

    public static boolean isManualTrigger(ChunkCoordinates pos) {
        return isManualTrigger(pos, 0);
    }

    /**
     * Force-execute all scheduled tasks immediately (used by chat trigger). Runs on the calling thread.
     */
    public static void executeAllNow() {
        List<Scheduled> copy = new ArrayList<>(SCHEDULED);
        SCHEDULED.clear();
        POSITIONS.clear();
        MANUAL_TRIGGER.clear();
        DE_MANUAL_TRIGGER.clear(); // Also clear DE manual triggers
        REENTRY.clear();
        EXPLOSION_POWERS.clear(); // Also clear explosion powers to maintain consistency
        // LOGGER.info("PendingMeltdown.executeAllNow: executing {} tasks immediately", copy.size());
        for (Scheduled s : copy) {
            try {
                // LOGGER.info("PendingMeltdown.executeAllNow: running task for pos {}", s.pos);
                s.task.run();
            } catch (Throwable t) {
                // LOGGER.error("Error running meltdown task", t);
            }
        }
    }

    /**
     * Execute specific scheduled task immediately by position (used by manual trigger).
     */
    public static void executeByPosition(ChunkCoordinates pos, int dimension) {
        List<Scheduled> copy = new ArrayList<>(SCHEDULED);
        List<Scheduled> toRemove = new ArrayList<>();

        PosKey posKey = new PosKey(pos, dimension);
        for (Scheduled s : copy) {
            if (s.pos.equals(posKey)) {
                toRemove.add(s);
                try {
                    // LOGGER.info("PendingMeltdown.executeByPosition: running task for pos {}", s.pos)
                    setAllowNextExplosion();
                    s.task.run();
                } catch (Throwable t) {
                    // LOGGER.error("Error running meltdown task", t);
                }
            }
        }

        if (!toRemove.isEmpty()) {
            SCHEDULED.removeAll(toRemove);
            POSITIONS.remove(posKey);
            MANUAL_TRIGGER.remove(posKey);
            REENTRY.remove(posKey);
            EXPLOSION_POWERS.remove(posKey); // Also remove explosion power to maintain consistency
        }
    }

    // Backwards-compatible variant (dimension 0)
    public static void executeByPosition(ChunkCoordinates pos) {
        executeByPosition(pos, 0);
    }

    /**
     * Trigger explosion immediately for a manually marked position.
     * This creates a new explosion task and executes it immediately.
     */
    public static void triggerExplosionImmediately(ChunkCoordinates pos) {
        System.out.println("[EZNuclear] triggerExplosionImmediately called for position: " + pos);
        // We need to find the correct PosKey with dimension from the MANUAL_TRIGGER set
        PosKey foundPosKey = null;
        for (PosKey key : MANUAL_TRIGGER) {
            if (key.x == pos.posX && key.y == pos.posY && key.z == pos.posZ) {
                foundPosKey = key;
                break;
            }
        }

        if (foundPosKey == null) {
            System.out.println("[EZNuclear] Position not marked for manual trigger: " + pos);
            return;
        }

        // Remove from manual trigger set
        MANUAL_TRIGGER.remove(foundPosKey);

        // Get stored explosion power if available
        Double power = EXPLOSION_POWERS.get(foundPosKey);
        if (power == null) {
            power = 4.0; // Default power if not stored
        }
        System.out.println("[EZNuclear] Using explosion power: " + power);

        // Set flag to allow the explosion to proceed without being re-cancelled
        setAllowNextExplosion();

        // Create and trigger the explosion immediately at the specified position
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            WorldServer world = server.worldServers[foundPosKey.dim]; // Use the correct dimension

            // Create the IC2 explosion
            ExplosionIC2 explosion = new ExplosionIC2(
                world,
                null,
                pos.posX,
                pos.posY,
                pos.posZ,
                power.floatValue(),
                0.01F,
                ExplosionIC2.Type.Nuclear);
            explosion.doExplosion();

            System.out.println("[EZNuclear] IC2 Explosion triggered at position: " + pos + " with power: " + power);
        }

        // Clean up stored power
        EXPLOSION_POWERS.remove(foundPosKey);
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onChat(ServerChatEvent event) {
        System.out.println("[EZNuclear] onChat called with message: " + event.message);
        String triggerMessage = Constants.COMMAND_EZUNCLEAR; // 你可以改成任何触发消息

        if (event.message != null && event.message.equals(triggerMessage)) {
            System.out.println("[EZNuclear] Trigger message detected, executing all scheduled tasks");
            executeAllNow();
        }

        // Handle manual trigger command "坏了坏了"
        if (event.message != null && event.message.equals(Constants.COMMAND_OH_NO)) {
            System.out.println("[EZNuclear] Manual trigger command detected");

            // Process IC2 explosions
            processManualTriggers(MANUAL_TRIGGER, false); // false = not DE

            // Process DE explosions
            processManualTriggers(DE_MANUAL_TRIGGER, true); // true = DE
        }
    }

    private static void processManualTriggers(Set<PosKey> triggerSet, boolean isDE) {
        List<PosKey> positionsToTrigger = new ArrayList<>();
        synchronized (triggerSet) {
            positionsToTrigger.addAll(triggerSet);
        }

        System.out.println(
            "[EZNuclear] Found " + positionsToTrigger.size() + " " + (isDE ? "DE" : "IC2") + " positions to trigger");
        for (PosKey posKey : positionsToTrigger) {
            ChunkCoordinates pos = new ChunkCoordinates(posKey.x, posKey.y, posKey.z);
            System.out.println("[EZNuclear] Triggering " + (isDE ? "DE" : "IC2") + " explosion at position: " + pos);

            if (triggerSet.contains(posKey)) {
                triggerSet.remove(posKey);

                // Get stored explosion power if available
                Double power = EXPLOSION_POWERS.get(posKey);
                if (power == null) {
                    power = 4.0; // Default power if not stored
                }
                System.out.println("[EZNuclear] Using " + (isDE ? "DE" : "IC2") + " explosion power: " + power);

                // Set flag to allow the explosion to proceed without being re-cancelled
                setAllowNextExplosion();

                // Create and trigger the explosion immediately at the specified position
                MinecraftServer server = MinecraftServer.getServer();
                if (server != null) {
                    WorldServer world = server.worldServers[posKey.dim]; // Use the correct dimension

                    if (isDE) {
                        // Create the DE explosion using ReactorExplosion
                        try {
                            Class<?> reClass = Class.forName(
                                "com.brandon3055.draconicevolution.common.tileentities.multiblocktiles.reactor.ReactorExplosion");
                            java.lang.reflect.Constructor<?> ctor = reClass.getConstructor(
                                net.minecraft.world.World.class,
                                int.class,
                                int.class,
                                int.class,
                                float.class);
                            Object newExp = ctor.newInstance(world, pos.posX, pos.posY, pos.posZ, power.floatValue());

                            // Add to process handler
                            Class<?> iProcessClass = Class
                                .forName("com.brandon3055.brandonscore.common.handlers.IProcess");
                            java.lang.reflect.Method addMethod = Class
                                .forName("com.brandon3055.brandonscore.common.handlers.ProcessHandler")
                                .getMethod("addProcess", iProcessClass);
                            addMethod.invoke(null, newExp);

                            System.out.println(
                                "[EZNuclear] DE ReactorExplosion triggered at position: " + pos
                                    + " with power: "
                                    + power);
                        } catch (Exception e) {
                            // Fallback to vanilla explosion if DE classes are not available
                            net.minecraft.world.Explosion explosion = new net.minecraft.world.Explosion(
                                world,
                                null,
                                pos.posX,
                                pos.posY,
                                pos.posZ,
                                power.floatValue());
                            explosion.doExplosionA();
                            explosion.doExplosionB(true);
                            System.out.println(
                                "[EZNuclear] DE fallback vanilla explosion triggered at position: " + pos
                                    + " with power: "
                                    + power);
                        }
                    } else {
                        // Create the IC2 explosion
                        ExplosionIC2 explosion = new ExplosionIC2(
                            world,
                            null,
                            pos.posX,
                            pos.posY,
                            pos.posZ,
                            power.floatValue(),
                            0.01F,
                            ExplosionIC2.Type.Nuclear);
                        explosion.doExplosion();
                        System.out.println(
                            "[EZNuclear] IC2 Explosion triggered at position: " + pos + " with power: " + power);
                    }
                }

                // Clean up stored power
                EXPLOSION_POWERS.remove(posKey);
            }
        }
    }

    /**
     * Trigger DE explosion immediately for a manually marked position.
     * This creates a new explosion task and executes it immediately.
     */
    public static void triggerDEExplosionImmediately(ChunkCoordinates pos) {
        System.out.println("[EZNuclear] triggerDEExplosionImmediately called for position: " + pos);
        // We need to find the correct PosKey with dimension from the MANUAL_TRIGGER set
        PosKey foundPosKey = null;
        for (PosKey key : MANUAL_TRIGGER) {
            if (key.x == pos.posX && key.y == pos.posY && key.z == pos.posZ) {
                foundPosKey = key;
                break;
            }
        }

        if (foundPosKey == null) {
            System.out.println("[EZNuclear] Position not marked for manual trigger: " + pos);
            return;
        }

        // Remove from manual trigger set
        MANUAL_TRIGGER.remove(foundPosKey);

        // Get stored explosion power if available
        Double power = EXPLOSION_POWERS.get(foundPosKey);
        if (power == null) {
            power = 4.0; // Default power if not stored
        }
        System.out.println("[EZNuclear] Using DE explosion power: " + power);

        // Create and trigger the explosion immediately at the specified position
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            WorldServer world = server.worldServers[foundPosKey.dim]; // Use the correct dimension

            // Create the vanilla explosion for DE
            net.minecraft.world.Explosion explosion = new net.minecraft.world.Explosion(
                world,
                null,
                pos.posX,
                pos.posY,
                pos.posZ,
                power.floatValue());
            explosion.doExplosionA();
            explosion.doExplosionB(true);

            System.out.println("[EZNuclear] DE Explosion triggered at position: " + pos + " with power: " + power);
        }

        // Clean up stored power
        EXPLOSION_POWERS.remove(foundPosKey);
    }

    @SubscribeEvent
    public void onExplosionStart(ExplosionEvent.Start event) {
        try {
            Explosion explosion = event.explosion;
            if (explosion == null) return;
            int ex = (int) Math.floor(explosion.explosionX);
            int ey = (int) Math.floor(explosion.explosionY);
            int ez = (int) Math.floor(explosion.explosionZ);
            ChunkCoordinates pos = new ChunkCoordinates(ex, ey, ez);
            // LOGGER.info("PendingMeltdown.onExplosionStart: detected explosion at {}", pos);

            // If reentry present, allow
            if (consumeReentry(pos)) {
                // LOGGER.info("PendingMeltdown.onExplosionStart: reentry present for {}, allowing explosion", pos);
                return;
            }

            // Cancel and reschedule via scheduler
            event.setCanceled(true);
            // LOGGER.info(
            // "PendingMeltdown.onExplosionStart: cancelled explosive at {}, scheduling via PendingMeltdown",
            // pos);

            schedule(pos, () -> {
                try {
                    // recreate and trigger explosion on server thread
                    // LOGGER.info("PendingMeltdown: executing scheduled explosion for {}", pos);

                    // re-create explosion instance using existing Explosion class if necessary
                    // Find the world field on Explosion via reflection (common names: world, worldObj)
                    java.lang.reflect.Field worldField = null;
                    Object worldObj = null;
                    try {
                        worldField = explosion.getClass()
                            .getDeclaredField("world");
                        worldField.setAccessible(true);
                        worldObj = worldField.get(explosion);
                    } catch (NoSuchFieldException nsf) {
                        // try common alternative name
                        try {
                            worldField = explosion.getClass()
                                .getDeclaredField("worldObj");
                            worldField.setAccessible(true);
                            worldObj = worldField.get(explosion);
                        } catch (NoSuchFieldException nsf2) {
                            // scan declared fields for a World typed field
                            for (java.lang.reflect.Field f : explosion.getClass()
                                .getDeclaredFields()) {
                                if (net.minecraft.world.World.class.isAssignableFrom(f.getType())) {
                                    f.setAccessible(true);
                                    worldObj = f.get(explosion);
                                    worldField = f;
                                    break;
                                }
                            }
                        }
                    }

                    // Get dimension from the world object
                    int dimension = 0;
                    if (worldObj instanceof net.minecraft.world.World) {
                        net.minecraft.world.World w = (net.minecraft.world.World) worldObj;
                        dimension = w.provider.dimensionId;
                    }

                    markReentry(pos, dimension);

                    if (worldObj instanceof net.minecraft.world.World) {
                        net.minecraft.world.World w = (net.minecraft.world.World) worldObj;
                        // Use IC2 ExplosionIC2 instead of vanilla Explosion
                        ExplosionIC2 e = new ExplosionIC2(
                            w,
                            explosion.getExplosivePlacedBy(),
                            explosion.explosionX,
                            explosion.explosionY,
                            explosion.explosionZ,
                            explosion.explosionSize,
                            0.01F,
                            ExplosionIC2.Type.Nuclear);
                        e.doExplosion();
                    } else {
                        // LOGGER.warn(
                        // "PendingMeltdown: could not locate Explosion.world field; skipping scheduled explosion for
                        // {}",
                        // pos);
                    }
                } catch (Throwable t) {
                    // LOGGER.warn("Failed to perform scheduled explosion for {}: {}", pos, t.getMessage());
                }
            }, Config.explosionDelaySeconds * 1000L);

        } catch (Throwable t) {
            // LOGGER.warn("onExplosionStart handler failed: {}", t.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // periodic scan for reactors with overheat when structure is invalid
        tickCounter++;
        boolean doScan = (tickCounter % SCAN_INTERVAL_TICKS) == 0;
        long now = System.currentTimeMillis();
        // CopyOnWriteArrayList iterator does not support remove(); collect due tasks and removeAll instead
        List<Scheduled> due = new ArrayList<>();
        for (Scheduled s : SCHEDULED) {
            if (s.executeAtMillis <= now) {
                due.add(s);
            }
        }
        if (!due.isEmpty()) {
            // remove scheduled entries first to avoid race when tasks reschedule
            SCHEDULED.removeAll(due);
            for (Scheduled s : due) {
                // LOGGER.info(
                // "PendingMeltdown.onServerTick: executing scheduled task for pos {} (scheduledAt={} now={})",
                // s.pos,
                // s.executeAtMillis,
                // now);
                try {
                    s.task.run();
                } catch (Throwable t) {
                    // LOGGER.error("Error running scheduled meltdown task", t);
                } finally {
                    // free the position so future meltdowns can be scheduled there
                    POSITIONS.remove(s.pos);
                    REENTRY.remove(s.pos);
                }
            }
        }

        if (doScan) {
            try {
                net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
                if (server != null) {
                    for (net.minecraft.world.WorldServer ws : server.worldServers) {
                        try {
                            // Get the dimension ID for the world
                            int dimensionId = ws.provider.dimensionId;
                            List<?> tes = ws.loadedTileEntityList;
                            for (Object te : tes) {
                                if (te == null) continue;
                                Class<?> teClass = te.getClass();
                                String clsName = teClass.getName();
                                if (!clsName.endsWith("TileReactorCore")) continue;

                                // found a reactor tile entity; try to get its position
                                int x = -1, y = -1, z = -1;
                                try {
                                    java.lang.reflect.Field fx = teClass.getField("xCoord");
                                    java.lang.reflect.Field fy = teClass.getField("yCoord");
                                    java.lang.reflect.Field fz = teClass.getField("zCoord");
                                    x = fx.getInt(te);
                                    y = fy.getInt(te);
                                    z = fz.getInt(te);
                                } catch (Throwable ignored) {}

                                ChunkCoordinates pos = new ChunkCoordinates(x, y, z);

                                // try to read temperature or heat fields
                                double temp = Double.NaN;
                                String[] tempFields = new String[] { "temperature", "temp", "coreTemp",
                                    "reactorTemperature", "heat", "coreTemperature" };
                                for (String fn : tempFields) {
                                    try {
                                        java.lang.reflect.Field f = teClass.getDeclaredField(fn);
                                        f.setAccessible(true);
                                        Object val = f.get(te);
                                        if (val instanceof Number) {
                                            temp = ((Number) val).doubleValue();
                                            break;
                                        }
                                    } catch (NoSuchFieldException ignore) {}
                                }

                                if (!Double.isNaN(temp) && temp > 2000.0) {
                                    // schedule meltdown if not already scheduled
                                    // LOGGER.info(
                                    // "PendingMeltdown.scan: reactor at {} has temp={} >2000; scheduling meltdown",
                                    // pos,
                                    // temp);
                                    // schedule with same behavior as mixin: send messages and reinvoke
                                    final int fx = x;
                                    final int fy = y;
                                    final int fz = z;
                                    final net.minecraft.world.WorldServer fws = ws;
                                    final int dimId = dimensionId;
                                    final ChunkCoordinates fpos = pos;
                                    schedule(fpos, () -> {
                                        try {
                                            // send interact message
                                            net.minecraft.server.MinecraftServer srv = net.minecraft.server.MinecraftServer
                                                .getServer();
                                            if (srv != null && !srv.isSinglePlayer()) {
                                                List<net.minecraft.entity.player.EntityPlayerMP> players = srv
                                                    .getConfigurationManager().playerEntityList;
                                                for (net.minecraft.entity.player.EntityPlayerMP p : players) {
                                                    // Check if GTUtility exists before using it
                                                    try {
                                                        Class.forName("gregtech.api.util.GTUtility");
                                                        gregtech.api.util.GTUtility.sendChatToPlayer(
                                                            p,
                                                            net.minecraft.util.StatCollector
                                                                .translateToLocal("info.ezunclear.interact"));
                                                    } catch (ClassNotFoundException e) {
                                                        // GTUtility not available, use vanilla chat
                                                        p.addChatMessage(
                                                            new net.minecraft.util.ChatComponentText(
                                                                net.minecraft.util.StatCollector
                                                                    .translateToLocal("info.ezunclear.interact")));
                                                    }
                                                }
                                            }

                                            // allow reentry and try to call goBoom on the original tile
                                            markReentry(fpos, dimId);
                                            try {
                                                // attempt to reflectively call goBoom on the tile entity (if still
                                                // loaded)
                                                Object tile = fws.getTileEntity(fx, fy, fz);
                                                if (tile != null) {
                                                    java.lang.reflect.Method mg = tile.getClass()
                                                        .getMethod("goBoom");
                                                    mg.setAccessible(true);
                                                    mg.invoke(tile);
                                                    return;
                                                }
                                            } catch (Throwable ignored) {}

                                            // fallback: create ReactorExplosion
                                            try {
                                                Class<?> reClass = Class.forName(
                                                    "com.brandon3055.draconicevolution.common.tileentities.multiblocktiles.reactor.ReactorExplosion");
                                                java.lang.reflect.Constructor<?> ctor = reClass.getConstructor(
                                                    net.minecraft.world.World.class,
                                                    int.class,
                                                    int.class,
                                                    int.class,
                                                    float.class);
                                                Object newExp = ctor.newInstance(fws, fx, fy, fz, 10F);
                                                // add to process handler
                                                try {
                                                    Class<?> iProcessClass = Class.forName(
                                                        "com.brandon3055.brandonscore.common.handlers.IProcess");
                                                    java.lang.reflect.Method addMethod = Class.forName(
                                                        "com.brandon3055.brandonscore.common.handlers.ProcessHandler")
                                                        .getMethod("addProcess", iProcessClass);
                                                    addMethod.invoke(null, newExp);
                                                } catch (Throwable t) {
                                                    // LOGGER.warn(
                                                    // "Failed to schedule ReactorExplosion via reflection: {}",
                                                    // t.getMessage());
                                                }
                                            } catch (Throwable t) {
                                                // LOGGER.warn(
                                                // "Failed to create ReactorExplosion fallback: {}",
                                                // t.getMessage());
                                            }

                                        } catch (Throwable t) {
                                            // LOGGER.warn("Scheduled scan-meltdown task failed: {}", t.getMessage());
                                        }
                                    }, 5000L, dimId);
                                }
                            }
                        } catch (Throwable t) {
                            // ignore per-world errors
                        }
                    }
                }
            } catch (Throwable t) {
                // LOGGER.warn("PendingMeltdown.scan failed: {}", t.getMessage());
            }
        }
    }
}
