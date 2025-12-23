package com.czqwq.EZNuclear;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static boolean IC2Explosion = true;
    public static boolean DEExplosion = true;
    public static boolean requireCommandToExplode = false;
    public static int explosionDelaySeconds = 5;
    public static double IC2ExplosionPower = 100.0;
    public static double DEExplosionPower = 100.0;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);
        IC2Explosion = configuration.getBoolean(
            "IC2Explosion",
            Configuration.CATEGORY_GENERAL,
            IC2Explosion,
            "Allow IC2 nuclear explosions\nAttention: IC2 NuclearReactor Will Remove after after prevent explosion");
        DEExplosion = configuration.getBoolean(
            "DEExplosion",
            Configuration.CATEGORY_GENERAL,
            DEExplosion,
            "Allow Draconic Evolution nuclear explosions");
        requireCommandToExplode = configuration.getBoolean(
            "requireCommandToExplode",
            Configuration.CATEGORY_GENERAL,
            requireCommandToExplode,
            "Require player to send '坏了坏了' command to trigger explosion. When enabled, only sends info.ezunclear message and waits for manual trigger");
        explosionDelaySeconds = configuration.getInt(
            "explosionDelaySeconds",
            Configuration.CATEGORY_GENERAL,
            explosionDelaySeconds,
            1,
            300,
            "Delay in seconds before explosion occurs (default: 5 seconds)");
        IC2ExplosionPower = configuration.getFloat(
            "IC2ExplosionPower",
            Configuration.CATEGORY_GENERAL,
            (float) IC2ExplosionPower,
            0.0f,
            Float.MAX_VALUE,
            "Fixed power value for IC2 nuclear explosions when requireCommandToExplode is enabled (default: 100.0)");
        DEExplosionPower = configuration.getFloat(
            "DEExplosionPower",
            Configuration.CATEGORY_GENERAL,
            (float) DEExplosionPower,
            0.0f,
            Float.MAX_VALUE,
            "Fixed power value for Draconic Evolution reactor explosions when requireCommandToExplode is enabled (default: 100.0)");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
