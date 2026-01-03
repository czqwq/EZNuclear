package com.czqwq.EZNuclear.mixin;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.brandon3055.draconicevolution.common.tileentities.multiblocktiles.reactor.TileReactorCore;
import com.czqwq.EZNuclear.Config;
import com.czqwq.EZNuclear.EZNuclear;
import com.czqwq.EZNuclear.data.PendingMeltdown;
import com.czqwq.EZNuclear.util.MessageUtils;

import gregtech.api.util.GTUtility;

@SuppressWarnings("UnusedMixin")
@Mixin(value = TileReactorCore.class, remap = false)
public abstract class TileReactorCoreMixin {

    // reentry is managed by PendingMeltdown to keep a single source of truth

    /**
     * Helper method to get WorldServer by dimension ID.
     * Since dimension IDs can be negative or high values that don't match array indices,
     * we need to find the WorldServer by iterating through the available worlds.
     */
    private static WorldServer getWorldServerByDimension(MinecraftServer server, int dimensionId) {
        if (server == null || server.worldServers == null) {
            return null;
        }

        for (WorldServer worldServer : server.worldServers) {
            if (worldServer != null && worldServer.provider.dimensionId == dimensionId) {
                return worldServer;
            }
        }

        return null; // World with specified dimension not found
    }

    // reentry is managed by PendingMeltdown to keep a single source of truth

    @Inject(method = "goBoom", remap = false, at = @At("HEAD"), cancellable = true)
    private void onGoBoom(CallbackInfo ci) {
        // System.out.println("[EZNuclear] TileReactorCore.goBoom called");
        // Get the tile entity to access position and world information
        TileEntity te = (TileEntity) (Object) this;
        ChunkCoordinates pos = new ChunkCoordinates(te.xCoord, te.yCoord, te.zCoord);

        // Check if DE explosions are disabled in config
        if (!Config.DEExplosion) {
            // Even if explosion is disabled, still send the message to players
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                if (!server.isSinglePlayer()) {
                    List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
                    for (EntityPlayerMP p : players) {
                        GTUtility.sendChatToPlayer(p, StatCollector.translateToLocal("info.ezunclear"));
                    }
                } else {
                    // For single player, try to send message to client
                    MessageUtils.sendToSinglePlayer("info.ezunclear");
                }

                // Get the world and dimension for proper scheduling
                int dimId = 0;
                if (te.getWorldObj() != null) {
                    try {
                        dimId = te.getWorldObj().provider.dimensionId;
                    } catch (Exception e) {
                        EZNuclear.LOG
                            .warn("[EZNuclear] Could not get dimension from world, using default 0: " + e.getMessage());
                    }
                }

                // Schedule the second message after delay using actual position
                PendingMeltdown.schedule(pos, () -> {
                    MinecraftServer srv = MinecraftServer.getServer();
                    if (srv != null) {
                        if (!srv.isSinglePlayer()) {
                            List<EntityPlayerMP> players = srv.getConfigurationManager().playerEntityList;
                            for (EntityPlayerMP p : players) {
                                GTUtility.sendChatToPlayer(
                                    p,
                                    StatCollector.translateToLocal("info.ezunclear.preventexplosion"));
                            }
                        } else {
                            MessageUtils.sendToSinglePlayer("info.ezunclear.preventexplosion");
                        }
                    }
                }, Config.explosionDelaySeconds * 1000L, dimId);
            }

            ci.cancel();
            return;
        }

        // Get dimension for position tracking
        int dimension = 0;
        if (te.getWorldObj() != null) {
            try {
                dimension = te.getWorldObj().provider.dimensionId;
            } catch (Exception e) {
                EZNuclear.LOG
                    .warn("[EZNuclear] Could not get dimension from world, using default 0: " + e.getMessage());
            }
        }
        // System.out.println("[EZNuclear] DE goBoom at position: " + pos + " in dimension: " + dimension);

        // Check if this position has recently had a manual trigger to prevent duplicate processing
        // System.out.println(
        // "[EZNuclear] DE goBoom: Checking if position " + pos + " should be ignored, dimension: " + dimension);
        if (com.czqwq.EZNuclear.data.PendingMeltdown.shouldIgnoreExplosionAt(pos, dimension)) {
            // System.out.println(
            // "[EZNuclear] Position " + pos + " had recent manual trigger, skipping DE goBoom to prevent duplicate");
            return; // Skip processing for this position
        } else {
            // System.out.println("[EZNuclear] Position " + pos + " passed ignore check, continuing with DE goBoom");
        }

        if (com.czqwq.EZNuclear.data.PendingMeltdown.consumeReentry(pos)) {
            // System.out.println("[EZNuclear] Reentry consumed for position " + pos + ", allowing original goBoom");
            return; // allow original goBoom
        }

        if (PendingMeltdown.schedule(pos, createScheduledTask(te), Config.explosionDelaySeconds * 1000L)) {
            ci.cancel();
            // System.out.println("[EZNuclear] Scheduled task for DE explosion at position: " + pos);
            PendingMeltdown.schedule(pos, () -> {}, 0L); // touch pos to log scheduling if needed

            // Check if manual trigger is required
            if (Config.requireCommandToExplode) {
                // Mark this position for DE manual trigger with configured power
                PendingMeltdown.markDEManualTriggerWithPower(
                    pos,
                    (te.getWorldObj() != null)
                        ? (te.getWorldObj().provider.dimensionId >= 0 ? te.getWorldObj().provider.dimensionId : 0)
                        : 0,
                    Config.DEExplosionPower);
                // System.out.println("[EZNuclear] Marked DE explosion for manual trigger at position: " + pos);
            } else {
                // System.out.println("[EZNuclear] DE explosion in auto mode at position: " + pos);
            }

            MinecraftServer server = MinecraftServer.getServer();
            if (server != null && !server.isSinglePlayer()) {
                List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
                for (EntityPlayerMP p : players) {
                    GTUtility.sendChatToPlayer(p, StatCollector.translateToLocal("info.ezunclear"));
                }
            } else {
                World world = te.getWorldObj();
                if (world != null && world.isRemote) {
                    MessageUtils.sendToSinglePlayer("info.ezunclear");
                } else if (server != null) {
                    List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
                    for (EntityPlayerMP p : players) {
                        GTUtility.sendChatToPlayer(p, StatCollector.translateToLocal("info.ezunclear"));
                    }
                }
            }
        }
    }

    // create the Runnable to be run on server thread by PendingMeltdown scheduler
    private Runnable createScheduledTask(TileEntity te) {
        final int sx = te.xCoord;
        final int sy = te.yCoord;
        final int sz = te.zCoord;
        int sdim = 0;
        if (te.getWorldObj() != null) {
            try {
                sdim = te.getWorldObj().provider.dimensionId;
            } catch (Exception e) {
                EZNuclear.LOG
                    .warn("[EZNuclear] Could not get dimension from world, using default 0: " + e.getMessage());
            }
        }
        final int finalSdim = sdim; // Make sdim final to be captured by lambda
        return () -> {
            // Lookup world and tile entity at execution time to avoid stale references
            net.minecraft.server.MinecraftServer srvLookup = net.minecraft.server.MinecraftServer.getServer();
            net.minecraft.world.WorldServer worldServer = null;
            if (srvLookup != null) {
                worldServer = getWorldServerByDimension(srvLookup, finalSdim);
            }
            TileReactorCore reactor = null;
            World world;
            if (worldServer != null) {
                world = worldServer;
                TileEntity fresh = worldServer.getTileEntity(sx, sy, sz);
                if (fresh instanceof TileReactorCore) reactor = (TileReactorCore) fresh;
            } else {
                // fallback to original passed-in TE if worldServer unavailable
                reactor = (te instanceof TileReactorCore) ? (TileReactorCore) te : null;
                world = te.getWorldObj();
            }

            // Check if manual trigger is required
            ChunkCoordinates pos = new ChunkCoordinates(sx, sy, sz);
            if (Config.requireCommandToExplode) {
                // If manual trigger is required, mark this position for manual trigger with power
                PendingMeltdown.markDEManualTriggerWithPower(pos, finalSdim, Config.DEExplosionPower);

                // In manual mode, we don't schedule the interaction message automatically
                // The message is sent when the player manually triggers the explosion
                // System.out
                // .println("[EZNuclear] Manual trigger mode: marked position " + pos + " for manual activation");
                return;
            }

            // send second message if manual trigger is not required (auto mode)
            if (!Config.requireCommandToExplode) {
                MinecraftServer srv = MinecraftServer.getServer();
                if (srv != null && !srv.isSinglePlayer()) {
                    List<EntityPlayerMP> players = srv.getConfigurationManager().playerEntityList;
                    for (EntityPlayerMP p : players) {
                        GTUtility.sendChatToPlayer(p, StatCollector.translateToLocal("info.ezunclear.interact"));
                    }
                } else if (world != null && world.isRemote) {
                    MessageUtils.sendToSinglePlayer("info.ezunclear.interact");
                } else if (srv != null) {
                    List<EntityPlayerMP> players = srv.getConfigurationManager().playerEntityList;
                    for (EntityPlayerMP p : players) {
                        GTUtility.sendChatToPlayer(p, StatCollector.translateToLocal("info.ezunclear.interact"));
                    }
                }
            }

            // allow re-entry and invoke goBoom
            com.czqwq.EZNuclear.data.PendingMeltdown.markReentry(pos);
            try {
                if (reactor != null) {
                    // Instead of calling the original goBoom which calculates power dynamically,
                    // create a ReactorExplosion directly with our configured power
                    float power = (float) Config.DEExplosionPower;

                    // Use the common method from PendingMeltdown to create and execute DE explosion
                    com.czqwq.EZNuclear.data.PendingMeltdown.createAndExecuteDEExplosion(world, sx, sy, sz, power);
                } else {
                    throw new IllegalStateException("TileReactorCore not found at " + pos);
                }
            } catch (Exception e) {
                // fallback: direct explosion
                try {
                    float power = (float) Config.DEExplosionPower; // Use configured power instead of dynamic
                                                                   // calculation
                    // Use the common method from PendingMeltdown to create and execute DE explosion
                    com.czqwq.EZNuclear.data.PendingMeltdown.createAndExecuteDEExplosion(world, sx, sy, sz, power);
                } catch (Throwable ex) {
                    // Failed fallback explosion
                    // System.out.println("[EZNuclear] Failed to create DE explosion fallback: " + ex.getMessage());
                }
            }
        };
    }
}
