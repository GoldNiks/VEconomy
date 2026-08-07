package com.valorcraft.veconomy.api;

/**
 * Результат чтения эскроу-записи. В отличие от {@code Optional<EscrowSnapshot>}
 * различает «записи нет» и «ошибку базы данных»: контракт чтения не прячет сбой
 * БД за пустым значением, а даёт вызывающему явный сигнал {@link Status#DATABASE_ERROR}.
 */
public record EscrowLookupResult(Status status, EscrowSnapshot snapshot) {

    public enum Status {
        /** Запись найдена. */
        FOUND,
        /** Записи с таким referenceId нет. */
        NOT_FOUND,
        /** Ошибка базы данных при чтении (повторить позже). */
        DATABASE_ERROR
    }

    public static EscrowLookupResult found(EscrowSnapshot snapshot) {
        return new EscrowLookupResult(Status.FOUND, snapshot);
    }

    public static EscrowLookupResult notFound() {
        return new EscrowLookupResult(Status.NOT_FOUND, null);
    }

    public static EscrowLookupResult databaseError() {
        return new EscrowLookupResult(Status.DATABASE_ERROR, null);
    }

    /** Удобный доступ к снимку (ничего не возвращает без {@link Status#FOUND}). */
    public EscrowSnapshot snapshotOrNull() {
        return status == Status.FOUND ? snapshot : null;
    }
}