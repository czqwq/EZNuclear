package com.czqwq.EZNuclear.data;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.ServerChatEvent;

import com.brandon3055.draconicevolution.common.tileentities.multiblocktiles.reactor.ReactorExplosion;
import com.brandon3055.draconicevolution.common.tileentities.multiblocktiles.reactor.TileReactorCore;
import com.brandon3055.draconicevolution.common.utils.handlers.ProcessHandler;
import com.czqwq.EZNuclear.Config;
import com.czqwq.EZNuclear.EZNuclear;
import com.czqwq.EZNuclear.util.Constants;
import com.czqwq.EZNuclear.util.MessageUtils;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import ic2.core.ExplosionIC2;

/**
 * 核电爆炸的延时 / 拦截 / 手动触发调度中枢。
 *
 * <p>
 * 所有状态都以 (x, y, z, dimension) 为 key，避免不同维度同坐标互相干扰。
 * 所有任务都在服务器 tick 线程里执行（{@link #onServerTick} 的 END 阶段）。
 */
public class PendingMeltdown {

    // 延时任务队列：到点后在服务器线程执行
    private static final List<Scheduled> SCHEDULED = new CopyOnWriteArrayList<>();
    // 允许"原生爆炸"放行一次的位置（我们自己的延时回调，以及扫描触发的熔毁）
    private static final Set<PosKey> REENTRY = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // 等待玩家手动触发的 IC2 / DE 位置
    private static final Set<PosKey> MANUAL_TRIGGER = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<PosKey> DE_MANUAL_TRIGGER = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // 一次性放行令牌：手动触发时由本模组自己 new 出来的爆炸实例需要绕过一次拦截
    private static final Set<PosKey> ALLOW_ONCE = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // 已经排好延时爆炸的位置，用于吞掉连锁反应造成的重复排程
    private static final Set<PosKey> PENDING_EXPLOSIONS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // 手动触发时使用的爆炸威力
    private static final Map<PosKey, Double> EXPLOSION_POWERS = new ConcurrentHashMap<>();
    // 刚刚被手动触发过的位置，避免同一次爆炸被反复处理
    private static final Set<PosKey> PROCESSED_POSITIONS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<PosKey, Long> PROCESSED_POSITIONS_TIME = new ConcurrentHashMap<>();
    // 任务创建时间，用于超时清理，避免内存泄漏
    private static final Map<PosKey, Long> SCHEDULED_TASK_CREATION_TIME = new ConcurrentHashMap<>();

    // 防止重复处理的窗口（毫秒）
    private static final long PROCESSING_WINDOW_MS = 10000;
    // 过热反应堆扫描间隔（tick）
    private static final int SCAN_INTERVAL_TICKS = 20;
    // 扫描触发的熔毁延时（毫秒）
    private static final long SCAN_MELTDOWN_DELAY_MS = 5000L;

    private int tickCounter = 0;
    private int lastProcessedTick = -1;

    private static final class Scheduled {

        final long executeAtMillis;
        final Runnable task;
        final PosKey pos;

        Scheduled(long executeAtMillis, Runnable task, PosKey pos) {
            this.executeAtMillis = executeAtMillis;
            this.task = task;
            this.pos = pos;
        }
    }

    private static final class PosKey {

        final int x, y, z, dim;

        PosKey(ChunkCoordinates c, int dim) {
            this(c.posX, c.posY, c.posZ, dim);
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

        @Override
        public String toString() {
            return "(" + x + "," + y + "," + z + "|dim" + dim + ")";
        }
    }

    private static WorldServer getWorldServerByDimension(MinecraftServer server, int dimensionId) {
        if (server == null || server.worldServers == null) {
            return null;
        }
        for (WorldServer worldServer : server.worldServers) {
            if (worldServer != null && worldServer.provider.dimensionId == dimensionId) {
                return worldServer;
            }
        }
        return null;
    }

    private static boolean isOperator(EntityPlayerMP player) {
        if (player == null) return false;
        try {
            MinecraftServer server = MinecraftServer.getServer();
            // 单人存档：只有房主可以触发
            if (server != null && server.isSinglePlayer()) {
                return player.getCommandSenderName()
                    .equalsIgnoreCase(server.getServerOwner());
            }
            return player.canCommandSenderUseCommand(2, "EZNuclear");
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 延时任务

    /**
     * 排一个延时任务，{@code dimension} 必须传真实维度。
     */
    public static void schedule(ChunkCoordinates pos, Runnable task, long delayMs, int dimension) {
        if (pos == null || task == null) return;
        PosKey key = new PosKey(pos, dimension);
        long executeAt = System.currentTimeMillis() + Math.max(0, delayMs);
        SCHEDULED.add(new Scheduled(executeAt, task, key));
        SCHEDULED_TASK_CREATION_TIME.put(key, System.currentTimeMillis());
        EZNuclear.LOG.debug("[EZNuclear] Scheduled task at {} in {}ms", key, delayMs);
    }

    // ------------------------------------------------------------------ 放行 / 去重状态

    /** 允许该位置的原生爆炸逻辑放行一次（由本模组的延时回调使用）。 */
    public static void markReentry(ChunkCoordinates pos, int dimension) {
        if (pos == null) return;
        REENTRY.add(new PosKey(pos, dimension));
    }

    public static boolean consumeReentry(ChunkCoordinates pos, int dimension) {
        if (pos == null) return false;
        return REENTRY.remove(new PosKey(pos, dimension));
    }

    /** 一次性放行令牌：用于本模组自己创建、需要绕过拦截的爆炸实例。 */
    public static void allowExplosionOnce(ChunkCoordinates pos, int dimension) {
        if (pos == null) return;
        ALLOW_ONCE.add(new PosKey(pos, dimension));
    }

    public static boolean consumeAllowExplosion(ChunkCoordinates pos, int dimension) {
        if (pos == null) return false;
        return ALLOW_ONCE.remove(new PosKey(pos, dimension));
    }

    public static void clearAllowExplosion(ChunkCoordinates pos, int dimension) {
        if (pos == null) return;
        ALLOW_ONCE.remove(new PosKey(pos, dimension));
    }

    /** 标记该位置已经有排队中的爆炸。 */
    public static void markExplosionPending(ChunkCoordinates pos, int dimension) {
        if (pos == null) return;
        PENDING_EXPLOSIONS.add(new PosKey(pos, dimension));
    }

    public static boolean isExplosionPending(ChunkCoordinates pos, int dimension) {
        if (pos == null) return false;
        return PENDING_EXPLOSIONS.contains(new PosKey(pos, dimension));
    }

    public static void clearExplosionPending(ChunkCoordinates pos, int dimension) {
        if (pos == null) return;
        PENDING_EXPLOSIONS.remove(new PosKey(pos, dimension));
    }

    private static void clearExplosionPending(PosKey key) {
        if (key != null) PENDING_EXPLOSIONS.remove(key);
    }

    // ------------------------------------------------------------------ 手动触发

    public static void markManualTriggerWithPower(ChunkCoordinates pos, int dimension, double power) {
        if (pos == null) return;
        PosKey key = new PosKey(pos, dimension);
        MANUAL_TRIGGER.add(key);
        EXPLOSION_POWERS.put(key, power);
        EZNuclear.LOG.debug("[EZNuclear] Marked IC2 manual trigger at {} with power {}", key, power);
    }

    public static void markDEManualTrigger(ChunkCoordinates pos, int dimension) {
        if (pos == null) return;
        PosKey key = new PosKey(pos, dimension);
        DE_MANUAL_TRIGGER.add(key);
        EZNuclear.LOG.debug("[EZNuclear] Marked DE manual trigger at {}", key);
    }

    private static boolean isDEManualTrigger(ChunkCoordinates pos, int dimension) {
        return pos != null && DE_MANUAL_TRIGGER.contains(new PosKey(pos, dimension));
    }

    /**
     * 该位置最近是否刚被处理过。返回 true 时调用方应放行原生爆炸（连锁反应不再重复延时）。
     */
    public static boolean shouldIgnoreExplosionAt(ChunkCoordinates pos, int dimension) {
        if (pos == null) return false;
        PosKey key = new PosKey(pos, dimension);
        if (!PROCESSED_POSITIONS.contains(key)) return false;
        Long processedTime = PROCESSED_POSITIONS_TIME.get(key);
        if (processedTime == null) return true;
        return (System.currentTimeMillis() - processedTime) < PROCESSING_WINDOW_MS;
    }

    /**
     * 立刻执行所有排队任务（聊天命令触发）。
     */
    public static void executeAllNow() {
        List<Scheduled> copy = new ArrayList<>(SCHEDULED);
        SCHEDULED.clear();
        MANUAL_TRIGGER.clear();
        DE_MANUAL_TRIGGER.clear();
        ALLOW_ONCE.clear();
        REENTRY.clear();
        PENDING_EXPLOSIONS.clear();
        EXPLOSION_POWERS.clear();
        PROCESSED_POSITIONS.clear();
        PROCESSED_POSITIONS_TIME.clear();
        SCHEDULED_TASK_CREATION_TIME.clear();
        EZNuclear.LOG.debug("[EZNuclear] Executing {} scheduled task(s) immediately", copy.size());
        for (Scheduled s : copy) {
            try {
                s.task.run();
            } catch (Throwable t) {
                EZNuclear.LOG.error("[EZNuclear] Error running scheduled task at " + s.pos, t);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onChat(ServerChatEvent event) {
        if (event == null || event.message == null) return;

        boolean triggerAll = Constants.COMMAND_EZUNCLEAR.equals(event.message);
        boolean manualTrigger = Constants.COMMAND_OH_NO.equals(event.message);
        if (!triggerAll && !manualTrigger) return;

        if (Config.TriggerRequireOP && !isOperator(event.player)) {
            if (event.player != null) {
                event.player.addChatMessage(new ChatComponentTranslation("info.ezunclear.nopermission"));
            }
            return;
        }

        if (triggerAll) {
            EZNuclear.LOG.debug(
                "[EZNuclear] {} requested immediate execution of all scheduled explosions",
                event.player.getCommandSenderName());
            executeAllNow();
        }

        if (manualTrigger) {
            EZNuclear.LOG.debug(
                "[EZNuclear] {} triggered manual explosions (IC2={}, DE={})",
                event.player.getCommandSenderName(),
                MANUAL_TRIGGER.size(),
                DE_MANUAL_TRIGGER.size());
            triggerAllManualExplosions();
        }
    }

    /**
     * 触发所有已登记的手动引信，等价于玩家发送 '坏了坏了'。
     */
    public static void triggerAllManualExplosions() {
        processManualTriggers(MANUAL_TRIGGER, false);
        processManualTriggers(DE_MANUAL_TRIGGER, true);
    }

    private static void processManualTriggers(Set<PosKey> triggerSet, boolean isDE) {
        List<PosKey> positions = new ArrayList<>(triggerSet);
        for (PosKey posKey : positions) {
            if (!triggerSet.remove(posKey)) continue;

            if (isDE) {
                // DE 侧威力由 Config.isDEPowerOverridden() 决定：覆写则用 DEManualExplosivePower，否则走 DE 原生 goBoom
                EZNuclear.LOG.debug("[EZNuclear] Triggering DE explosion at {}", posKey);
                triggerReactorExplosion(posKey.x, posKey.y, posKey.z, posKey.dim);
            } else {
                // 拦截时记录下来的威力：覆写时是 IC2ManualExplosivePower，否则是 IC2 自己的威力
                Double stored = EXPLOSION_POWERS.remove(posKey);
                double power = stored != null ? stored : 4.0D;
                EZNuclear.LOG.debug("[EZNuclear] Triggering IC2 explosion at {} with power {}", posKey, power);
                createAndExecuteIC2ExplosionAt(posKey.x, posKey.y, posKey.z, posKey.dim, (float) power);
            }

            PROCESSED_POSITIONS.add(posKey);
            PROCESSED_POSITIONS_TIME.put(posKey, System.currentTimeMillis());
        }
    }

    // ------------------------------------------------------------------ 爆炸构造

    /**
     * 用给定威力创建 DE 反应堆爆炸（覆写威力或反应堆实体丢失时使用）。
     */
    public static void createAndExecuteDEExplosion(World world, int x, int y, int z, float power) {
        if (world == null) {
            EZNuclear.LOG.warn("[EZNuclear] World is null, cannot create DE explosion");
            return;
        }
        ProcessHandler.addProcess(new ReactorExplosion(world, x, y, z, power));
        // DE 自己的 goBoom 也会移除核心方块，这里保持一致
        world.setBlockToAir(x, y, z);
    }

    /**
     * 用配置威力创建 IC2 核爆（手动触发路径使用）。
     */
    public static void createAndExecuteIC2Explosion(World world, int x, int y, int z, float power) {
        if (world == null) {
            EZNuclear.LOG.warn("[EZNuclear] World is null, cannot create IC2 explosion");
            return;
        }
        ExplosionIC2 explosion = new ExplosionIC2(world, null, x, y, z, power, 0.01F, ExplosionIC2.Type.Nuclear);
        explosion.doExplosion();
    }

    /**
     * 用给定威力创建 IC2 核爆（自己解析世界，供延时任务和手动触发使用）。
     *
     * 这次爆炸是本模组创建的，需要放行一次令牌，否则会被自己的拦截逻辑再次取消。
     */
    public static void createAndExecuteIC2ExplosionAt(int x, int y, int z, int dimension, float power) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            EZNuclear.LOG.warn("[EZNuclear] Minecraft server is null, cannot trigger IC2 explosion");
            return;
        }
        WorldServer world = getWorldServerByDimension(server, dimension);
        if (world == null) {
            EZNuclear.LOG.warn("[EZNuclear] World not found for dimension: " + dimension);
            return;
        }
        allowExplosionOnce(new ChunkCoordinates(x, y, z), dimension);
        createAndExecuteIC2Explosion(world, x, y, z, power);
    }

    /**
     * 触发一次 DE 反应堆爆炸，威力取决于 {@link Config#isDEPowerOverridden()}：
     * <ul>
     * <li>开启覆写：直接用 {@link Config#DEManualExplosivePower} 制造爆炸；</li>
     * <li>未开启覆写：放行一次 DE 原生 {@code goBoom}，威力按燃料计算并附带客户端特效；</li>
     * <li>反应堆实体已丢失时退回到 {@link Config#DEManualExplosivePower}，保证爆炸仍然发生。</li>
     * </ul>
     */
    public static void triggerReactorExplosion(int x, int y, int z, int dimension) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        WorldServer world = getWorldServerByDimension(server, dimension);
        if (world == null) {
            EZNuclear.LOG.warn("[EZNuclear] World not found for dimension: " + dimension);
            return;
        }

        if (Config.isDEPowerOverridden()) {
            createAndExecuteDEExplosion(world, x, y, z, (float) Config.DEManualExplosivePower);
            return;
        }

        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileReactorCore) {
            markReentry(new ChunkCoordinates(x, y, z), dimension);
            try {
                Method goBoom = TileReactorCore.class.getDeclaredMethod("goBoom");
                goBoom.setAccessible(true);
                goBoom.invoke(tile);
                return;
            } catch (Throwable t) {
                EZNuclear.LOG.warn("[EZNuclear] Failed to invoke native reactor goBoom, using configured power", t);
            }
        } else {
            EZNuclear.LOG.warn("[EZNuclear] Reactor core missing at (" + x + "," + y + "," + z + ") dim " + dimension);
        }

        createAndExecuteDEExplosion(world, x, y, z, (float) Config.DEManualExplosivePower);
    }

    // ------------------------------------------------------------------ 服务器 tick

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;

        // 本模组同时注册在 FML 与 Forge 两条总线上，同一 tick 会收到两次事件，这里去重
        int tick = server.getTickCounter();
        if (tick == lastProcessedTick) return;
        lastProcessedTick = tick;

        tickCounter++;
        long now = System.currentTimeMillis();

        List<Scheduled> due = new ArrayList<>();
        for (Scheduled s : SCHEDULED) {
            if (s.executeAtMillis <= now) due.add(s);
        }
        if (!due.isEmpty()) {
            // 先摘掉再执行，避免任务内部重新排程时被重复触发
            SCHEDULED.removeAll(due);
            for (Scheduled s : due) {
                try {
                    s.task.run();
                } catch (Throwable t) {
                    EZNuclear.LOG.error("[EZNuclear] Error running scheduled meltdown task at " + s.pos, t);
                } finally {
                    clearExplosionPending(s.pos);
                    REENTRY.remove(s.pos);
                }
            }
        }

        if (Config.DEExplosion && (tickCounter % SCAN_INTERVAL_TICKS) == 0) {
            try {
                scanOverheatedReactors(server);
            } catch (Throwable t) {
                EZNuclear.LOG.warn("PendingMeltdown reactor scan failed: " + t.getMessage());
            }
        }

        cleanupExpiredTasks();
    }

    /**
     * 结构失效但温度已经过热的反应堆不会自己调用 goBoom，这里定期兜底。
     */
    private void scanOverheatedReactors(MinecraftServer server) {
        if (server.worldServers == null) return;
        for (WorldServer world : server.worldServers) {
            if (world == null) continue;
            int dimensionId = world.provider.dimensionId;
            List<?> tiles = world.loadedTileEntityList;
            for (Object obj : tiles) {
                if (!(obj instanceof TileReactorCore)) continue;
                TileReactorCore reactor = (TileReactorCore) obj;
                if (reactor.reactionTemperature <= 2000.0D) continue;

                ChunkCoordinates pos = new ChunkCoordinates(reactor.xCoord, reactor.yCoord, reactor.zCoord);
                if (isExplosionPending(pos, dimensionId) || shouldIgnoreExplosionAt(pos, dimensionId)) continue;

                if (Config.requireCommandToExplode) {
                    if (!isDEManualTrigger(pos, dimensionId)) {
                        markDEManualTrigger(pos, dimensionId);
                        MessageUtils.broadcast(Constants.MSG_EASY_NUCLEAR);
                    }
                    continue;
                }

                final int x = reactor.xCoord;
                final int y = reactor.yCoord;
                final int z = reactor.zCoord;
                final int dimension = dimensionId;
                markExplosionPending(pos, dimensionId);
                schedule(pos, () -> {
                    MessageUtils.broadcast(Constants.MSG_OH_NO);
                    triggerReactorExplosion(x, y, z, dimension);
                }, SCAN_MELTDOWN_DELAY_MS, dimensionId);
            }
        }
    }

    private static void cleanupExpiredTasks() {
        long now = System.currentTimeMillis();
        long timeoutMs = Config.taskTimeoutMinutes * 60 * 1000L;

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
                clearExplosionPending(s.pos);
                REENTRY.remove(s.pos);
                ALLOW_ONCE.remove(s.pos);
                SCHEDULED_TASK_CREATION_TIME.remove(s.pos);
                EZNuclear.LOG.warn("[EZNuclear] Dropped expired scheduled task at " + s.pos);
            }
        }
    }
}
