package com.czqwq.EZNuclear;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());

        // Load optional mixin configs for DE and Brandon's Core if the mods are present
        if (Loader.isModLoaded("DraconicEvolution")) {
            try {
                Class.forName("org.spongepowered.asm.mixin.MixinEnvironment")
                    .getMethod("getDefaultEnvironment")
                    .invoke(null);

                // Try to add the DE mixin config
                org.spongepowered.asm.mixin.MixinEnvironment.getDefaultEnvironment()
                    .addConfiguration("mixins.eznuclear.de.json");
            } catch (Throwable t) {
                EZNuclear.LOG.warn("Could not load Draconic Evolution mixins: " + t.getMessage());
            }
        }

        if (Loader.isModLoaded("BrandonsCore")) {
            try {
                Class.forName("org.spongepowered.asm.mixin.MixinEnvironment")
                    .getMethod("getDefaultEnvironment")
                    .invoke(null);

                // Try to add the BrandonsCore mixin config
                org.spongepowered.asm.mixin.MixinEnvironment.getDefaultEnvironment()
                    .addConfiguration("mixins.eznuclear.brandonscore.json");
            } catch (Throwable t) {
                EZNuclear.LOG.warn("Could not load Brandon's Core mixins: " + t.getMessage());
            }
        }
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {}

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {}

    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {}
}
