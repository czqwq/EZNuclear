package com.czqwq.EZNuclear.mixin;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.brandon3055.brandonscore.common.handlers.IProcess;

import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Mixin to fix ConcurrentModificationException in ProcessHandler.onServerTick
 * This occurs when reactor explosions add processes while the list is being iterated.
 * Based on the fix from GTNewHorizons/BrandonsCore repository.
 */
@Mixin(value = com.brandon3055.brandonscore.common.handlers.ProcessHandler.class, remap = false)
public class ProcessHandlerMixin {

    @Shadow
    private static List<IProcess> processes;

    // Thread-safe list for processes that are added during iteration
    // This is our own field, not shadowed from the original class
    private static final List<IProcess> newProcesses = new CopyOnWriteArrayList<IProcess>();

    /**
     * Fix ConcurrentModificationException by using Iterator for safe removal
     * and a separate list for new processes added during iteration.
     * This matches the fix from GTNewHorizons/BrandonsCore.
     */
    @Inject(method = "onServerTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void onServerTickFix(TickEvent.ServerTickEvent event, CallbackInfo ci) {
        if (event.phase == TickEvent.Phase.START) {
            // Use Iterator for safe removal during iteration
            Iterator<IProcess> i = processes.iterator();

            while (i.hasNext()) {
                IProcess process = i.next();
                if (process.isDead()) {
                    i.remove(); // Safe removal using iterator
                } else {
                    process.updateProcess();
                }
            }

            // Add any new processes that were queued during iteration
            if (!newProcesses.isEmpty()) {
                processes.addAll(newProcesses);
                newProcesses.clear();
            }

            // Cancel the original method execution to prevent ConcurrentModificationException
            ci.cancel();
        }
    }

    /**
     * Intercept addProcess to use our newProcesses queue during server ticks.
     * This prevents ConcurrentModificationException when processes are added during iteration.
     */
    @Inject(method = "addProcess", at = @At("HEAD"), cancellable = true, remap = false)
    private static void addProcessFix(IProcess process, CallbackInfo ci) {
        // Always use the queue to ensure thread-safety
        // The queue will be processed at the start of the next server tick
        newProcesses.add(process);
        ci.cancel();
    }
}
