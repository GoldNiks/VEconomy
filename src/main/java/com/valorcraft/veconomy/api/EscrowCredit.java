package com.valorcraft.veconomy.api;

import java.util.UUID;

/**
 * Одна строка распределения средств из эскроу при расчёте (settle).
 * Сумма всех кредитов должна равняться зарезервированной сумме.
 */
public record EscrowCredit(UUID recipientId, long amount, String role) {
}