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
import com.czqwq.EZNuclear.Config;
import com.czqwq.EZNuclear.EZNuclear;
import com.czqwq.EZNuclear.data.PendingMeltdown;

@Mixin(value = ProcessHandler.class, remap = false)
public class ProcessHandlerMixin {

    private static final Logger LOGGER = LogManager.getLogger("EZNuclear.ProcessHandlerMixin");
    private static long lastLogTime_addProcess = 0;
    private static long lastLogTime_notReactor = 0;
    private static long lastLogTime_disabled = 0;
    private static long lastLogTime_coords = 0;
    private static long lastLogTime_dimension = 0;
    private static long lastLogTime_power = 0;
    private static long lastLogTime_reentry = 0;
    private static long lastLogTime_intercept = 0;
    private static long lastLogTime_execution = 0;
    private static long lastLogTime_creation = 0;
    private static long lastLogTime_errors = 0;
    private static final long LOG_INTERVAL = 5000; // 5 seconds

    @Inject(method = "addProcess", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onAddProcess(IProcess process, CallbackInfo ci) {
        LOGGER.info("ProcessHandlerMixin.onAddProcess called");

        if (process == null) {
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_addProcess > LOG_INTERVAL) {
                    LOGGER.info("ProcessHandler.addProcess called with null process, ignoring");
                    lastLogTime_addProcess = currentTime;
                }
            }
            return;
        }

        try {
            Class<?> cls = process.getClass();
            String name = cls.getName();
            LOGGER.info("Processing process of type: " + name);

            // Check if this is a ReactorExplosion or ReactorExplosionTrace
            if (!name.endsWith("ReactorExplosion") && !name.endsWith("ReactorExplosionTrace")) {
                if (Config.DebugMode) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime_notReactor > LOG_INTERVAL) {
                        LOGGER.info(
                            "Process is not ReactorExplosion or ReactorExplosionTrace, allowing normal processing: {}",
                            name);
                        lastLogTime_notReactor = currentTime;
                    }
                }
                return;
            }

            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_addProcess > LOG_INTERVAL) {
                    LOGGER.info("ProcessHandler.addProcess called for ReactorExplosion instance: {}", name);
                    lastLogTime_addProcess = currentTime;
                }
            }

            // Check if DE explosions are disabled in config
            if (!Config.DEExplosion) {
                if (Config.DebugMode) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime_disabled > LOG_INTERVAL) {
                        LOGGER.info("DE explosions disabled in config, cancelling ReactorExplosion process");
                        lastLogTime_disabled = currentTime;
                    }
                }
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
            LOGGER.info("ReactorExplosion coordinates: ({}, {}, {})", x, y, z);

            // try to get world/dimension if available
            int dim = 0;
            Object worldObj = null;
            try {
                Field worldField = cls.getDeclaredField("world");
                worldField.setAccessible(true);
                worldObj = worldField.get(process);
                if (worldObj instanceof net.minecraft.world.World) {
                    dim = ((net.minecraft.world.World) worldObj).provider.dimensionId;
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_dimension > LOG_INTERVAL) {
                            LOGGER.info("ReactorExplosion dimension: {}", dim);
                            lastLogTime_dimension = currentTime;
                        }
                    }
                }
            } catch (Throwable ignored) {
                if (Config.DebugMode) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime_dimension > LOG_INTERVAL) {
                        LOGGER.warn("Could not get world/dimension from ReactorExplosion");
                        lastLogTime_dimension = currentTime;
                    }
                }
            }

            // try to get power field if available
            float power = 10F;
            try {
                String[] powerFieldNames = new String[] { "power", "explosionSize", "strength", "explosionPower",
                    "size" };
                for (String fieldName : powerFieldNames) {
                    try {
                        Field powerField = cls.getDeclaredField(fieldName);
                        powerField.setAccessible(true);
                        Object val = powerField.get(process);
                        if (val instanceof Float) {
                            power = (Float) val;
                            if (Config.DebugMode) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastLogTime_power > LOG_INTERVAL) {
                                    LOGGER.info("Found power field '{}': {}", fieldName, power);
                                    lastLogTime_power = currentTime;
                                }
                            }
                            break;
                        } else if (val instanceof Double) {
                            power = ((Double) val).floatValue();
                            if (Config.DebugMode) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastLogTime_power > LOG_INTERVAL) {
                                    LOGGER.info("Found power field '{}' (double): {}", fieldName, power);
                                    lastLogTime_power = currentTime;
                                }
                            }
                            break;
                        } else if (val instanceof Integer) {
                            power = ((Integer) val).floatValue();
                            if (Config.DebugMode) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastLogTime_power > LOG_INTERVAL) {
                                    LOGGER.info("Found power field '{}' (integer): {}", fieldName, power);
                                    lastLogTime_power = currentTime;
                                }
                            }
                            break;
                        }
                    } catch (NoSuchFieldException ignored) {}
                }
            } catch (Throwable ignored) {
                if (Config.DebugMode) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime_power > LOG_INTERVAL) {
                        LOGGER.warn("Could not get power field from ReactorExplosion, using default: {}", power);
                        lastLogTime_power = currentTime;
                    }
                }
            }

            // If reentry already set for this pos+dim, allow original addProcess
            net.minecraft.util.ChunkCoordinates pos = new net.minecraft.util.ChunkCoordinates(x, y, z);
            if (PendingMeltdown.consumeReentry(pos, dim)) {
                if (Config.DebugMode) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime_reentry > LOG_INTERVAL) {
                        LOGGER.info("Reentry present for ({}, {}, {}), allowing addProcess", x, y, z);
                        lastLogTime_reentry = currentTime;
                    }
                }
                return; // allow addProcess to continue
            }

            // Otherwise cancel and schedule via PendingMeltdown (delay 5s)
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_intercept > LOG_INTERVAL) {
                    LOGGER.info(
                        "Intercepting ReactorExplosion addProcess at ({}, {}, {}). Scheduling delayed execution with power: {}",
                        x,
                        y,
                        z,
                        power);
                    lastLogTime_intercept = currentTime;
                }
            }
            ci.cancel();
            final int fdim = dim;
            final Object fworld = worldObj;
            final int fx = x;
            final int fy = y;
            final int fz = z;
            final Class<?> fcls = cls;
            final net.minecraft.util.ChunkCoordinates fpos = pos;
            final float fpower = power;
            PendingMeltdown.schedule(fpos, () -> {
                try {
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_execution > LOG_INTERVAL) {
                            LOGGER.info("Executing scheduled ReactorExplosion task at ({}, {}, {})", fx, fy, fz);
                            lastLogTime_execution = currentTime;
                        }
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
                        newExp = ctor.newInstance(world, fx, fy, fz, fpower);
                        if (Config.DebugMode) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastLogTime_creation > LOG_INTERVAL) {
                                LOGGER.info("Created ReactorExplosion with power: {}", fpower);
                                lastLogTime_creation = currentTime;
                            }
                        }
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
                            newExp = ctor.newInstance(world, fx, fy, fz, (double) fpower);
                            if (Config.DebugMode) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastLogTime_creation > LOG_INTERVAL) {
                                    LOGGER.info("Created ReactorExplosion with power (double): {}", fpower);
                                    lastLogTime_creation = currentTime;
                                }
                            }
                        } catch (Throwable t) {
                            if (Config.DebugMode) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastLogTime_errors > LOG_INTERVAL) {
                                    LOGGER.error("Failed to recreate ReactorExplosion: " + t.getMessage(), t);
                                    lastLogTime_errors = currentTime;
                                }
                            }
                            EZNuclear.LOG.error("Failed to recreate ReactorExplosion: " + t.getMessage());
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
                            if (Config.DebugMode) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastLogTime_creation > LOG_INTERVAL) {
                                    LOGGER.info("Re-added ReactorExplosion for ({}, {}, {})", fx, fy, fz);
                                    lastLogTime_creation = currentTime;
                                }
                            }
                        } catch (Throwable t) {
                            if (Config.DebugMode) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastLogTime_errors > LOG_INTERVAL) {
                                    LOGGER.error("Failed to re-add ReactorExplosion: " + t.getMessage(), t);
                                    lastLogTime_errors = currentTime;
                                }
                            }
                            EZNuclear.LOG.error("Failed to re-add ReactorExplosion: " + t.getMessage());
                        }
                    }
                } catch (Throwable t) {
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_errors > LOG_INTERVAL) {
                            LOGGER.error("Scheduled addProcess task failed: " + t.getMessage(), t);
                            lastLogTime_errors = currentTime;
                        }
                    }
                    EZNuclear.LOG.error("Scheduled addProcess task failed: " + t.getMessage());
                }
            }, 5000L);

        } catch (NoSuchFieldException nsfe) {
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_errors > LOG_INTERVAL) {
                    LOGGER.warn("Cannot find x/y/z fields in ReactorExplosion, not intercepting: " + nsfe.getMessage());
                    lastLogTime_errors = currentTime;
                }
            }
        } catch (Throwable t) {
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_errors > LOG_INTERVAL) {
                    LOGGER.error("ProcessHandlerMixin interception error: " + t.getMessage(), t);
                    lastLogTime_errors = currentTime;
                }
            }
            EZNuclear.LOG.error("ProcessHandlerMixin interception error: " + t.getMessage());
        }
    }
}
