package com.valorcraft.veconomy.audit;

import java.util.UUID;

/**
 * Кто инициировал аудит-событие. Для административных действий фиксируется
 * реальный инициатор: игрок (PLAYER) с его UUID либо консоль (CONSOLE).
 * Автоматические действия помечены SYSTEM (недельный фонд, события милстоунов),
 * внешние интеграции (KubeJS) — INTEGRATION.
 */
public enum AuditActorType {

    /** Живой игрок (actorId — UUID инициатора). */
    PLAYER,
    /** Консоль сервера (actorId пуст). */
    CONSOLE,
    /** Автоматическое действие самой экономики. */
    SYSTEM,
    /** Внешняя интегрированная система (KubeJS/мост). */
    INTEGRATION;

    /** Производный тип по идентификатору инициатора: игрок или консоль. */
    public static AuditActorType of(UUID actorId) {
        return actorId != null ? PLAYER : CONSOLE;
    }
}