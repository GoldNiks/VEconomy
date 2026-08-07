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
        /** Некорректное распределение при расчёте (нулевые/отрицательные доли,
         *  получатель не указан, сумма долей не равна зарезервированной). */
        INVALID_CREDITS,
        /** Эскроу-запись с таким referenceId не найдена. */
        NOT_FOUND,
        /** Запись в состоянии, не допускающем операцию (например, release после settle). */
        WRONG_STATE,
        /** Запись с таким referenceId уже существует с другими параметрами. */
        CONFLICT,
        /** Повторный reserve с теми же параметрами — идемпотентный повтор, ничего не изменил. */
        ALREADY_RESERVED,
        /** Повторный settle с тем же распределением — идемпотентный повтор. */
        ALREADY_SETTLED,
        /** Псевдоним DUPLICATE устарел, см. {@link #ALREADY_RESERVED}/{@link #CONFLICT}. */
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

    /** Идемпотентный повтор (reserve/settle) — «совместимый» успех: состояние уже таким и было. */
    public boolean isSuccessOrIdempotent() {
        return switch (status) {
            case SUCCESS, ALREADY_RESERVED, ALREADY_SETTLED -> true;
            default -> false;
        };
    }

    public static EscrowResult success(long reservedAmount, String referenceId) {
        return new EscrowResult(Status.SUCCESS, reservedAmount, referenceId);
    }

    public static EscrowResult success(long reservedAmount, String referenceId, Status status) {
        return new EscrowResult(status, reservedAmount, referenceId);
    }

    public static EscrowResult failed(Status status) {
        return new EscrowResult(status, -1L, null);
    }
}
