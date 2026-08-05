package com.valorcraft.veconomy.activity;

/**
 * Результат проверки условия milestone.
 *
 * @param status    мет/не мет/недоступно для автоматической проверки
 * @param reasonKey необязательный ключ локализации с объяснением (null для MET)
 */
public record MilestoneCheckResult(Status status, String reasonKey) {

    public enum Status {
        /** Условие выполнено, награду можно выдать. */
        MET,
        /** Условие не выполнено. */
        NOT_MET,
        /** Автоматическая проверка невозможна (например, игрок офлайн для advancement
         *  или тип EXTERNAL, который выдают только доверенные системы). */
        NOT_AVAILABLE
    }

    public static MilestoneCheckResult met() {
        return new MilestoneCheckResult(Status.MET, null);
    }

    public static MilestoneCheckResult notMet(String reasonKey) {
        return new MilestoneCheckResult(Status.NOT_MET, reasonKey);
    }

    public static MilestoneCheckResult unavailable(String reasonKey) {
        return new MilestoneCheckResult(Status.NOT_AVAILABLE, reasonKey);
    }
}
