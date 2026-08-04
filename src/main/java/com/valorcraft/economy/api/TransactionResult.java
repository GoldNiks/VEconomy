package com.valorcraft.economy.api;

/** Результат транзакции через {@link EconomyAPI}. */
public enum TransactionResult {
    /** Транзакция успешно выполнена. */
    SUCCESS,
    /** Недостаточно средств (и отрицательный баланс запрещён конфигом). */
    INSUFFICIENT_FUNDS,
    /** Ошибка: неверные аргументы, отменено событием Pre, capability недоступна и т.п. */
    ERROR
}
