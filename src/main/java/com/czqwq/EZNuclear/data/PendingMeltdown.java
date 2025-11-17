package com.czqwq.EZNuclear.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.Explosion;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.world.ExplosionEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class PendingMeltdown {

    // Scheduled task container: tasks are executed on server thread when due
    private static final List<Scheduled> SCHEDULED = new CopyOnWriteArrayList<>();
    // Track occupied positions (avoid duplicates). Use a simple key for stable equals/hashCode.
    private static final Set<PosKey> POSITIONS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // Re-entry set: positions allowed to bypass interception once
    private static final Set<PosKey> REENTRY = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Logger LOGGER = LogManager.getLogger(PendingMeltdown.class);
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
        if (pos == null || task == null) return false;
        PosKey key = new PosKey(pos, dimension);
        boolean added = POSITIONS.add(key);
        if (!added) return false;
        long executeAt = System.currentTimeMillis() + Math.max(0, delayMs);
        LOGGER.info(
            "PendingMeltdown.schedule: scheduling task at {} dim={} delayMs={} execAt={}",
            pos,
            dimension,
            delayMs,
            executeAt);
        SCHEDULED.add(new Scheduled(executeAt, task, key));
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

    /**
     * Force-execute all scheduled tasks immediately (used by chat trigger). Runs on the calling thread.
     */
    public static void executeAllNow() {
        List<Scheduled> copy = new ArrayList<>(SCHEDULED);
        SCHEDULED.clear();
        POSITIONS.clear();
        LOGGER.info("PendingMeltdown.executeAllNow: executing {} tasks immediately", copy.size());
        for (Scheduled s : copy) {
            try {
                LOGGER.info("PendingMeltdown.executeAllNow: running task for pos {}", s.pos);
                s.task.run();
            } catch (Throwable t) {
                LOGGER.error("Error running meltdown task", t);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onChat(ServerChatEvent event) {
        String triggerMessage = "EZUNCLEAR"; // 你可以改成任何触发消息

        if (event.message != null && event.message.equals(triggerMessage)) {
            executeAllNow();
        }
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
            LOGGER.info("PendingMeltdown.onExplosionStart: detected explosion at {}", pos);

            // If reentry present, allow
            if (consumeReentry(pos)) {
                LOGGER.info("PendingMeltdown.onExplosionStart: reentry present for {}, allowing explosion", pos);
                return;
            }

            // Cancel and reschedule via scheduler
            event.setCanceled(true);
            LOGGER.info(
                "PendingMeltdown.onExplosionStart: cancelled explosive at {}, scheduling via PendingMeltdown",
                pos);
            schedule(pos, () -> {
                try {
                    // recreate and trigger explosion on server thread
                    LOGGER.info("PendingMeltdown: executing scheduled explosion for {}", pos);
                    markReentry(pos);
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

                    if (worldObj instanceof net.minecraft.world.World) {
                        net.minecraft.world.World w = (net.minecraft.world.World) worldObj;
                        // Explosion constructor in this MC version is (World, Entity, double, double, double, float)
                        Explosion e = new Explosion(
                            w,
                            explosion.getExplosivePlacedBy(),
                            explosion.explosionX,
                            explosion.explosionY,
                            explosion.explosionZ,
                            explosion.explosionSize);
                        // try to copy flags if present on target Explosion type
                        try {
                            java.lang.reflect.Field flamingField = null;
                            try {
                                flamingField = e.getClass()
                                    .getDeclaredField("isFlaming");
                            } catch (NoSuchFieldException nsf) {
                                try {
                                    flamingField = e.getClass()
                                        .getDeclaredField("isFlaming");
                                } catch (NoSuchFieldException ignore) {
                                    flamingField = null;
                                }
                            }
                            if (flamingField != null) {
                                flamingField.setAccessible(true);
                                try {
                                    flamingField.setBoolean(e, explosion.isFlaming);
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}

                        try {
                            java.lang.reflect.Field smokingField = null;
                            try {
                                smokingField = e.getClass()
                                    .getDeclaredField("isSmoking");
                            } catch (NoSuchFieldException nsf) {
                                try {
                                    smokingField = e.getClass()
                                        .getDeclaredField("isSmoking");
                                } catch (NoSuchFieldException ignore) {
                                    smokingField = null;
                                }
                            }
                            if (smokingField != null) {
                                smokingField.setAccessible(true);
                                try {
                                    smokingField.setBoolean(e, explosion.isSmoking);
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}

                        e.doExplosionA();
                        e.doExplosionB(true);
                    } else {
                        LOGGER.warn(
                            "PendingMeltdown: could not locate Explosion.world field; skipping scheduled explosion for {}",
                            pos);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("Failed to perform scheduled explosion for {}: {}", pos, t.getMessage());
                }
            }, 5000L);

        } catch (Throwable t) {
            LOGGER.warn("onExplosionStart handler failed: {}", t.getMessage());
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
                LOGGER.info(
                    "PendingMeltdown.onServerTick: executing scheduled task for pos {} (scheduledAt={} now={})",
                    s.pos,
                    s.executeAtMillis,
                    now);
                try {
                    s.task.run();
                } catch (Throwable t) {
                    LOGGER.error("Error running scheduled meltdown task", t);
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
                                    LOGGER.info(
                                        "PendingMeltdown.scan: reactor at {} has temp={} >2000; scheduling meltdown",
                                        pos,
                                        temp);
                                    // schedule with same behavior as mixin: send messages and reinvoke
                                    final int fx = x;
                                    final int fy = y;
                                    final int fz = z;
                                    final net.minecraft.world.WorldServer fws = ws;
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
                                                    gregtech.api.util.GTUtility.sendChatToPlayer(
                                                        p,
                                                        net.minecraft.util.StatCollector
                                                            .translateToLocal("info.ezunclear.interact"));
                                                }
                                            }

                                            // allow reentry and try to call goBoom on the original tile
                                            markReentry(fpos);
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
                                                    LOGGER.warn(
                                                        "Failed to schedule ReactorExplosion via reflection: {}",
                                                        t.getMessage());
                                                }
                                            } catch (Throwable t) {
                                                LOGGER.warn(
                                                    "Failed to create ReactorExplosion fallback: {}",
                                                    t.getMessage());
                                            }

                                        } catch (Throwable t) {
                                            LOGGER.warn("Scheduled scan-meltdown task failed: {}", t.getMessage());
                                        }
                                    }, 5000L);
                                }
                            }
                        } catch (Throwable t) {
                            // ignore per-world errors
                        }
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("PendingMeltdown.scan failed: {}", t.getMessage());
            }
        }
    }
}
