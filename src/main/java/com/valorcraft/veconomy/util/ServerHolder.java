package com.valorcraft.veconomy.util;

import net.minecraft.server.MinecraftServer;

/** Хранит ссылку на запущенный сервер для серверных интеграций (KubeJS и т.п.). */
public final class ServerHolder {

    private static volatile MinecraftServer server;

    private ServerHolder() {}

    public static void set(MinecraftServer value) {
        server = value;
    }

    public static MinecraftServer get() {
        return server;
    }

    public static void clear() {
        server = null;
    }
}
