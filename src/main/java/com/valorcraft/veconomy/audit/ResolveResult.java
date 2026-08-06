package com.valorcraft.veconomy.audit;

/**
 * Структурированный результат обработки аудит-события ({@code resolve}).
 * Отличает «события нет» от «это не сигнал» и «уже обработано»: повторный
 * resolve НЕ трогает resolved_at/resolved_by/note — база не перезаписывает
 * уже принятое решение.
 */
public record ResolveResult(Status status) {

    public enum Status {
        /** Событие было открытым сигналом и успешно обработано. */
        SUCCESS,
        /** Событие с таким id не существует. */
        NOT_FOUND,
        /** Событие есть, но это не сигнал подозрительной активности (severity < SUSPICIOUS). */
        NOT_SUSPICIOUS,
        /** Сигнал уже обработан (RESOLVED/DISMISSED) — повторно не перезаписывается. */
        ALREADY_REVIEWED,
        /** Ошибка базы данных. */
        DATABASE_ERROR
    }

    public boolean success() {
        return status == Status.SUCCESS;
    }
}
