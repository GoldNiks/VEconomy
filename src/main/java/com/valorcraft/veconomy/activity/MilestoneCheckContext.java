package com.valorcraft.veconomy.activity;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * Контекст проверки условия milestone: данные игрока, доступные обработчику условия.
 * Продакшен-реализации (онлайн/офлайн) строятся сервисом; тесты используют свои
 * фейковые реализации, поэтому условные обработчики тестируются без живого сервера.
 */
public interface MilestoneCheckContext {

    UUID playerId();

    /**
     * Живой игрок сервера, если он онлайн. Для офлайн-проверок пусто: условия,
     * требующие живого прогресса (advancement), отвечают NOT_AVAILABLE.
     */
    Optional<net.minecraft.server.level.ServerPlayer> player();

    /**
     * Прогресс по advancement: {@code true}/{@code false} — выполнено/нет;
     * пусто, если игрок офлайн и прогресс недоступен. {@code false} также возвращается,
     * если advancement с таким id не зарегистрирован на сервере.
     */
    Optional<Boolean> advancementDone(ResourceLocation advancementId);
}
