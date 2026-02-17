package com.czqwq.EZNuclear.mixin;

import java.util.ArrayList;
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
 * Based on the fix from GTNewHorizons/BrandonsCore repository, but adapted for mixin usage
 * by iterating over a snapshot copy instead of directly over the shared list.
 * 
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
     * Fix ConcurrentModificationException by iterating over a snapshot copy of the process list.
     * 
     * Unlike the GTNewHorizons source code fix (which modifies ProcessHandler directly),
     * this mixin approach must be more defensive because:
     * 1. The processes list can be modified from other code paths we don't control
     * 2. Dimension changes and chunk unloading can trigger concurrent modifications
     * 3. We cannot guarantee all modification paths go through our intercepted addProcess
     * 
     * The fix works by:
     * 1. Creating a snapshot copy of the processes list before iteration
     * 2. Iterating over the snapshot to safely check and update each process
     * 3. Removing dead processes from the original list after iteration
     * 4. Adding queued processes from newProcesses after iteration completes
     */
    @Inject(method = "onServerTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void onServerTickFix(TickEvent.ServerTickEvent event, CallbackInfo ci) {
        if (event.phase == TickEvent.Phase.START) {
            // Create a snapshot copy to iterate over safely
            // This prevents ConcurrentModificationException even if processes list is modified
            // during iteration (e.g., during dimension changes, chunk unloading)
            List<IProcess> snapshot = new ArrayList<IProcess>(processes);
            
            // Collect dead processes for efficient batch removal
            List<IProcess> deadProcesses = new ArrayList<IProcess>();

            // Iterate over the snapshot, not the original list
            for (IProcess process : snapshot) {
                if (process.isDead()) {
                    // Collect dead processes for batch removal
                    deadProcesses.add(process);
                } else {
                    // Update the process
                    process.updateProcess();
                }
            }
            
            // Remove all dead processes in one operation (O(n) instead of O(n²))
            if (!deadProcesses.isEmpty()) {
                processes.removeAll(deadProcesses);
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
     * Note: All processes will be queued and added during the onServerTick START phase,
     * after the current iteration completes. This matches the behavior of GTNewHorizons/BrandonsCore fix.
     */
    @Inject(method = "addProcess", at = @At("HEAD"), cancellable = true, remap = false)
    private static void addProcessFix(IProcess process, CallbackInfo ci) {
        // Queue the process to be added after the current iteration completes
        newProcesses.add(process);
        ci.cancel();
    }
}
