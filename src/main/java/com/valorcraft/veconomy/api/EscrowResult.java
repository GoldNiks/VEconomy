package com.valorcraft.veconomy.api;

/**
 * Результат операции эскроу.
 */
public record EscrowResult(Status status, long reservedAmount, String referenceId) {

    public enum Status {
        /** Операция выполнена. */
        SUCCESS,
        /** Недостаточно средств у владельца. */
        INSUFFICIENT_FUNDS,
        /** Некорректная сумма (ноль/отрицательная). */
        INVALID_AMOUNT,
        /** Эскроу-запись с таким referenceId не найдена. */
        NOT_FOUND,
        /** Эскроу-запись уже в финальном состоянии (capture/release повторно). */
        WRONG_STATE,
        /** Запись с таким referenceId уже существует. */
        DUPLICATE,
        /** Аккаунт заморожен. */
        ACCOUNT_DISABLED,
        /** Превышен лимит баланса. */
        LIMIT_EXCEEDED,
        /** Ошибка базы данных. */
        DATABASE_ERROR
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public static EscrowResult success(long reservedAmount, String referenceId) {
        return new EscrowResult(Status.SUCCESS, reservedAmount, referenceId);
    }

    public static EscrowResult failed(Status status) {
        return new EscrowResult(status, -1L, null);
    }
}
