package com.czqwq.EZNuclear.mixin;

import java.lang.reflect.Field;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.brandon3055.brandonscore.common.handlers.IProcess;
import com.brandon3055.brandonscore.common.handlers.ProcessHandler;
import com.czqwq.EZNuclear.Config;
import com.czqwq.EZNuclear.EZNuclear;
import com.czqwq.EZNuclear.data.PendingMeltdown;
import com.czqwq.EZNuclear.util.MessageUtils;

@SuppressWarnings("UnusedMixin")
@Mixin(value = ProcessHandler.class, remap = false)
public class ProcessHandlerMixin {

    // private static final Logger LOGGER = LogManager.getLogger("EZNuclear.ProcessHandlerMixin");

    @Inject(method = "addProcess", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onAddProcess(IProcess process, CallbackInfo ci) {

        if (process == null) return;
        try {
            // LOGGER.info(
            // "ProcessHandler.addProcess called for instance: {}",
            // process.getClass()
            // .getName());
            Class<?> cls = process.getClass();
            String name = cls.getName();
            if (!name.endsWith("ReactorExplosion")) return;

            // Check if DE explosions are disabled in config
            if (!Config.DEExplosion) {
                ci.cancel();
                return;
            }

            // try to get x,y,z
            Field xf = cls.getDeclaredField("x");
            Field yf = cls.getDeclaredField("y");
            Field zf = cls.getDeclaredField("z");
            xf.setAccessible(true);
            yf.setAccessible(true);
            zf.setAccessible(true);
            int x = xf.getInt(process);
            int y = yf.getInt(process);
            int z = zf.getInt(process);

            // try to get world/dimension if available
            int dim = 0;
            Object worldObj = null;
            try {
                Field worldField = cls.getDeclaredField("world");
                worldField.setAccessible(true);
                worldObj = worldField.get(process);
                if (worldObj instanceof net.minecraft.world.World) {
                    dim = ((net.minecraft.world.World) worldObj).provider.dimensionId;
                }
            } catch (Throwable ignored) {}

            // If reentry already set for this pos+dim, allow original addProcess
            net.minecraft.util.ChunkCoordinates pos = new net.minecraft.util.ChunkCoordinates(x, y, z);
            if (PendingMeltdown.consumeReentry(pos, dim)) {
                // LOGGER.info("ProcessHandlerMixin: reentry present for {} ({}). allowing addProcess", pos, name);
                return; // allow addProcess to continue
            }

            // Otherwise cancel and schedule via PendingMeltdown (delay configurable)
            // LOGGER.info(
            // "ProcessHandlerMixin: intercepting ReactorExplosion addProcess at {}. scheduling delayed execution.",
            // pos);
            ci.cancel();
            final int fdim = dim;
            final Object fworld = worldObj;
            final int fx = x;
            final int fy = y;
            final int fz = z;
            final Class<?> fcls = cls;
            final net.minecraft.util.ChunkCoordinates fpos = pos;

            // Check if manual trigger is required
            if (Config.requireCommandToExplode) {
                // Mark this position for manual trigger
                PendingMeltdown.markManualTrigger(pos, dim);
            }

            PendingMeltdown.schedule(fpos, () -> {
                try {
                    // Check if manual trigger is required
                    if (Config.requireCommandToExplode && PendingMeltdown.isManualTrigger(fpos, fdim)) {
                        // If manual trigger is required and this position is marked for manual trigger,
                        // send the prevent explosion message and return without exploding
                        net.minecraft.server.MinecraftServer srv = net.minecraft.server.MinecraftServer.getServer();
                        if (srv != null) {
                            if (!srv.isSinglePlayer()) {
                                java.util.List<net.minecraft.entity.player.EntityPlayerMP> players = srv
                                    .getConfigurationManager().playerEntityList;
                                for (net.minecraft.entity.player.EntityPlayerMP p : players) {
                                    gregtech.api.util.GTUtility.sendChatToPlayer(
                                        p,
                                        net.minecraft.util.StatCollector
                                            .translateToLocal("info.ezunclear.preventexplosion"));
                                }
                            } else {
                                MessageUtils.sendToSinglePlayer("info.ezunclear.preventexplosion");
                            }
                        }
                        return;
                    }

                    PendingMeltdown.markReentry(pos);
                    // recreate a ReactorExplosion via reflection and call original addProcess
                    Object newExp = null;
                    try {
                        Class<?> reClass = fcls;
                        java.lang.reflect.Constructor<?> ctor = reClass.getConstructor(
                            net.minecraft.world.World.class,
                            int.class,
                            int.class,
                            int.class,
                            float.class);
                        Object world = fworld;
                        newExp = ctor.newInstance(world, fx, fy, fz, 10F);
                    } catch (NoSuchMethodException nsme) {
                        try {
                            Class<?> reClass = fcls;
                            java.lang.reflect.Constructor<?> ctor = reClass.getConstructor(
                                net.minecraft.world.World.class,
                                int.class,
                                int.class,
                                int.class,
                                double.class);
                            Object world = fworld;
                            newExp = ctor.newInstance(world, fx, fy, fz, (double) 10F);
                        } catch (Throwable t) {
                            EZNuclear.LOG.error("Failed to recreate ReactorExplosion");
                        }
                    }
                    if (newExp != null) {
                        try {
                            Class<?> iProcessClass = Class
                                .forName("com.brandon3055.brandonscore.common.handlers.IProcess");
                            java.lang.reflect.Method addMethod = Class
                                .forName("com.brandon3055.brandonscore.common.handlers.ProcessHandler")
                                .getMethod("addProcess", iProcessClass);
                            addMethod.invoke(null, newExp);
                            // LOGGER.info("ProcessHandlerMixin: re-added ReactorExplosion for {}", pos);
                        } catch (Throwable t) {
                            // LOGGER.warn("Failed to re-add ReactorExplosion: {}", t.getMessage());
                        }
                    }
                } catch (Throwable t) {
                    // LOGGER.warn("Scheduled addProcess task failed: {}", t.getMessage());
                }
            }, Config.explosionDelaySeconds * 1000L);

        } catch (NoSuchFieldException nsfe) {
            // can't find fields, don't intercept
        } catch (Throwable t) {
            // LOGGER.warn("ProcessHandlerMixin interception error: {}", t.getMessage());
        }
    }
}
