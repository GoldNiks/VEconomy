package com.valorcraft.veconomy.activity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/** Контекст проверки по живому игроку сервера (события, админ-команды по онлайн-игроку). */
public final class ServerMilestoneCheckContext implements MilestoneCheckContext {

    private final ServerPlayer player;

    private ServerMilestoneCheckContext(ServerPlayer player) {
        this.player = player;
    }

    public static ServerMilestoneCheckContext of(ServerPlayer player) {
        return new ServerMilestoneCheckContext(player);
    }

    @Override
    public UUID playerId() {
        return player.getUUID();
    }

    @Override
    public Optional<ServerPlayer> player() {
        return Optional.of(player);
    }

    @Override
    public Optional<Boolean> advancementDone(ResourceLocation advancementId) {
        var advancement = player.getServer().getAdvancements().getAdvancement(advancementId);
        if (advancement == null) {
            return Optional.of(false);
        }
        return Optional.of(player.getAdvancements().getOrStartProgress(advancement).isDone());
    }

    @Override
    public Optional<Boolean> advancementRegistered(ResourceLocation advancementId) {
        return Optional.of(player.getServer().getAdvancements().getAdvancement(advancementId) != null);
    }
}
