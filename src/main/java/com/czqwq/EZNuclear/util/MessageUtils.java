package com.czqwq.EZNuclear.util;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.StatCollector;

import com.czqwq.EZNuclear.EZNuclear;

import gregtech.api.util.GTUtility;

public class MessageUtils {

    public static void sendToAllPlayers(String messageKey) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            if (!server.isSinglePlayer()) {
                if (server.getConfigurationManager() != null
                    && server.getConfigurationManager().playerEntityList != null) {
                    List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
                    for (EntityPlayerMP p : players) {
                        if (p != null) {
                            GTUtility.sendChatToPlayer(p, StatCollector.translateToLocal(messageKey));
                        }
                    }
                }
            } else {
                sendToSinglePlayer(messageKey);
            }
        }
    }

    public static void sendToSinglePlayer(String messageKey) {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getMinecraft")
                .invoke(null);
            if (mc != null) {
                Object thePlayer = mcClass.getField("thePlayer")
                    .get(mc);
                if (thePlayer != null) {
                    Class<?> chatClass = Class.forName("net.minecraft.util.ChatComponentTranslation");
                    Object chat = chatClass.getConstructor(String.class, Object[].class)
                        .newInstance(messageKey, new Object[0]);
                    Class<?> iChatClass = Class.forName("net.minecraft.util.IChatComponent");
                    thePlayer.getClass()
                        .getMethod("addChatMessage", iChatClass)
                        .invoke(thePlayer, chat);
                }
            }
        } catch (ClassNotFoundException e) {
            EZNuclear.LOG.debug(
                "Client-side Minecraft classes not available, skipping single player message: " + e.getMessage());
        } catch (Throwable t) {
            EZNuclear.LOG.warn("Failed to send message to single player: " + t.getMessage());
        }
    }

    // 重载方法，支持带参数的消息
    public static void sendToAllPlayers(String messageKey, Object... params) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            if (!server.isSinglePlayer()) {
                if (server.getConfigurationManager() != null
                    && server.getConfigurationManager().playerEntityList != null) {
                    List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
                    for (EntityPlayerMP p : players) {
                        if (p != null) {
                            GTUtility.sendChatToPlayer(p, StatCollector.translateToLocalFormatted(messageKey, params));
                        }
                    }
                }
            } else {
                sendToSinglePlayer(messageKey, params);
            }
        }
    }

    public static void sendToSinglePlayer(String messageKey, Object... params) {
        try {
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcClass.getMethod("getMinecraft")
                .invoke(null);
            if (mc != null) {
                Object thePlayer = mcClass.getField("thePlayer")
                    .get(mc);
                if (thePlayer != null) {
                    Class<?> chatClass = Class.forName("net.minecraft.util.ChatComponentTranslation");
                    Object chat = chatClass.getConstructor(String.class, Object[].class)
                        .newInstance(messageKey, params);
                    Class<?> iChatClass = Class.forName("net.minecraft.util.IChatComponent");
                    thePlayer.getClass()
                        .getMethod("addChatMessage", iChatClass)
                        .invoke(thePlayer, chat);
                }
            }
        } catch (ClassNotFoundException e) {
            EZNuclear.LOG.debug(
                "Client-side Minecraft classes not available, skipping single player message: " + e.getMessage());
        } catch (Throwable t) {
            EZNuclear.LOG.warn("Failed to send message to single player: " + t.getMessage());
        }
    }
}
