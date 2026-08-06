package com.valorcraft.veconomy.activity;

/**
 * Результат административной смены флага {@code excluded_from_rewards}.
 * <p>
 * Булевый признак успеха недостаточен: при ошибке базы запрошенное значение
 * («исключить игрока») не применено, но {@code false} совпало бы с успешным
 * «снять исключение». Результат явно различает подтверждённое изменение,
 * отсутствие изменения (флаг уже равен запрошенному), отсутствие игрока
 * и ошибку, а также фактическое значение флага.
 *
 * @param status         {@link Status#SUCCESS} — флаг подтверждённо изменён;
 *                       {@link Status#NO_CHANGES} — флаг уже равен запрошенному значению;
 *                       {@link Status#PLAYER_NOT_FOUND} — не задан идентификатор игрока;
 *                       {@link Status#DATABASE_ERROR} — изменение не применено
 * @param resultingValue значение флага после операции (корректно для SUCCESS/NO_CHANGES)
 * @param errorCode      технический код ошибки (null для SUCCESS/NO_CHANGES)
 */
public record AccountFlagUpdateResult(Status status, boolean resultingValue, String errorCode) {

    public enum Status {
        /** Подтверждённое изменение флага (или создание минимальной записи). */
        SUCCESS,
        /** Запись существует и флаг уже равен запрошенному значению: ничего не менялось. */
        NO_CHANGES,
        /** Игрок не найден (не задан идентификатор). */
        PLAYER_NOT_FOUND,
        /** Изменение не применено из-за ошибки базы. */
        DATABASE_ERROR
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}