package com.czqwq.EZNuclear.mixin;

import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.brandon3055.draconicevolution.common.tileentities.multiblocktiles.reactor.TileReactorCore;
import com.czqwq.EZNuclear.Config;
import com.czqwq.EZNuclear.data.PendingMeltdown;

import gregtech.api.util.GTUtility;

@Mixin(value = TileReactorCore.class, remap = false)
public abstract class TileReactorCoreMixin {

    private static final Logger LOGGER = LogManager.getLogger("EZNuclear.TileReactorCoreMixin");
    private static long lastLogTime_goBoom = 0;
    private static long lastLogTime_disabled = 0;
    private static long lastLogTime_processing = 0;
    private static long lastLogTime_reentry = 0;
    private static long lastLogTime_scheduled = 0;
    private static long lastLogTime_messages = 0;
    private static long lastLogTime_creation = 0;
    private static long lastLogTime_power = 0;
    private static long lastLogTime_fallback = 0;
    private static final long LOG_INTERVAL = 5000; // 5 seconds

    // reentry is managed by PendingMeltdown to keep a single source of truth

    @Inject(method = "goBoom", remap = false, at = @At("HEAD"), cancellable = true)
    private void onGoBoom(CallbackInfo ci) {
        LOGGER.info("TileReactorCoreMixin.onGoBoom called");

        // Check if DE explosions are disabled in config
        if (!Config.DEExplosion) {
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_disabled > LOG_INTERVAL) {
                    LOGGER.info("DE explosions disabled in config, cancelling explosion but sending messages");
                    lastLogTime_disabled = currentTime;
                }
            }

            // Even if explosion is disabled, still send the message to players
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                if (!server.isSinglePlayer()) {
                    List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                            LOGGER.info("Sending initial message to {} players", players.size());
                            lastLogTime_messages = currentTime;
                        }
                    }
                    for (EntityPlayerMP p : players) {
                        GTUtility.sendChatToPlayer(p, StatCollector.translateToLocal("info.ezunclear"));
                    }
                } else {
                    // For single player, try to send message to client
                    try {
                        Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
                        Object mc = mcClass.getMethod("getMinecraft")
                            .invoke(null);
                        Object thePlayer = mcClass.getField("thePlayer")
                            .get(mc);
                        if (thePlayer != null) {
                            Class<?> chatClass = Class.forName("net.minecraft.util.ChatComponentTranslation");
                            Object chat = chatClass.getConstructor(String.class, Object[].class)
                                .newInstance("info.ezunclear", new Object[0]);
                            Class<?> iChatClass = Class.forName("net.minecraft.util.IChatComponent");
                            thePlayer.getClass()
                                .getMethod("addChatMessage", iChatClass)
                                .invoke(thePlayer, chat);
                            if (Config.DebugMode) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                                    LOGGER.info("Sent initial message to single player");
                                    lastLogTime_messages = currentTime;
                                }
                            }
                        }
                    } catch (Throwable t) {
                        if (Config.DebugMode) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                                LOGGER.warn("Failed to send client chat message: " + t.getMessage());
                                lastLogTime_messages = currentTime;
                            }
                        }
                        // Reflection failed, skipping client chat message
                    }
                }

                // Schedule the second message after 5 seconds
                PendingMeltdown.schedule(new ChunkCoordinates(0, 0, 0), () -> {
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                            LOGGER.info("Sending second message after 5 seconds (disabled explosion)");
                            lastLogTime_messages = currentTime;
                        }
                    }
                    MinecraftServer srv = MinecraftServer.getServer();
                    if (srv != null) {
                        if (!srv.isSinglePlayer()) {
                            List<EntityPlayerMP> players = srv.getConfigurationManager().playerEntityList;
                            for (EntityPlayerMP p : players) {
                                GTUtility.sendChatToPlayer(
                                    p,
                                    StatCollector.translateToLocal("info.ezunclear.preventexplosion"));
                            }
                        } else {
                            try {
                                Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
                                Object mc = mcClass.getMethod("getMinecraft")
                                    .invoke(null);
                                Object thePlayer = mcClass.getField("thePlayer")
                                    .get(mc);
                                if (thePlayer != null) {
                                    Class<?> chatClass = Class.forName("net.minecraft.util.ChatComponentTranslation");
                                    Object chat = chatClass.getConstructor(String.class, Object[].class)
                                        .newInstance("info.ezunclear.preventexplosion", new Object[0]);
                                    Class<?> iChatClass = Class.forName("net.minecraft.util.IChatComponent");
                                    thePlayer.getClass()
                                        .getMethod("addChatMessage", iChatClass)
                                        .invoke(thePlayer, chat);
                                }
                            } catch (Throwable t) {
                                if (Config.DebugMode) {
                                    long currentTime = System.currentTimeMillis();
                                    if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                                        LOGGER.warn("Failed to send client chat message (delayed): " + t.getMessage());
                                        lastLogTime_messages = currentTime;
                                    }
                                }
                                // Reflection failed, skipping client chat message
                            }
                        }
                    }
                }, 5000L);
            }

            ci.cancel();
            return;
        }

        TileEntity te = (TileEntity) (Object) this;
        ChunkCoordinates pos = new ChunkCoordinates(te.xCoord, te.yCoord, te.zCoord);
        if (Config.DebugMode) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastLogTime_processing > LOG_INTERVAL) {
                LOGGER.info("Processing explosion at position: ({}, {}, {})", te.xCoord, te.yCoord, te.zCoord);
                lastLogTime_processing = currentTime;
            }
        }

        if (com.czqwq.EZNuclear.data.PendingMeltdown.consumeReentry(pos)) {
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_reentry > LOG_INTERVAL) {
                    LOGGER.info("Reentry flag consumed, allowing original goBoom");
                    lastLogTime_reentry = currentTime;
                }
            }
            return; // allow original goBoom
        }

        if (PendingMeltdown.schedule(pos, createScheduledTask(te), 5000L)) {
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_scheduled > LOG_INTERVAL) {
                    LOGGER.info("Scheduled delayed explosion, cancelling original");
                    lastLogTime_scheduled = currentTime;
                }
            }
            ci.cancel();
            PendingMeltdown.schedule(pos, () -> {}, 0L); // touch pos to log scheduling if needed

            MinecraftServer server = MinecraftServer.getServer();
            if (server != null && !server.isSinglePlayer()) {
                List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
                if (Config.DebugMode) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                        LOGGER.info("Sending initial message to {} players for delayed explosion", players.size());
                        lastLogTime_messages = currentTime;
                    }
                }
                for (EntityPlayerMP p : players) {
                    GTUtility.sendChatToPlayer(p, StatCollector.translateToLocal("info.ezunclear"));
                }
            } else {
                World world = te.getWorldObj();
                if (world != null && world.isRemote) {
                    try {
                        Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
                        Object mc = mcClass.getMethod("getMinecraft")
                            .invoke(null);
                        Object thePlayer = mcClass.getField("thePlayer")
                            .get(mc);
                        if (thePlayer != null) {
                            Class<?> chatClass = Class.forName("net.minecraft.util.ChatComponentTranslation");
                            Object chat = chatClass.getConstructor(String.class, Object[].class)
                                .newInstance("info.ezunclear", new Object[0]);
                            Class<?> iChatClass = Class.forName("net.minecraft.util.IChatComponent");
                            thePlayer.getClass()
                                .getMethod("addChatMessage", iChatClass)
                                .invoke(thePlayer, chat);
                            if (Config.DebugMode) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                                    LOGGER.info("Sent initial message to single player for delayed explosion");
                                    lastLogTime_messages = currentTime;
                                }
                            }
                        }
                    } catch (Throwable t) {
                        if (Config.DebugMode) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                                LOGGER.warn(
                                    "Failed to send client chat message for delayed explosion: " + t.getMessage());
                                lastLogTime_messages = currentTime;
                            }
                        }
                        // Reflection failed, skipping client chat message
                    }
                } else if (server != null) {
                    List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                            LOGGER.info(
                                "Sending initial message to {} players (server-side) for delayed explosion",
                                players.size());
                            lastLogTime_messages = currentTime;
                        }
                    }
                    for (EntityPlayerMP p : players) {
                        GTUtility.sendChatToPlayer(p, StatCollector.translateToLocal("info.ezunclear"));
                    }
                }
            }
        } else {
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_scheduled > LOG_INTERVAL) {
                    LOGGER.warn(
                        "Failed to schedule delayed explosion at position: ({}, {}, {})",
                        te.xCoord,
                        te.yCoord,
                        te.zCoord);
                    lastLogTime_scheduled = currentTime;
                }
            }
        }
    }

    // create the Runnable to be run on server thread by PendingMeltdown scheduler
    private Runnable createScheduledTask(TileEntity te) {
        final int sx = te.xCoord;
        final int sy = te.yCoord;
        final int sz = te.zCoord;
        final int sdim = (te.getWorldObj() != null) ? te.getWorldObj().provider.dimensionId : 0;
        if (Config.DebugMode) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastLogTime_creation > LOG_INTERVAL) {
                LOGGER.info("Creating scheduled task for explosion at: ({}, {}, {}) in dimension {}", sx, sy, sz, sdim);
                lastLogTime_creation = currentTime;
            }
        }

        return () -> {
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_creation > LOG_INTERVAL) {
                    LOGGER.info("Executing scheduled explosion task at: ({}, {}, {})", sx, sy, sz);
                    lastLogTime_creation = currentTime;
                }
            }

            // Lookup world and tile entity at execution time to avoid stale references
            net.minecraft.server.MinecraftServer srvLookup = net.minecraft.server.MinecraftServer.getServer();
            net.minecraft.world.WorldServer worldServer = null;
            if (srvLookup != null && srvLookup.worldServers != null
                && sdim >= 0
                && sdim < srvLookup.worldServers.length) {
                worldServer = srvLookup.worldServers[sdim];
            }
            TileReactorCore reactor = null;
            World world = null;
            if (worldServer != null) {
                world = worldServer;
                TileEntity fresh = worldServer.getTileEntity(sx, sy, sz);
                if (fresh instanceof TileReactorCore) reactor = (TileReactorCore) fresh;
            } else {
                // fallback to original passed-in TE if worldServer unavailable
                reactor = (te instanceof TileReactorCore) ? (TileReactorCore) te : null;
                world = te.getWorldObj();
            }

            // Send second message before explosion
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                    LOGGER.info("Sending second message before explosion");
                    lastLogTime_messages = currentTime;
                }
            }
            MinecraftServer srv = MinecraftServer.getServer();
            if (srv != null && !srv.isSinglePlayer()) {
                List<EntityPlayerMP> players = srv.getConfigurationManager().playerEntityList;
                if (Config.DebugMode) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                        LOGGER.info("Sending interaction message to {} players", players.size());
                        lastLogTime_messages = currentTime;
                    }
                }
                for (EntityPlayerMP p : players) {
                    GTUtility.sendChatToPlayer(p, StatCollector.translateToLocal("info.ezunclear.interact"));
                }
            } else if (world != null && world.isRemote) {
                try {
                    Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
                    Object mc = mcClass.getMethod("getMinecraft")
                        .invoke(null);
                    Object thePlayer = mcClass.getField("thePlayer")
                        .get(mc);
                    if (thePlayer != null) {
                        Class<?> chatClass = Class.forName("net.minecraft.util.ChatComponentTranslation");
                        Object chat = chatClass.getConstructor(String.class, Object[].class)
                            .newInstance("info.ezunclear.interact", new Object[0]);
                        Class<?> iChatClass = Class.forName("net.minecraft.util.IChatComponent");
                        thePlayer.getClass()
                            .getMethod("addChatMessage", iChatClass)
                            .invoke(thePlayer, chat);
                        if (Config.DebugMode) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                                LOGGER.info("Sent interaction message to single player");
                                lastLogTime_messages = currentTime;
                            }
                        }
                    }
                } catch (Throwable t) {
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                            LOGGER.warn("Failed to send client interaction message: " + t.getMessage());
                            lastLogTime_messages = currentTime;
                        }
                    }
                    // Reflection failed, skipping client chat message
                }
            } else if (srv != null) {
                List<EntityPlayerMP> players = srv.getConfigurationManager().playerEntityList;
                if (Config.DebugMode) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                        LOGGER.info("Sending interaction message to {} players (server-side)", players.size());
                        lastLogTime_messages = currentTime;
                    }
                }
                for (EntityPlayerMP p : players) {
                    GTUtility.sendChatToPlayer(p, StatCollector.translateToLocal("info.ezunclear.interact"));
                }
            }

            // allow re-entry and invoke goBoom
            ChunkCoordinates pos = new ChunkCoordinates(sx, sy, sz);
            com.czqwq.EZNuclear.data.PendingMeltdown.markReentry(pos);
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_reentry > LOG_INTERVAL) {
                    LOGGER.info("Marked reentry and invoking goBoom");
                    lastLogTime_reentry = currentTime;
                }
            }

            try {
                if (reactor != null) {
                    Method m = reactor.getClass()
                        .getMethod("goBoom");
                    m.invoke(reactor);
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_creation > LOG_INTERVAL) {
                            LOGGER.info("Successfully invoked goBoom on reactor");
                            lastLogTime_creation = currentTime;
                        }
                    }
                } else {
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_creation > LOG_INTERVAL) {
                            LOGGER.warn(
                                "TileReactorCore not found at ({}, {}, {}), using fallback explosion",
                                sx,
                                sy,
                                sz);
                            lastLogTime_creation = currentTime;
                        }
                    }
                    throw new IllegalStateException("TileReactorCore not found at " + pos);
                }
            } catch (Throwable t) {
                if (Config.DebugMode) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime_fallback > LOG_INTERVAL) {
                        LOGGER.error("Error invoking goBoom: " + t.getMessage(), t);
                        lastLogTime_fallback = currentTime;
                    }
                }
                // fallback: direct explosion
                try {
                    float power = getPower(reactor);
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_power > LOG_INTERVAL) {
                            LOGGER.info("Using calculated power: {}", power);
                            lastLogTime_power = currentTime;
                        }
                    }

                    // create a direct Explosion and run it on the server thread – avoids
                    // ReactorExplosion/ProcessHandler
                    try {
                        if (world != null) {
                            // Explosion expects doubles for coordinates; using block center
                            net.minecraft.world.Explosion e = new net.minecraft.world.Explosion(
                                world,
                                null,
                                (double) sx + 0.5D,
                                (double) sy + 0.5D,
                                (double) sz + 0.5D,
                                power);
                            e.doExplosionA();
                            e.doExplosionB(true);
                            world.setBlockToAir(sx, sy, sz);
                            if (Config.DebugMode) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastLogTime_fallback > LOG_INTERVAL) {
                                    LOGGER.info("Executed fallback explosion with power: {}", power);
                                    lastLogTime_fallback = currentTime;
                                }
                            }
                        } else {
                            if (Config.DebugMode) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastLogTime_fallback > LOG_INTERVAL) {
                                    LOGGER.warn("World is null, cannot execute fallback explosion");
                                    lastLogTime_fallback = currentTime;
                                }
                            }
                        }
                    } catch (Throwable ex) {
                        if (Config.DebugMode) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastLogTime_fallback > LOG_INTERVAL) {
                                LOGGER.error("Failed fallback direct explosion: " + ex.getMessage(), ex);
                                lastLogTime_fallback = currentTime;
                            }
                        }
                        // Failed fallback direct explosion
                    }
                } catch (Throwable ex) {
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_fallback > LOG_INTERVAL) {
                            LOGGER.error("Failed fallback explosion: " + ex.getMessage(), ex);
                            lastLogTime_fallback = currentTime;
                        }
                    }
                    // Failed fallback explosion
                }
            }
        };
    }

    private static float getPower(TileReactorCore reactor) {
        int totalFuel = 1000;
        try {
            java.lang.reflect.Field f1 = TileReactorCore.class.getField("reactorFuel");
            java.lang.reflect.Field f2 = TileReactorCore.class.getField("convertedFuel");
            int r = 0;
            int c = 0;
            if (reactor != null) {
                r = f1.getInt(reactor);
                c = f2.getInt(reactor);
            }
            totalFuel = r + c;
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_power > LOG_INTERVAL) {
                    LOGGER.info("Calculated total fuel: {} (reactorFuel: {}, convertedFuel: {})", totalFuel, r, c);
                    lastLogTime_power = currentTime;
                }
            }
        } catch (Throwable ignored) {
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_power > LOG_INTERVAL) {
                    LOGGER.warn("Failed to get fuel values, using default: " + ignored.getMessage());
                    lastLogTime_power = currentTime;
                }
            }
        }

        float power = 2F + ((float) totalFuel / (10368 + 1F) * 18F);
        if (Config.DebugMode) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastLogTime_power > LOG_INTERVAL) {
                LOGGER.info("Calculated explosion power: {}", power);
                lastLogTime_power = currentTime;
            }
        }
        return power;
    }
}
