package com.czqwq.EZNuclear.mixin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
 * 
 * Based on the fix from GTNewHorizons/BrandonsCore repository.
 * Reference: https://github.com/GTNewHorizons/BrandonsCore/blob/master/src/main/java/com/brandon3055/brandonscore/common/handlers/ProcessHandler.java
 */
@Mixin(value = com.brandon3055.brandonscore.common.handlers.ProcessHandler.class, remap = false)
public class ProcessHandlerMixin {

    @Shadow
    private static List<IProcess> processes;

    /**
     * Separate list for processes that are added while iterating.
     * This prevents ConcurrentModificationException by deferring additions until after iteration.
     * Matches the approach used in GTNewHorizons/BrandonsCore.
     */
    private static List<IProcess> newProcesses = new ArrayList<IProcess>();

    /**
     * Fix ConcurrentModificationException by using Iterator for safe removal
     * and a separate list for new processes added during iteration.
     * 
     * The fix works by:
     * 1. Using Iterator.remove() instead of List.remove() for safe removal during iteration
     * 2. Queuing new process additions in a separate list
     * 3. Adding queued processes after iteration completes
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
     * Intercept addProcess to queue new processes instead of adding them directly.
     * This prevents ConcurrentModificationException when processes are added during iteration.
     * 
     * Note: All processes will be queued and added at the start of the next server tick,
     * which matches the behavior of GTNewHorizons/BrandonsCore fix.
     */
    @Inject(method = "addProcess", at = @At("HEAD"), cancellable = true, remap = false)
    private static void addProcessFix(IProcess process, CallbackInfo ci) {
        // Queue the process to be added at the start of the next server tick
        newProcesses.add(process);
        ci.cancel();
    }
}
