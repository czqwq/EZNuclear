package com.czqwq.EZNuclear.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;

import com.czqwq.EZNuclear.EZNuclear;

public class MessageUtils {

    /**
     * 向所有在线玩家广播一条翻译键消息。
     * 使用 ChatComponentTranslation 让客户端按自身语言显示；只有服务端调用才有意义，
     * 专用客户端上 MinecraftServer.getServer() 为 null，直接忽略即可。
     */
    public static void broadcast(String translationKey) {
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.getConfigurationManager() == null) {
                return;
            }
            server.getConfigurationManager()
                .sendChatMsg(new ChatComponentTranslation(translationKey));
        } catch (Throwable t) {
            EZNuclear.LOG.warn("[EZNuclear] Failed to broadcast message " + translationKey + ": " + t.getMessage());
        }
    }
}
