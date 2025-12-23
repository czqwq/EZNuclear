package com.czqwq.EZNuclear.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.brandon3055.draconicevolution.common.tileentities.multiblocktiles.reactor.ReactorExplosion;

@SuppressWarnings("UnusedMixin")
@Mixin(value = ReactorExplosion.class, remap = false)
public abstract class ReactorExplosionMixin {

    // This mixin is no longer needed as TileReactorCoreMixin handles DE explosion logic
    // The original functionality has been moved to TileReactorCoreMixin for better integration
    // This class is kept as a placeholder to avoid any potential issues during transitions
    // But the actual injection has been removed to prevent duplicate processing

}
