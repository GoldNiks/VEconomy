package com.valorcraft.veconomy.persistence;

/** Исключение при работе с базой данных. Всегда оборачивает SQLException или IO-ошибку. */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public DatabaseException(String message) {
        super(message);
    }
}
