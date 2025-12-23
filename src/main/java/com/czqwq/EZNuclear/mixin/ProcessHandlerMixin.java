package com.czqwq.EZNuclear.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.brandon3055.brandonscore.common.handlers.ProcessHandler;

@SuppressWarnings("UnusedMixin")
@Mixin(value = ProcessHandler.class, remap = false)
public class ProcessHandlerMixin {

    // This mixin is no longer needed as TileReactorCoreMixin handles DE explosion logic
    // The original functionality has been moved to TileReactorCoreMixin for better integration
    // This class is kept as a placeholder to avoid any potential issues during transitions
    // But the actual injection has been removed to prevent duplicate processing

}
