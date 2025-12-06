package com.czqwq.EZNuclear;

import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.collect.Lists;

import cpw.mods.fml.common.Loader;

public class EZNuclearMixinPlugin implements org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin {

    public static final Logger LOG = LogManager.getLogger("EZNuclearMixinPlugin");

    static {
        LOG.info("EZNuclearMixinPlugin class loaded");
    }

    @Override
    public void onLoad(String mixinPackage) {
        LOG.info("EZNuclearMixinPlugin loaded for package: " + mixinPackage);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        LOG.info("Checking if mixin should apply - Target: " + targetClassName + ", Mixin: " + mixinClassName);
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        LOG.info("Accepting targets. My targets: " + myTargets.size() + ", Other targets: " + otherTargets.size());
    }

    @Override
    public List<String> getMixins() {
        LOG.info("Getting mixins list...");
        List<String> mixins = Lists.newArrayList();

        // Check what mods are loaded
        boolean deLoaded = Loader.isModLoaded("DraconicEvolution");
        boolean bcLoaded = Loader.isModLoaded("BrandonsCore");
        boolean ic2Loaded = Loader.isModLoaded("IC2");

        LOG.info(
            "Mod loading status - DraconicEvolution: " + deLoaded
                + ", BrandonsCore: "
                + bcLoaded
                + ", IC2: "
                + ic2Loaded);

        // Only load mixins if the required mods are loaded
        if (deLoaded && bcLoaded) {
            LOG.info("Both DraconicEvolution and BrandonsCore detected, loading DE mixins");
            mixins.add("ProcessHandlerMixin");
            mixins.add("TileReactorCoreMixin");
        } else if (deLoaded) {
            LOG.info("DraconicEvolution detected, loading partial DE mixins");
            mixins.add("TileReactorCoreMixin");
        } else if (bcLoaded) {
            LOG.info("BrandonsCore detected, loading partial DE mixins");
            mixins.add("ProcessHandlerMixin");
        } else {
            LOG.info("Neither DraconicEvolution nor BrandonsCore detected, skipping DE mixins");
        }

        if (ic2Loaded) {
            LOG.info("IC2 detected, loading IC2 mixins");
            // IC2 mixins would be added here if needed
        }

        LOG.info("Final mixins list: " + mixins);
        return mixins;
    }

    @Override
    public void preApply(String targetClassName, org.spongepowered.asm.lib.tree.ClassNode targetClass,
        String mixinClassName, org.spongepowered.asm.mixin.extensibility.IMixinInfo mixinInfo) {
        // Nothing to do here
    }

    @Override
    public void postApply(String targetClassName, org.spongepowered.asm.lib.tree.ClassNode targetClass,
        String mixinClassName, org.spongepowered.asm.mixin.extensibility.IMixinInfo mixinInfo) {
        // Nothing to do here
    }
}
