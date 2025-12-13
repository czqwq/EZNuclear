package com.czqwq.EZNuclear.mixin;

import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.brandon3055.draconicevolution.common.tileentities.multiblocktiles.reactor.TileReactorCore;
import com.czqwq.EZNuclear.Config;
import com.czqwq.EZNuclear.data.PendingMeltdown;

import gregtech.api.util.GTUtility;

@SuppressWarnings("UnusedMixin")
@Mixin(value = TileReactorCore.class, remap = false)
public abstract class TileReactorCoreMixin {

    // reentry is managed by PendingMeltdown to keep a single source of truth

    @Inject(method = "goBoom", remap = false, at = @At("HEAD"), cancellable = true)
    private void onGoBoom(CallbackInfo ci) {
        // Check if DE explosions are disabled in config
        if (!Config.DEExplosion) {
            // Even if explosion is disabled, still send the message to players
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                if (!server.isSinglePlayer()) {
                    List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
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
                        }
                    } catch (Throwable t) {
                        // Reflection failed, skipping client chat message
                    }
                }

                // Schedule the second message after 5 seconds
                PendingMeltdown.schedule(new ChunkCoordinates(0, 0, 0), () -> {
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

        if (com.czqwq.EZNuclear.data.PendingMeltdown.consumeReentry(pos)) {
            return; // allow original goBoom
        }

        if (PendingMeltdown.schedule(pos, createScheduledTask(te), 5000L)) {
            ci.cancel();
            PendingMeltdown.schedule(pos, () -> {}, 0L); // touch pos to log scheduling if needed

            MinecraftServer server = MinecraftServer.getServer();
            if (server != null && !server.isSinglePlayer()) {
                List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
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
                        }
                    } catch (Throwable t) {
                        // Reflection failed, skipping client chat message
                    }
                } else if (server != null) {
                    List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
                    for (EntityPlayerMP p : players) {
                        GTUtility.sendChatToPlayer(p, StatCollector.translateToLocal("info.ezunclear"));
                    }
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
        return () -> {
            // Lookup world and tile entity at execution time to avoid stale references
            net.minecraft.server.MinecraftServer srvLookup = net.minecraft.server.MinecraftServer.getServer();
            net.minecraft.world.WorldServer worldServer = null;
            if (srvLookup != null && srvLookup.worldServers != null
                && sdim >= 0
                && sdim < srvLookup.worldServers.length) {
                worldServer = srvLookup.worldServers[sdim];
            }
            TileReactorCore reactor = null;
            World world;
            if (worldServer != null) {
                world = worldServer;
                TileEntity fresh = worldServer.getTileEntity(sx, sy, sz);
                if (fresh instanceof TileReactorCore) reactor = (TileReactorCore) fresh;
            } else {
                // fallback to original passed-in TE if worldServer unavailable
                reactor = (te instanceof TileReactorCore) ? (TileReactorCore) te : null;
                world = te.getWorldObj();
            }

            // send second message
            MinecraftServer srv = MinecraftServer.getServer();
            if (srv != null && !srv.isSinglePlayer()) {
                List<EntityPlayerMP> players = srv.getConfigurationManager().playerEntityList;
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
                    }
                } catch (Throwable t) {
                    // Reflection failed, skipping client chat message
                }
            } else if (srv != null) {
                List<EntityPlayerMP> players = srv.getConfigurationManager().playerEntityList;
                for (EntityPlayerMP p : players) {
                    GTUtility.sendChatToPlayer(p, StatCollector.translateToLocal("info.ezunclear.interact"));
                }
            }

            // allow re-entry and invoke goBoom
            ChunkCoordinates pos = new ChunkCoordinates(sx, sy, sz);
            com.czqwq.EZNuclear.data.PendingMeltdown.markReentry(pos);
            try {
                if (reactor != null) {
                    Method m = reactor.getClass()
                        .getMethod("goBoom");
                    m.invoke(reactor);
                } else {
                    throw new IllegalStateException("TileReactorCore not found at " + pos);
                }
            } catch (Throwable t) {
                // fallback: direct explosion
                try {
                    float power = getPower(reactor);
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
                        }
                    } catch (Throwable ex) {
                        // Failed fallback direct explosion
                    }
                } catch (Throwable ex) {
                    // Failed fallback explosion
                }
            }
        };
    }

    private static float getPower(TileReactorCore reactor) {
        int totalFuel = 10000;
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
        } catch (Throwable ignored) {}

        return 2F + ((float) totalFuel / (10368 + 1F) * 18F);
    }
}
