package com.czqwq.EZNuclear.mixin;

import java.lang.reflect.Field;

import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.brandon3055.brandonscore.common.handlers.ProcessHandler;
import com.brandon3055.draconicevolution.common.tileentities.multiblocktiles.reactor.ReactorExplosion;
import com.czqwq.EZNuclear.Config;
import com.czqwq.EZNuclear.EZNuclear;
import com.czqwq.EZNuclear.data.PendingMeltdown;

@SuppressWarnings("UnusedMixin")
@Mixin(value = ReactorExplosion.class, remap = false)

public abstract class ReactorExplosionMixin {

    // private static final Logger LOGGER = LogManager.getLogger("EZNuclear.ReactorExplosionMixin");

    // Try both common names: some builds use "run", others use "onRun" (or obfuscated names).
    // Using an array makes the injector tolerant to either name at runtime.
    @Inject(method = { "run", "onRun" }, at = @At("HEAD"), cancellable = true, remap = false)
    private void onRun(CallbackInfo ci) {
        // Check if DE explosions are disabled in config
        if (!Config.DEExplosion) {
            ci.cancel();
            return;
        }

        try {
            // LOGGER.info(
            // "ReactorExplosion.run intercepted for instance: {}",
            // this.getClass()
            // .getName());
            // try to read x, y, z and world from fields
            Class<?> cls = this.getClass();
            Field xField = cls.getDeclaredField("x");
            Field yField = cls.getDeclaredField("y");
            Field zField = cls.getDeclaredField("z");
            Field worldField = cls.getDeclaredField("world");
            xField.setAccessible(true);
            yField.setAccessible(true);
            zField.setAccessible(true);
            worldField.setAccessible(true);
            int x = xField.getInt(this);
            int y = yField.getInt(this);
            int z = zField.getInt(this);
            World world = (World) worldField.get(this);

            ChunkCoordinates pos = new ChunkCoordinates(x, y, z);
            // If PendingMeltdown already scheduled reentry, allow run to proceed
            if (PendingMeltdown.consumeReentry(pos)) {
                // LOGGER.info("ReactorExplosionMixin: reentry present for {}. allowing run", pos);
                return; // allow original run
            }

            // otherwise cancel and schedule run via PendingMeltdown to run later on server thread
            ci.cancel();
            // try to read a float power field from this instance (common names)
            float power = 0F;
            String[] candidates = new String[] { "power", "explosionSize", "strength", "explosionPower", "size" };
            for (String name : candidates) {
                try {
                    Field pf = cls.getDeclaredField(name);
                    pf.setAccessible(true);
                    Object val = pf.get(this);
                    if (val instanceof Float) {
                        power = (Float) val;
                        break;
                    } else if (val instanceof Double) {
                        power = ((Double) val).floatValue();
                        break;
                    } else if (val instanceof Integer) {
                        power = ((Integer) val).floatValue();
                        break;
                    }
                } catch (NoSuchFieldException ignore) {}
            }
            final float fpower = power > 0F ? power : 2500F;

            PendingMeltdown.schedule(pos, () -> {
                try {
                    // allow one re-entry then create a new ReactorExplosion and schedule it via ProcessHandler
                    PendingMeltdown.markReentry(pos);
                    try {
                        // find constructor ReactorExplosion(World,int,int,int,float)
                        Class<?> reClass = ReactorExplosion.class;
                        java.lang.reflect.Constructor<?> ctor = null;
                        Object newExp = null;
                        try {
                            ctor = reClass.getConstructor(World.class, int.class, int.class, int.class, float.class);
                            newExp = ctor.newInstance(world, x, y, z, fpower);
                        } catch (NoSuchMethodException nsme) {
                            try {
                                ctor = reClass
                                    .getConstructor(World.class, int.class, int.class, int.class, double.class);
                                newExp = ctor.newInstance(world, x, y, z, (double) fpower);
                            } catch (NoSuchMethodException nsme2) {
                                EZNuclear.LOG
                                    .error("No suitable ReactorExplosion constructor found to recreate explosion");
                            }
                        }

                        if (newExp != null) {
                            // Use reflection to call ProcessHandler.addProcess(IProcess)
                            try {
                                Class<?> iProcessClass = Class
                                    .forName("com.brandon3055.brandonscore.common.handlers.IProcess");
                                java.lang.reflect.Method addMethod = ProcessHandler.class
                                    .getMethod("addProcess", iProcessClass);
                                addMethod.invoke(null, newExp);
                                // LOGGER.info("ReactorExplosionMixin: scheduled re-added ReactorExplosion at {}", pos);
                            } catch (Throwable t) {
                                // LOGGER
                                // .warn("Failed to schedule ReactorExplosion via ProcessHandler: {}", t.getMessage());
                            }
                        }
                    } catch (Throwable t) {
                        // LOGGER.warn("Error creating ReactorExplosion instance: {}", t.getMessage());
                    }
                } catch (Throwable t) {
                    // LOGGER.warn("Scheduled ReactorExplosion task failed: {}", t.getMessage());
                }
            }, 0L);

        } catch (NoSuchFieldException nsfe) {
            // if fields not found, don't intercept
        } catch (Throwable t) {
            // LOGGER.warn("ReactorExplosionMixin interception failed: {}", t.getMessage());
        }
    }
}
