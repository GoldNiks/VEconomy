package com.valorcraft.veconomy.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * Поиск UUID игрока по нику. Имя используется только для поиска и отображения;
 * источником истины всегда является UUID. Офлайн-UUID из ника не генерируется вручную —
 * используется профильный кеш сервера (online mode).
 */
public final class PlayerResolver {

    private PlayerResolver() {}

    /** Игрок онлайн (только серверный поток). */
    public static ServerPlayer online(MinecraftServer server, String name) {
        return server.getPlayerList().getPlayerByName(name);
    }

    /**
     * Разрешить игрока по нику или UUID-строке: сначала онлайн-игрок, затем кеш профилей,
     * затем прямая попытка распарсить UUID.
     */
    public static Resolved resolve(MinecraftServer server, String input) {
        if (input == null || input.isBlank()) {
            return Resolved.missing();
        }
        ServerPlayer online = online(server, input);
        if (online != null) {
            return new Resolved(online.getGameProfile(), online);
        }
        try {
            UUID asUuid = UUID.fromString(input);
            ServerPlayer byUuid = server.getPlayerList().getPlayer(asUuid);
            GameProfile profile = server.getProfileCache().get(asUuid).orElse(null);
            String name = profile != null ? profile.getName()
                    : byUuid != null ? byUuid.getGameProfile().getName() : null;
            return new Resolved(new GameProfile(asUuid, name), byUuid);
        } catch (IllegalArgumentException ignored) {
            // не UUID — ищем по нику в кеше профилей
        }
        Optional<GameProfile> profile = server.getProfileCache().get(input);
        if (profile.isPresent()) {
            ServerPlayer byName = server.getPlayerList().getPlayer(profile.get().getId());
            return new Resolved(profile.get(), byName);
        }
        return Resolved.missing();
    }

    /** Результат поиска игрока. */
    public record Resolved(GameProfile profile, ServerPlayer player) {
        public boolean exists() {
            return profile != null;
        }

        public UUID uuid() {
            return profile.getId();
        }

        public String name() {
            return player != null ? player.getGameProfile().getName() : profile.getName();
        }

        public static Resolved missing() {
            return new Resolved(null, null);
        }
    }
}
