package com.valorcraft.veconomy.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Публичный безопасный API экономики для других модов (например, будущего аукциона).
 * <p>
 * Все суммы передаются в минимальных единицах валюты (long). Операции атомарны,
 * идемпотентны (через {@link TransactionContext#idempotencyKey()}) и всегда
 * сопровождаются записью в журнал. Изменять баланс в обход этого API нельзя.
 * <p>
 * Вызывайте методы на серверном потоке (в серверных обработчиках/командах).
 */
public interface EconomyApi {

    /** Текущий баланс игрока в минимальных единицах. 0, если аккаунта нет. */
    long getBalance(UUID playerId);

    /** Зачислить деньги на личный аккаунт. */
    TransactionResult deposit(UUID playerId, long amount, TransactionContext context);

    /** Списать деньги с личного аккаунта. */
    TransactionResult withdraw(UUID playerId, long amount, TransactionContext context);

    /** Перевести деньги между личными аккаунтами. */
    TransactionResult transfer(UUID senderId, UUID recipientId, long amount, TransactionContext context);

    /** Полная информация об аккаунте (если он существует). */
    Optional<BalanceSnapshot> getAccount(UUID playerId);

    /** Есть ли у игрока средств не меньше указанной суммы. */
    boolean has(UUID playerId, long amount);
}
