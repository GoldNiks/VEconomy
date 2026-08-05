package com.valorcraft.veconomy.activity;

/**
 * Тип условия milestone. Тип определяет обработчик условия через
 * {@link MilestoneConditionRegistry}: событие или административный запрос
 * не содержат switch по типам — только запрос условия у реестра.
 */
public enum MilestoneType {
    /** Достижение порога активного времени (личное). */
    PLAYTIME,
    /** Получение конкретного Minecraft-advancement. */
    ADVANCEMENT,
    /** Личное посещение измерения. */
    DIMENSION_VISIT,
    /** Начисление доверенной внешней системой (KubeJS, консоль, интеграции). */
    EXTERNAL
}
