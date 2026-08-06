package com.valorcraft.veconomy.activity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * Контекст проверки без живого игрока (периодическая проверка по UUID, админ-команда
 * по офлайн-игроку). Условия, требующие живого прогресса (advancement), отвечают
 * NOT_AVAILABLE; PLAYTIME и DIMENSION_VISIT работают по данным базы.
 */
public final class OfflineMilestoneCheckContext implements MilestoneCheckContext {

    private final UUID playerId;

    private OfflineMilestoneCheckContext(UUID playerId) {
        this.playerId = playerId;
    }

    public static OfflineMilestoneCheckContext of(UUID playerId) {
        return new OfflineMilestoneCheckContext(playerId);
    }

    @Override
    public UUID playerId() {
        return playerId;
    }

    @Override
    public Optional<ServerPlayer> player() {
        return Optional.empty();
    }

    @Override
    public Optional<Boolean> advancementDone(ResourceLocation advancementId) {
        return Optional.empty();
    }

    @Override
    public Optional<Boolean> advancementRegistered(ResourceLocation advancementId) {
        return Optional.empty();
    }
}
