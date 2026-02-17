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
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.ServerChatEvent;

import com.czqwq.EZNuclear.Config;
import com.czqwq.EZNuclear.EZNuclear;
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
    // Set to track positions that have been completely processed to prevent re-processing
    private static final Set<PosKey> PROCESSED_POSITIONS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Map to track when a position was processed, to prevent re-processing for a certain time period
    private static final Map<PosKey, Long> PROCESSED_POSITIONS_TIME = new ConcurrentHashMap<>();
    // Map to track when a scheduled task was created, to prevent memory leaks
    private static final Map<PosKey, Long> SCHEDULED_TASK_CREATION_TIME = new ConcurrentHashMap<>();

    // Queue for deferred addition of processes to avoid ConcurrentModificationException
    private static final List<Object> DEFERRED_PROCESS_QUEUE = new CopyOnWriteArrayList<>();

    // Time window (in milliseconds) to prevent re-processing of the same position after manual trigger
    private static final long PROCESSING_WINDOW_MS = 10000; // 10 seconds

    /**
     * Helper method to get WorldServer by dimension ID.
     * Since dimension IDs can be negative or high values that don't match array indices,
     * we need to find the WorldServer by iterating through the available worlds.
     */
    private static WorldServer getWorldServerByDimension(MinecraftServer server, int dimensionId) {
        if (server == null || server.worldServers == null) {
            return null;
        }

        for (WorldServer worldServer : server.worldServers) {
            if (worldServer != null && worldServer.provider.dimensionId == dimensionId) {
                return worldServer;
            }
        }

        return null; // World with specified dimension not found
    }

    /**
     * Process any deferred additions to the ProcessHandler to avoid ConcurrentModificationException.
     */
    private static void processDeferredProcesses() {
        if (DEFERRED_PROCESS_QUEUE.isEmpty()) {
            return;
        }

        // Process all deferred additions safely
        List<Object> processesToAdd = new ArrayList<>(DEFERRED_PROCESS_QUEUE);
        DEFERRED_PROCESS_QUEUE.clear();

        for (Object process : processesToAdd) {
            try {
                // Use reflection to call ProcessHandler.addProcess
                Class<?> processHandlerClass = Class
                    .forName("com.brandon3055.brandonscore.common.handlers.ProcessHandler");
                Class<?> iProcessClass = Class.forName("com.brandon3055.brandonscore.common.handlers.IProcess");
                java.lang.reflect.Method addMethod = processHandlerClass.getMethod("addProcess", iProcessClass);
                addMethod.invoke(null, process);
            } catch (Exception e) {
                EZNuclear.LOG.error("[EZNuclear] Error adding deferred process: " + e.getMessage(), e);
            }
        }
    }

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
            this.dim = 0; // Default to 0, but callers should provide dimension when possible
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
        EZNuclear.LOG.debug("[EZNuclear] Scheduling task for position: " + pos + " with delay: " + delayMs + "ms");
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
        // Track when this task was created to enable timeout cleanup
        SCHEDULED_TASK_CREATION_TIME.put(key, System.currentTimeMillis());
        EZNuclear.LOG.debug("[EZNuclear] Task added to SCHEDULED list: " + scheduled);
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
        EZNuclear.LOG.debug("[EZNuclear] Marking position for manual trigger: " + pos + " dimension: " + dimension);
        if (pos == null) return;
        MANUAL_TRIGGER.add(new PosKey(pos, dimension));
    }

    public static void markManualTriggerWithPower(ChunkCoordinates pos, double power) {
        EZNuclear.LOG.debug("[EZNuclear] Marking position for manual trigger with power: " + pos + " power: " + power);
        if (pos == null) return;
        // For this method, we use dimension 0 by default, but it would be better to pass dimension
        PosKey key = new PosKey(pos, 0);
        MANUAL_TRIGGER.add(key);
        EXPLOSION_POWERS.put(key, power);
    }

    public static void markManualTriggerWithPower(ChunkCoordinates pos, int dimension, double power) {
        EZNuclear.LOG.debug(
            "[EZNuclear] markManualTriggerWithPower called for position: " + pos
                + " dimension: "
                + dimension
                + " power: "
                + power);
        if (pos == null) return;
        PosKey key = new PosKey(pos, dimension);
        MANUAL_TRIGGER.add(key);
        EXPLOSION_POWERS.put(key, power);
        EZNuclear.LOG.debug("[EZNuclear] Added position " + pos + " to IC2 manual trigger set with power: " + power);
    }

    public static void markDEManualTriggerWithPower(ChunkCoordinates pos, int dimension, double power) {
        EZNuclear.LOG.debug(
            "[EZNuclear] markDEManualTriggerWithPower called for position: " + pos
                + " dimension: "
                + dimension
                + " power: "
                + power);
        if (pos == null) return;
        PosKey key = new PosKey(pos, dimension);
        DE_MANUAL_TRIGGER.add(key);
        EXPLOSION_POWERS.put(key, power);
        EZNuclear.LOG.debug("[EZNuclear] Added position " + pos + " to DE manual trigger set with power: " + power);
    }

    public static boolean consumeManualTrigger(ChunkCoordinates pos, int dimension) {
        if (pos == null) return false;
        return MANUAL_TRIGGER.remove(new PosKey(pos, dimension));
    }

    public static boolean isManualTrigger(ChunkCoordinates pos, int dimension) {
        if (pos == null) return false;
        return MANUAL_TRIGGER.contains(new PosKey(pos, dimension));
    }

    /**
     * Check if this position has recently had an explosion manually triggered
     * to prevent duplicate processing from chain reactions
     */
    public static boolean shouldIgnoreExplosionAt(ChunkCoordinates pos, int dimension) {
        if (pos == null) return false;
        PosKey key = new PosKey(pos, dimension);

        boolean shouldIgnore = PROCESSED_POSITIONS.contains(key);
        EZNuclear.LOG.debug(
            "[EZNuclear] shouldIgnoreExplosionAt: pos=" + pos
                + ", dimension="
                + dimension
                + ", shouldIgnore="
                + shouldIgnore);

        // Check if the position is in the recently processed set
        if (!shouldIgnore) {
            EZNuclear.LOG.debug("[EZNuclear] Position " + pos + " not in PROCESSED_POSITIONS, allowing explosion");
            return false;
        }

        // Check if it's within the time window
        Long processedTime = PROCESSED_POSITIONS_TIME.get(key);
        if (processedTime != null) {
            long timeDiff = System.currentTimeMillis() - processedTime;
            boolean inTimeWindow = timeDiff < PROCESSING_WINDOW_MS;
            EZNuclear.LOG.debug(
                "[EZNuclear] Position " + pos
                    + " time check: diff="
                    + timeDiff
                    + "ms, window="
                    + PROCESSING_WINDOW_MS
                    + "ms, inWindow="
                    + inTimeWindow);
            return inTimeWindow;
        }

        // If we only have it in PROCESSED_POSITIONS but not in PROCESSED_POSITIONS_TIME,
        // consider it still in ignore window
        EZNuclear.LOG.debug("[EZNuclear] Position " + pos + " in PROCESSED_POSITIONS but no time record, ignoring");
        return true;
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
        PROCESSED_POSITIONS.clear(); // Also clear processed positions to maintain consistency
        PROCESSED_POSITIONS_TIME.clear(); // Also clear processed positions time to maintain consistency
        SCHEDULED_TASK_CREATION_TIME.clear(); // Also clear creation time map to prevent memory leaks
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
            PROCESSED_POSITIONS.remove(posKey); // Also remove from processed set to allow future processing if needed
            PROCESSED_POSITIONS_TIME.remove(posKey); // Also remove from processed time set
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
        EZNuclear.LOG.debug("[EZNuclear] triggerExplosionImmediately called for position: " + pos);
        // We need to find the correct PosKey with dimension from the MANUAL_TRIGGER set
        PosKey foundPosKey = null;
        for (PosKey key : MANUAL_TRIGGER) {
            if (key.x == pos.posX && key.y == pos.posY && key.z == pos.posZ) {
                foundPosKey = key;
                break;
            }
        }

        if (foundPosKey == null) {
            EZNuclear.LOG.debug("[EZNuclear] Position not marked for manual trigger: " + pos);
            return;
        }

        // Remove from manual trigger set
        MANUAL_TRIGGER.remove(foundPosKey);

        // Get stored explosion power if available
        Double power = EXPLOSION_POWERS.get(foundPosKey);
        if (power == null) {
            power = 4.0; // Default power if not stored
        }
        EZNuclear.LOG.debug("[EZNuclear] Using explosion power: " + power);

        // Set flag to allow the explosion to proceed without being re-cancelled
        setAllowNextExplosion();

        // Create and trigger the explosion immediately at the specified position
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            // Get the world by dimension ID instead of using it as array index
            WorldServer world = getWorldServerByDimension(server, foundPosKey.dim);
            if (world != null) {
                createAndExecuteIC2Explosion(world, pos.posX, pos.posY, pos.posZ, power.floatValue());
            } else {
                EZNuclear.LOG.warn("[EZNuclear] World not found for dimension: " + foundPosKey.dim);
            }
        } else {
            EZNuclear.LOG.warn("[EZNuclear] Minecraft server is null, cannot trigger explosion");
        }

        // Clean up stored power
        EXPLOSION_POWERS.remove(foundPosKey);

        // Mark this position as processed to prevent re-interception
        PROCESSED_POSITIONS.add(foundPosKey);
        PROCESSED_POSITIONS_TIME.put(foundPosKey, System.currentTimeMillis());
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onChat(ServerChatEvent event) {
        EZNuclear.LOG.debug(
            "[EZNuclear] onChat called with message: " + event.message
                + " from player: "
                + event.player.getCommandSenderName());
        String triggerMessage = Constants.COMMAND_EZUNCLEAR; // 你可以改成任何触发消息

        if (event.message != null && event.message.equals(triggerMessage)) {
            EZNuclear.LOG.debug("[EZNuclear] Trigger message detected, executing all scheduled tasks");
            executeAllNow();
        }

        // Handle manual trigger command "坏了坏了"
        if (event.message != null && event.message.equals(Constants.COMMAND_OH_NO)) {
            EZNuclear.LOG.debug(
                "[EZNuclear] Manual trigger command detected from player: " + event.player.getCommandSenderName());
            EZNuclear.LOG.debug("[EZNuclear] IC2 Manual Trigger Set size: " + MANUAL_TRIGGER.size());
            EZNuclear.LOG.debug("[EZNuclear] DE Manual Trigger Set size: " + DE_MANUAL_TRIGGER.size());

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

        EZNuclear.LOG.debug(
            "[EZNuclear] processManualTriggers called for " + (isDE ? "DE" : "IC2")
                + ", found "
                + positionsToTrigger.size()
                + " positions to trigger");
        EZNuclear.LOG.debug("[EZNuclear] triggerSet size before processing: " + triggerSet.size());
        for (PosKey posKey : positionsToTrigger) {
            ChunkCoordinates pos = new ChunkCoordinates(posKey.x, posKey.y, posKey.z);
            EZNuclear.LOG.debug("[EZNuclear] Triggering " + (isDE ? "DE" : "IC2") + " explosion at position: " + pos);

            if (triggerSet.contains(posKey)) {
                triggerSet.remove(posKey);
                EZNuclear.LOG.debug("[EZNuclear] Removed position " + pos + " from trigger set");

                // Get stored explosion power if available
                Double power = EXPLOSION_POWERS.get(posKey);
                if (power == null) {
                    power = 4.0; // Default power if not stored
                }
                EZNuclear.LOG.debug("[EZNuclear] Using " + (isDE ? "DE" : "IC2") + " explosion power: " + power);

                // Set flag to allow the explosion to proceed without being re-cancelled
                setAllowNextExplosion();
                EZNuclear.LOG.debug("[EZNuclear] Set allowNextExplosion flag to true");

                // Create and trigger the explosion immediately at the specified position
                MinecraftServer server = MinecraftServer.getServer();
                if (server != null) {
                    // Get the world by dimension ID instead of using it as array index
                    WorldServer world = getWorldServerByDimension(server, posKey.dim);
                    if (world != null) {
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
                                Object newExp = ctor
                                    .newInstance(world, pos.posX, pos.posY, pos.posZ, power.floatValue());

                                // Add to process handler (deferred to avoid ConcurrentModificationException)
                                DEFERRED_PROCESS_QUEUE.add(newExp);

                                EZNuclear.LOG.debug(
                                    "[EZNuclear] DE ReactorExplosion triggered at position: " + pos
                                        + " with power: "
                                        + power);
                            } catch (Exception e) {
                                EZNuclear.LOG.error("[EZNuclear] Error creating DE explosion: " + e.getMessage(), e);
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
                                EZNuclear.LOG.debug(
                                    "[EZNuclear] DE fallback vanilla explosion triggered at position: " + pos
                                        + " with power: "
                                        + power);
                            }
                        } else {
                            // Create the IC2 explosion using the common helper method
                            createAndExecuteIC2Explosion(world, pos.posX, pos.posY, pos.posZ, power.floatValue());
                            EZNuclear.LOG.debug(
                                "[EZNuclear] IC2 Explosion triggered at position: " + pos + " with power: " + power);
                        }
                    } else {
                        EZNuclear.LOG.warn("[EZNuclear] World not found for dimension: " + posKey.dim);
                    }
                } else {
                    EZNuclear.LOG.warn("[EZNuclear] Minecraft server is null, cannot trigger explosion");
                }

                // Clean up stored power
                EXPLOSION_POWERS.remove(posKey);
                EZNuclear.LOG.debug("[EZNuclear] Cleaned up stored power for position: " + pos);

                // Mark this position as processed to prevent re-interception
                PROCESSED_POSITIONS.add(posKey);
                PROCESSED_POSITIONS_TIME.put(posKey, System.currentTimeMillis());
                EZNuclear.LOG.debug("[EZNuclear] Marked position " + pos + " as processed to prevent re-interception");
            }
        }
        EZNuclear.LOG.debug("[EZNuclear] processManualTriggers completed for " + (isDE ? "DE" : "IC2"));
    }

    /**
     * Trigger DE explosion immediately for a manually marked position.
     * This creates a new explosion task and executes it immediately.
     */
    public static void triggerDEExplosionImmediately(ChunkCoordinates pos) {
        EZNuclear.LOG.debug("[EZNuclear] triggerDEExplosionImmediately called for position: " + pos);
        // We need to find the correct PosKey with dimension from the MANUAL_TRIGGER set
        PosKey foundPosKey = null;
        for (PosKey key : MANUAL_TRIGGER) {
            if (key.x == pos.posX && key.y == pos.posY && key.z == pos.posZ) {
                foundPosKey = key;
                break;
            }
        }

        if (foundPosKey == null) {
            EZNuclear.LOG.debug("[EZNuclear] Position not marked for manual trigger: " + pos);
            return;
        }

        // Remove from manual trigger set
        MANUAL_TRIGGER.remove(foundPosKey);

        // Get stored explosion power if available
        Double power = EXPLOSION_POWERS.get(foundPosKey);
        if (power == null) {
            power = 4.0; // Default power if not stored
        }
        EZNuclear.LOG.debug("[EZNuclear] Using DE explosion power: " + power);

        // Create and trigger the explosion immediately at the specified position
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            // Get the world by dimension ID instead of using it as array index
            WorldServer world = getWorldServerByDimension(server, foundPosKey.dim);
            if (world != null) {
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

                EZNuclear.LOG.debug("[EZNuclear] DE Explosion triggered at position: " + pos + " with power: " + power);
            } else {
                EZNuclear.LOG.warn("[EZNuclear] World not found for dimension: " + foundPosKey.dim);
            }
        } else {
            EZNuclear.LOG.warn("[EZNuclear] Minecraft server is null, cannot trigger DE explosion");
        }

        // Clean up stored power
        EXPLOSION_POWERS.remove(foundPosKey);

        // Mark this position as processed to prevent re-interception
        PROCESSED_POSITIONS.add(foundPosKey);
        PROCESSED_POSITIONS_TIME.put(foundPosKey, System.currentTimeMillis());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerTick(TickEvent.ServerTickEvent event) {
        // Process deferred additions at START to avoid ConcurrentModificationException
        // This must happen before ProcessHandler.onServerTick runs
        if (event.phase == TickEvent.Phase.START) {
            processDeferredProcesses();
            return;
        }

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
                if (server != null && server.worldServers != null) {
                    for (net.minecraft.world.WorldServer ws : server.worldServers) {
                        if (ws == null) continue; // Skip null worlds
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
                                                if (players != null) {
                                                    for (net.minecraft.entity.player.EntityPlayerMP p : players) {
                                                        if (p != null) {
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
                                                                            .translateToLocal(
                                                                                "info.ezunclear.interact")));
                                                            }
                                                        }
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
                                                // add to process handler (deferred to avoid
                                                // ConcurrentModificationException)
                                                DEFERRED_PROCESS_QUEUE.add(newExp);
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
                            EZNuclear.LOG.warn("Error processing world during reactor scan: " + t.getMessage());
                        }
                    }
                }
            } catch (Throwable t) {
                // LOGGER.warn("PendingMeltdown.scan failed: {}", t.getMessage());
                EZNuclear.LOG.warn("PendingMeltdown reactor scan failed: " + t.getMessage());
            }
        }

        // Cleanup expired tasks to prevent memory leaks
        cleanupExpiredTasks();

        // Process any deferred additions to avoid ConcurrentModificationException
        processDeferredProcesses();
    }

    /**
     * Common method to create and execute DE explosion with proper error handling
     */
    public static void createAndExecuteDEExplosion(net.minecraft.world.World world, int x, int y, int z, float power) {
        if (world == null) {
            EZNuclear.LOG.warn("[EZNuclear] World is null, cannot create DE explosion");
            return;
        }

        try {
            // Create DE's ReactorExplosion with provided power
            Class<?> reClass = Class.forName(
                "com.brandon3055.draconicevolution.common.tileentities.multiblocktiles.reactor.ReactorExplosion");
            java.lang.reflect.Constructor<?> ctor = reClass
                .getConstructor(net.minecraft.world.World.class, int.class, int.class, int.class, float.class);
            Object newExp = ctor.newInstance(world, x, y, z, power);

            // Add to process handler (deferred to avoid ConcurrentModificationException)
            DEFERRED_PROCESS_QUEUE.add(newExp);

            // Remove the core block after triggering the explosion
            world.setBlockToAir(x, y, z);
        } catch (Exception ex) {
            EZNuclear.LOG.error("[EZNuclear] Error creating DE explosion: " + ex.getMessage(), ex);
            // If DE classes are not available, fallback to vanilla explosion
            net.minecraft.world.Explosion vanillaExplosion = new net.minecraft.world.Explosion(
                world,
                null,
                (double) x + 0.5D,
                (double) y + 0.5D,
                (double) z + 0.5D,
                power);
            vanillaExplosion.doExplosionA();
            vanillaExplosion.doExplosionB(true);
            world.setBlockToAir(x, y, z);
        }
    }

    /**
     * Common method to create and execute IC2 explosion with proper error handling
     */
    public static void createAndExecuteIC2Explosion(net.minecraft.world.World world, int x, int y, int z, float power) {
        if (world == null) {
            EZNuclear.LOG.warn("[EZNuclear] World is null, cannot create IC2 explosion");
            return;
        }

        try {
            // Create the IC2 explosion
            ExplosionIC2 explosion = new ExplosionIC2(world, null, x, y, z, power, 0.01F, ExplosionIC2.Type.Nuclear);
            explosion.doExplosion();

            EZNuclear.LOG.debug(
                "[EZNuclear] IC2 Explosion triggered at position: [" + x
                    + ","
                    + y
                    + ","
                    + z
                    + "] with power: "
                    + power);
        } catch (Exception ex) {
            EZNuclear.LOG.error("[EZNuclear] Error creating IC2 explosion: " + ex.getMessage(), ex);
        }
    }

    /**
     * Cleanup expired tasks to prevent memory leaks
     */
    private static void cleanupExpiredTasks() {
        long now = System.currentTimeMillis();
        long timeoutMs = Config.taskTimeoutMinutes * 60 * 1000L; // Convert minutes to milliseconds

        List<Scheduled> expired = new ArrayList<>();
        for (Scheduled s : SCHEDULED) {
            Long creationTime = SCHEDULED_TASK_CREATION_TIME.get(s.pos);
            if (creationTime != null && (now - creationTime) > timeoutMs) {
                expired.add(s);
            }
        }

        if (!expired.isEmpty()) {
            SCHEDULED.removeAll(expired);
            for (Scheduled s : expired) {
                POSITIONS.remove(s.pos);
                REENTRY.remove(s.pos);
                SCHEDULED_TASK_CREATION_TIME.remove(s.pos);
            }
        }
    }
}
