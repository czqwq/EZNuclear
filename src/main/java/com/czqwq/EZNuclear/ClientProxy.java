package com.czqwq.EZNuclear;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class ClientProxy extends CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
    }

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }

    // register server commands in this event handler (Remove if not needed)
    @Override
    public void serverStarting(FMLServerStartingEvent event) {
        super.serverStarting(event);
    }
}
