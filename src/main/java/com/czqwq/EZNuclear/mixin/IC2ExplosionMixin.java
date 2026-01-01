package com.czqwq.EZNuclear.mixin;

import static net.minecraft.util.StatCollector.translateToLocal;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChunkCoordinates;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.czqwq.EZNuclear.Config;
import com.czqwq.EZNuclear.data.PendingMeltdown;
import com.czqwq.EZNuclear.util.MessageUtils;

import cpw.mods.fml.common.FMLCommonHandler;
import gregtech.api.util.GTUtility;

@Mixin(value = ic2.core.ExplosionIC2.class, remap = false)
public class IC2ExplosionMixin {

    static {
        System.out.println("[EZNuclear] IC2ExplosionMixin loaded");
    }

    // Flag to allow the deferred explosion to run once without being re-cancelled
    @Unique
    private volatile boolean eznuclear_ignoreNext = false;

    @Inject(method = "doExplosion", at = @At("HEAD"), cancellable = true)
    private void onDoExplosion(CallbackInfo ci) {
        System.out.println("[EZNuclear] IC2ExplosionMixin.onDoExplosion called");
        System.out.println("[EZNuclear] IC2 Explosion starting at HEAD injection");

        // Try to get explosion coordinates from the parent Explosion class fields
        int ex = 0, ey = 0, ez = 0;

        try {
            // Access the fields from the parent Explosion class
            net.minecraft.world.Explosion thisExplosion = (net.minecraft.world.Explosion) (Object) this;

            // Get coordinates from parent class Explosion fields explosionX, explosionY, explosionZ
            System.out.println("[EZNuclear] Trying to get coordinates from parent Explosion class");
            try {
                // Use the public fields from the parent Explosion class
                ex = (int) Math.floor(thisExplosion.explosionX);
                ey = (int) Math.floor(thisExplosion.explosionY);
                ez = (int) Math.floor(thisExplosion.explosionZ);
            } catch (Exception directAccessException) {
                System.out.println("[EZNuclear] Direct field access failed, trying reflection on parent class");
                // If direct access fails, try using reflection
                try {
                    java.lang.reflect.Field xField = thisExplosion.getClass()
                        .getDeclaredField("explosionX");
                    xField.setAccessible(true);
                    ex = (int) Math.floor(xField.getDouble(thisExplosion));
                } catch (Exception ignored) {}

                try {
                    java.lang.reflect.Field yField = thisExplosion.getClass()
                        .getDeclaredField("explosionY");
                    yField.setAccessible(true);
                    ey = (int) Math.floor(yField.getDouble(thisExplosion));
                } catch (Exception ignored) {}

                try {
                    java.lang.reflect.Field zField = thisExplosion.getClass()
                        .getDeclaredField("explosionZ");
                    zField.setAccessible(true);
                    ez = (int) Math.floor(zField.getDouble(thisExplosion));
                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            System.out.println("[EZNuclear] Exception while trying to get coordinates: " + e.getMessage());
            e.printStackTrace();
        }

        ChunkCoordinates pos = new ChunkCoordinates(ex, ey, ez);
        System.out.println("[EZNuclear] Final explosion position: " + pos);

        // Check if IC2 explosions are disabled in config
        if (!Config.IC2Explosion) {
            System.out.println("[EZNuclear] IC2 explosions disabled in config");
            // Even if explosion is disabled, still send the message to players
            MinecraftServer server = FMLCommonHandler.instance()
                .getMinecraftServerInstance();
            if (server != null) {
                if (!server.isSinglePlayer()) {
                    List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
                    for (EntityPlayerMP p : players) {
                        GTUtility.sendChatToPlayer(p, translateToLocal("info.ezunclear"));
                    }
                } else {
                    // For single player, try to send message to client
                    MessageUtils.sendToSinglePlayer("info.ezunclear");
                }

                // Schedule the second message after delay using PendingMeltdown system
                PendingMeltdown.schedule(pos, () -> {
                    MinecraftServer srv = FMLCommonHandler.instance()
                        .getMinecraftServerInstance();
                    if (srv != null) {
                        if (!srv.isSinglePlayer()) {
                            List<EntityPlayerMP> players = srv.getConfigurationManager().playerEntityList;
                            for (EntityPlayerMP p : players) {
                                GTUtility.sendChatToPlayer(p, translateToLocal("info.ezunclear.preventexplosion"));
                            }
                        } else {
                            MessageUtils.sendToSinglePlayer("info.ezunclear.preventexplosion");
                        }
                    }
                }, Config.explosionDelaySeconds * 1000L);
            }

            ci.cancel();
            return;
        }

        // If this is the deferred invocation, allow it to proceed once
        if (eznuclear_ignoreNext) {
            System.out.println("[EZNuclear] Allowing deferred explosion to proceed");
            eznuclear_ignoreNext = false;
            return;
        }

        // Check if this position was recently manually triggered to prevent re-scheduling
        int dimension = 0;
        try {
            net.minecraft.world.Explosion explosion = (net.minecraft.world.Explosion) (Object) this;
            // Use reflection to access the private worldObj field
            java.lang.reflect.Field worldObjField = net.minecraft.world.Explosion.class.getDeclaredField("worldObj");
            worldObjField.setAccessible(true);
            net.minecraft.world.World world = (net.minecraft.world.World) worldObjField.get(explosion);
            if (world != null) {
                dimension = world.provider.dimensionId;
            }
        } catch (Exception e) {
            System.out.println("[EZNuclear] Could not get dimension, using default 0");
        }

        // Check if this position has recently had a manual trigger to prevent duplicate processing
        System.out.println("[EZNuclear] Checking if position " + pos + " should be ignored, dimension: " + dimension);
        if (PendingMeltdown.shouldIgnoreExplosionAt(pos, dimension)) {
            System.out.println(
                "[EZNuclear] Position " + pos
                    + " had recent manual trigger, skipping IC2 explosion to prevent duplicate");
            return; // Skip processing for this position
        } else {
            System.out.println("[EZNuclear] Position " + pos + " passed ignore check, continuing with processing");
        }

        // Only run on server side -- use server global instead of shadowing world
        MinecraftServer server = FMLCommonHandler.instance()
            .getMinecraftServerInstance();
        if (server == null) return;

        // Send initial message to players (run on server thread directly)
        try {
            server.getConfigurationManager()
                .sendChatMsg(new ChatComponentTranslation("info.ezunclear"));
        } catch (Throwable t) {
            t.printStackTrace();
        }

        // Check if manual trigger is required
        if (Config.requireCommandToExplode) {
            System.out.println("[EZNuclear] Manual trigger required, cancelling explosion");
            // Cancel immediate explosion and mark for manual trigger
            ci.cancel();

            // Use fixed power value from configuration
            double power = Config.IC2ExplosionPower;
            System.out.println("[EZNuclear] Using fixed power from configuration: " + power);

            // Get dimension from the world
            int dimensionId = 0;
            try {
                net.minecraft.world.Explosion explosion = (net.minecraft.world.Explosion) (Object) this;
                // Use reflection to access the private worldObj field
                java.lang.reflect.Field worldObjField = net.minecraft.world.Explosion.class
                    .getDeclaredField("worldObj");
                worldObjField.setAccessible(true);
                net.minecraft.world.World world = (net.minecraft.world.World) worldObjField.get(explosion);
                if (world != null) {
                    dimensionId = world.provider.dimensionId;
                }
            } catch (Exception e) {
                System.out.println("[EZNuclear] Could not get dimension, using default 0");
                e.printStackTrace();
            }

            // Mark this position for manual trigger with stored power and dimension
            PendingMeltdown.markManualTriggerWithPower(pos, dimensionId, power);
        } else {
            System.out.println("[EZNuclear] Auto-trigger mode, scheduling explosion after delay");
            // Cancel immediate explosion and schedule the real one after delay
            ci.cancel();

            // Use PendingMeltdown system for consistency
            PendingMeltdown.schedule(pos, () -> {
                System.out.println(
                    "[EZNuclear] Delay completed, sending interaction message and triggering explosion at position: "
                        + pos);
                MinecraftServer s = FMLCommonHandler.instance()
                    .getMinecraftServerInstance();
                if (s != null) {
                    try {
                        s.getConfigurationManager()
                            .sendChatMsg(new ChatComponentTranslation("info.ezunclear.interact"));
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }

                    // Set flag so the next doExplosion invocation is allowed through
                    eznuclear_ignoreNext = true;

                    // Invoke the original method on the target instance
                    try {
                        ((ic2.core.ExplosionIC2) (Object) this).doExplosion();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
            }, Config.explosionDelaySeconds * 1000L);
        }
    }
}
