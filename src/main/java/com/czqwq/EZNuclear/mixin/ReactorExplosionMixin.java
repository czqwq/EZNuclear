package com.czqwq.EZNuclear.mixin;

import java.lang.reflect.Field;

import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.brandon3055.brandonscore.common.handlers.ProcessHandler;
import com.brandon3055.draconicevolution.common.tileentities.multiblocktiles.reactor.ReactorExplosion;
import com.czqwq.EZNuclear.Config;
import com.czqwq.EZNuclear.EZNuclear;
import com.czqwq.EZNuclear.data.PendingMeltdown;

@Mixin(value = ReactorExplosion.class, remap = false)
public class ReactorExplosionMixin {

    private static final Logger LOGGER = LogManager.getLogger("EZNuclear.ReactorExplosionMixin");
    private static long lastLogTime_run = 0;
    private static long lastLogTime_disabled = 0;
    private static long lastLogTime_intercepted = 0;
    private static long lastLogTime_coords = 0;
    private static long lastLogTime_reentry = 0;
    private static long lastLogTime_scheduled = 0;
    private static long lastLogTime_execution = 0;
    private static long lastLogTime_power = 0;
    private static long lastLogTime_creation = 0;
    private static long lastLogTime_messages = 0;
    private static long lastLogTime_errors = 0;
    private static final long LOG_INTERVAL = 5000; // 5 seconds

    // Try both common names: some builds use "run", others use "onRun" (or obfuscated names).
    // Using an array makes the injector tolerant to either name at runtime.
    @Inject(method = "run", at = @At("HEAD"), cancellable = true, remap = false)
    private void onRun(CallbackInfo ci) {
        LOGGER.info("ReactorExplosionMixin.onRun called");

        if (!Config.IC2Explosion) {
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_disabled > LOG_INTERVAL) {
                    LOGGER.info("DE explosions disabled in config, cancelling ReactorExplosion");
                    lastLogTime_disabled = currentTime;
                }
            }
            ci.cancel();
            return;
        }

        try {
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_intercepted > LOG_INTERVAL) {
                    LOGGER.info(
                        "ReactorExplosion.run intercepted for instance: {}",
                        this.getClass()
                            .getName());
                    lastLogTime_intercepted = currentTime;
                }
            }

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
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_coords > LOG_INTERVAL) {
                    LOGGER.info("ReactorExplosion coordinates: ({}, {}, {})", x, y, z);
                    lastLogTime_coords = currentTime;
                }
            }

            ChunkCoordinates pos = new ChunkCoordinates(x, y, z);
            // If PendingMeltdown already scheduled reentry, allow run to proceed
            if (PendingMeltdown.consumeReentry(pos)) {
                if (Config.DebugMode) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime_reentry > LOG_INTERVAL) {
                        LOGGER.info("Reentry present for ({}, {}, {}), allowing ReactorExplosion run", x, y, z);
                        lastLogTime_reentry = currentTime;
                    }
                }
                return; // allow original run
            }

            // otherwise cancel and schedule run via PendingMeltdown to run later on server thread
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_scheduled > LOG_INTERVAL) {
                    LOGGER.info("Intercepting ReactorExplosion, scheduling delayed execution");
                    lastLogTime_scheduled = currentTime;
                }
            }
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
                        if (Config.DebugMode) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastLogTime_power > LOG_INTERVAL) {
                                LOGGER.info("Found power field '{}': {}", name, power);
                                lastLogTime_power = currentTime;
                            }
                        }
                        break;
                    } else if (val instanceof Double) {
                        power = ((Double) val).floatValue();
                        if (Config.DebugMode) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastLogTime_power > LOG_INTERVAL) {
                                LOGGER.info("Found power field '{}' (double): {}", name, power);
                                lastLogTime_power = currentTime;
                            }
                        }
                        break;
                    } else if (val instanceof Integer) {
                        power = ((Integer) val).floatValue();
                        if (Config.DebugMode) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastLogTime_power > LOG_INTERVAL) {
                                LOGGER.info("Found power field '{}' (integer): {}", name, power);
                                lastLogTime_power = currentTime;
                            }
                        }
                        break;
                    }
                } catch (NoSuchFieldException ignore) {}
            }
            final float fpower = power > 0F ? power : 10F;
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_power > LOG_INTERVAL) {
                    LOGGER.info("Using explosion power: {}", fpower);
                    lastLogTime_power = currentTime;
                }
            }

            PendingMeltdown.schedule(pos, () -> {
                try {
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_execution > LOG_INTERVAL) {
                            LOGGER.info("Executing scheduled ReactorExplosion task at ({}, {}, {})", x, y, z);
                            lastLogTime_execution = currentTime;
                        }
                    }

                    // Send initial message to players
                    net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
                    if (server != null && !server.isSinglePlayer()) {
                        java.util.List<net.minecraft.entity.player.EntityPlayerMP> players = server
                            .getConfigurationManager().playerEntityList;
                        if (Config.DebugMode) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                                LOGGER.info("Sending initial message to {} players", players.size());
                                lastLogTime_messages = currentTime;
                            }
                        }
                        for (net.minecraft.entity.player.EntityPlayerMP p : players) {
                            gregtech.api.util.GTUtility.sendChatToPlayer(
                                p,
                                net.minecraft.util.StatCollector.translateToLocal("info.ezunclear"));
                        }
                    }

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
                            if (Config.DebugMode) {
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastLogTime_creation > LOG_INTERVAL) {
                                    LOGGER.info("Created ReactorExplosion with power: {}", fpower);
                                    lastLogTime_creation = currentTime;
                                }
                            }
                        } catch (NoSuchMethodException nsme) {
                            try {
                                ctor = reClass
                                    .getConstructor(World.class, int.class, int.class, int.class, double.class);
                                newExp = ctor.newInstance(world, x, y, z, (double) fpower);
                                if (Config.DebugMode) {
                                    long currentTime = System.currentTimeMillis();
                                    if (currentTime - lastLogTime_creation > LOG_INTERVAL) {
                                        LOGGER.info("Created ReactorExplosion with power (double): {}", fpower);
                                        lastLogTime_creation = currentTime;
                                    }
                                }
                            } catch (NoSuchMethodException nsme2) {
                                if (Config.DebugMode) {
                                    long currentTime = System.currentTimeMillis();
                                    if (currentTime - lastLogTime_errors > LOG_INTERVAL) {
                                        LOGGER.error(
                                            "No suitable ReactorExplosion constructor found to recreate explosion");
                                        lastLogTime_errors = currentTime;
                                    }
                                }
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
                                if (Config.DebugMode) {
                                    long currentTime = System.currentTimeMillis();
                                    if (currentTime - lastLogTime_creation > LOG_INTERVAL) {
                                        LOGGER.info("Scheduled re-added ReactorExplosion at ({}, {}, {})", x, y, z);
                                        lastLogTime_creation = currentTime;
                                    }
                                }
                            } catch (Throwable t) {
                                if (Config.DebugMode) {
                                    long currentTime = System.currentTimeMillis();
                                    if (currentTime - lastLogTime_errors > LOG_INTERVAL) {
                                        LOGGER.error(
                                            "Failed to schedule ReactorExplosion via ProcessHandler: " + t.getMessage(),
                                            t);
                                        lastLogTime_errors = currentTime;
                                    }
                                }
                                EZNuclear.LOG
                                    .error("Failed to schedule ReactorExplosion via ProcessHandler: " + t.getMessage());
                            }
                        }
                    } catch (Throwable t) {
                        if (Config.DebugMode) {
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastLogTime_errors > LOG_INTERVAL) {
                                LOGGER.error("Error creating ReactorExplosion instance: " + t.getMessage(), t);
                                lastLogTime_errors = currentTime;
                            }
                        }
                        EZNuclear.LOG.error("Error creating ReactorExplosion instance: " + t.getMessage());
                    }
                } catch (Throwable t) {
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_errors > LOG_INTERVAL) {
                            LOGGER.error("Scheduled ReactorExplosion task failed: " + t.getMessage(), t);
                            lastLogTime_errors = currentTime;
                        }
                    }
                    EZNuclear.LOG.error("Scheduled ReactorExplosion task failed: " + t.getMessage());
                }

                // Send second message after delay
                if (Config.DebugMode) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                        LOGGER.info("Sending interaction message after delay");
                        lastLogTime_messages = currentTime;
                    }
                }
                net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
                if (server != null && !server.isSinglePlayer()) {
                    java.util.List<net.minecraft.entity.player.EntityPlayerMP> players = server
                        .getConfigurationManager().playerEntityList;
                    if (Config.DebugMode) {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - lastLogTime_messages > LOG_INTERVAL) {
                            LOGGER.info("Sending interaction message to {} players", players.size());
                            lastLogTime_messages = currentTime;
                        }
                    }
                    for (net.minecraft.entity.player.EntityPlayerMP p : players) {
                        gregtech.api.util.GTUtility.sendChatToPlayer(
                            p,
                            net.minecraft.util.StatCollector.translateToLocal("info.ezunclear.interact"));
                    }
                }
            }, 5000L);

        } catch (NoSuchFieldException nsfe) {
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_errors > LOG_INTERVAL) {
                    LOGGER.warn(
                        "Cannot find required fields in ReactorExplosion, not intercepting: " + nsfe.getMessage());
                    lastLogTime_errors = currentTime;
                }
            }
        } catch (Throwable t) {
            if (Config.DebugMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogTime_errors > LOG_INTERVAL) {
                    LOGGER.error("ReactorExplosionMixin interception failed: " + t.getMessage(), t);
                    lastLogTime_errors = currentTime;
                }
            }
            EZNuclear.LOG.error("ReactorExplosionMixin interception failed: " + t.getMessage());
        }
    }
}
