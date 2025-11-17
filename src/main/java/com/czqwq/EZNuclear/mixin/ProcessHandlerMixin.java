package com.czqwq.EZNuclear.mixin;

import java.lang.reflect.Field;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.brandon3055.brandonscore.common.handlers.IProcess;
import com.brandon3055.brandonscore.common.handlers.ProcessHandler;
import com.czqwq.EZNuclear.data.PendingMeltdown;

@Mixin(ProcessHandler.class)
public class ProcessHandlerMixin {

    private static final Logger LOGGER = LogManager.getLogger("EZNuclear.ProcessHandlerMixin");

    @Inject(method = "addProcess", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onAddProcess(IProcess process, CallbackInfo ci) {
        if (process == null) return;
        try {
            LOGGER.info(
                "ProcessHandler.addProcess called for instance: {}",
                process.getClass()
                    .getName());
            Class<?> cls = process.getClass();
            String name = cls.getName();
            if (!name.endsWith("ReactorExplosion")) return;

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
                LOGGER.info("ProcessHandlerMixin: reentry present for {} ({}). allowing addProcess", pos, name);
                return; // allow addProcess to continue
            }

            // Otherwise cancel and schedule via PendingMeltdown (delay 5s)
            LOGGER.info(
                "ProcessHandlerMixin: intercepting ReactorExplosion addProcess at {}. scheduling delayed execution.",
                pos);
            ci.cancel();
            final int fdim = dim;
            final Object fworld = worldObj;
            final int fx = x;
            final int fy = y;
            final int fz = z;
            final Class<?> fcls = cls;
            final net.minecraft.util.ChunkCoordinates fpos = pos;
            PendingMeltdown.schedule(fpos, () -> {
                try {
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
                            LOGGER.warn("Failed to recreate ReactorExplosion: {}", t.getMessage());
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
                            LOGGER.info("ProcessHandlerMixin: re-added ReactorExplosion for {}", pos);
                        } catch (Throwable t) {
                            LOGGER.warn("Failed to re-add ReactorExplosion: {}", t.getMessage());
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.warn("Scheduled addProcess task failed: {}", t.getMessage());
                }
            }, 5000L);

        } catch (NoSuchFieldException nsfe) {
            // can't find fields, don't intercept
        } catch (Throwable t) {
            LOGGER.warn("ProcessHandlerMixin interception error: {}", t.getMessage());
        }
    }
}
