package com.valorcraft.veconomy.activity;

/**
 * Результат административной смены флага {@code excluded_from_rewards}.
 * <p>
 * Булевый признак успеха недостаточен: при ошибке базы запрошенное значение
 * («исключить игрока») не применено, но {@code false} совпало бы с успешным
 * «снять исключение». Результат явно различает подтверждённое изменение,
 * ошибку и фактическое значение флага.
 *
 * @param status         {@link Status#OK} — флаг подтверждённо изменён;
 *                       {@link Status#DATABASE_ERROR} — изменение не применено
 * @param resultingValue значение флага после операции (корректно только для {@link Status#OK})
 * @param errorCode      технический код ошибки (null для {@link Status#OK})
 */
public record AccountFlagUpdateResult(Status status, boolean resultingValue, String errorCode) {

    public enum Status {
        OK,
        DATABASE_ERROR
    }

    public boolean isSuccess() {
        return status == Status.OK;
    }
}
