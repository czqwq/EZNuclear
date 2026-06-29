package com.czqwq.EZNuclear.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.brandon3055.draconicevolution.common.utils.handlers.IProcess;

import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Mixin to fix ConcurrentModificationException in ProcessHandler.onServerTick
 * and to add thread-safety via synchronized blocks.
 *
 * BrandonsCore has been merged into Draconic Evolution. The new DE ProcessHandler
 * already queues additions via newProcesses internally, but this mixin adds
 * synchronized blocks for additional thread-safety and uses snapshot iteration
 * to guard against concurrent modification from other code paths.
 *
 * Original reference (BrandonsCore):
 * https://github.com/GTNewHorizons/BrandonsCore/blob/master/src/main/java/com/brandon3055/brandonscore/common/handlers/ProcessHandler.java
 */
@Mixin(value = com.brandon3055.draconicevolution.common.utils.handlers.ProcessHandler.class, remap = false)
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
     * 1. Creating a snapshot copy of the processes list before iteration (synchronized)
     * 2. Iterating over the snapshot to safely check and update each process
     * 3. Removing dead processes from the original list after iteration (synchronized)
     * 4. Adding queued processes from newProcesses after iteration completes (synchronized)
     */
    @Inject(method = "onServerTick", at = @At("HEAD"), cancellable = true, remap = false)
    private void onServerTickFix(TickEvent.ServerTickEvent event, CallbackInfo ci) {
        if (event.phase == TickEvent.Phase.START) {
            // Create a snapshot copy to iterate over safely
            // Synchronized to prevent concurrent modification during copy
            List<IProcess> snapshot;
            synchronized (processes) {
                snapshot = new ArrayList<IProcess>(processes);
            }

            // Collect dead processes for efficient batch removal
            List<IProcess> deadProcesses = new ArrayList<IProcess>();

            // Iterate over the snapshot, not the original list
            // This is safe even if processes list is modified during iteration
            for (IProcess process : snapshot) {
                if (process.isDead()) {
                    // Collect dead processes for batch removal
                    deadProcesses.add(process);
                } else {
                    // Update the process
                    process.updateProcess();
                }
            }

            // Remove all dead processes and add new processes in one synchronized block
            // This ensures atomic operations and prevents race conditions
            synchronized (processes) {
                // Remove all dead processes in one operation (O(n) instead of O(n²))
                if (!deadProcesses.isEmpty()) {
                    processes.removeAll(deadProcesses);
                }

                // Add any new processes that were queued during iteration
                // Check and clear are atomic within this synchronized block
                if (!newProcesses.isEmpty()) {
                    processes.addAll(newProcesses);
                    newProcesses.clear();
                }
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
     * Synchronized with processes list to ensure thread-safety.
     */
    @Inject(method = "addProcess", at = @At("HEAD"), cancellable = true, remap = false)
    private static void addProcessFix(IProcess process, CallbackInfo ci) {
        // Queue the process to be added after the current iteration completes
        // Use synchronization to match the thread-safety guarantees in onServerTickFix
        synchronized (processes) {
            newProcesses.add(process);
        }
        ci.cancel();
    }
}
