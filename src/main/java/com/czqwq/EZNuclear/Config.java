package com.czqwq.EZNuclear;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static boolean IC2Explosion = true;
    public static boolean DEExplosion = true;
    public static boolean DebugMode = false;

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
        DebugMode = configuration.getBoolean(
            "DebugMode",
            Configuration.CATEGORY_GENERAL,
            DebugMode,
            "Enable debug logging for EZNuclear mod");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
