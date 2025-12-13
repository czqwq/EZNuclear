package com.czqwq.EZNuclear;

import net.minecraftforge.common.MinecraftForge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.czqwq.EZNuclear.data.PendingMeltdown;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(
    modid = EZNuclear.MODID,
    version = Tags.VERSION,
    name = "EZNulcear",
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:IC2;" + "required-after:structurelib;"
        + "required-after:dreamcraft;"
        + "required-after:gregtech;"
        + "required-after:BrandonsCore;"
        + "required-after:DraconicEvolution;")

public class EZNuclear {

    public static final String MODID = "EZNuclear";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = "com.czqwq.EZNuclear.ClientProxy", serverSide = "com.czqwq.EZNuclear.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
        // register PendingMeltdown to listen for chat and server tick events
        try {
            PendingMeltdown handler = new PendingMeltdown();
            MinecraftForge.EVENT_BUS.register(handler);
            FMLCommonHandler.instance()
                .bus()
                .register(handler);
            // FMLCommonHandler.instance().bus() may be used for other events if needed
            // LOG.info("PendingMeltdown registered to event bus");
        } catch (Throwable t) {
            // LOG.warn("Failed to register PendingMeltdown: " + t.getMessage());
        }
    }

    @Mod.EventHandler
    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

}
