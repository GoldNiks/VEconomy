package com.valorcraft.veconomy.api;

/**
 * Структурированный результат денежной операции (не только boolean).
 * <p>
 * При {@link Status#SUCCESS} заполнены {@code transactionId} и балансы после операции.
 */
public record TransactionResult(
        Status status,
        String transactionId,
        long sourceBalanceAfter,
        long targetBalanceAfter
) {

    public enum Status {
        /** Операция выполнена и записана в журнал. */
        SUCCESS,
        /** Недостаточно средств на балансе. */
        INSUFFICIENT_FUNDS,
        /** Некорректная сумма (меньше или равна нулю, либо превышает лимит перевода). */
        INVALID_AMOUNT,
        /** Аккаунт заморожен. */
        ACCOUNT_DISABLED,
        /** Превышен лимит (максимальный баланс или максимальная сумма перевода). */
        LIMIT_EXCEEDED,
        /** Операция с таким idempotency key уже выполнена ранее. */
        DUPLICATE_OPERATION,
        /** Состояние уже было целевым: ничего не изменено (например, повторная заморозка). */
        NO_CHANGES,
        /** Получатель не найден (и создание офлайн-получателя запрещено). */
        RECIPIENT_NOT_FOUND,
        /** Попытка перевода самому себе. */
        SELF_TRANSFER,
        /** Переводы игроков отключены конфигом. */
        TRANSFERS_DISABLED,
        /** Кулдаун между переводами ещё не истёк. */
        COOLDOWN_ACTIVE,
        /** Аккаунт не найден. */
        ACCOUNT_NOT_FOUND,
        /** Переполнение long при расчёте нового баланса. */
        OVERFLOW,
        /** Ошибка базы данных или внутренняя ошибка. */
        DATABASE_ERROR
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public static TransactionResult success(String transactionId, long sourceBalanceAfter, long targetBalanceAfter) {
        return new TransactionResult(Status.SUCCESS, transactionId, sourceBalanceAfter, targetBalanceAfter);
    }

    public static TransactionResult failed(Status status) {
        return new TransactionResult(status, null, -1L, -1L);
    }
}
