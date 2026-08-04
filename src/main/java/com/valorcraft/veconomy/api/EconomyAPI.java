package com.valorcraft.veconomy.api;

import com.valorcraft.veconomy.capability.EconomyCapabilities;
import com.valorcraft.veconomy.config.EconomyConfig;
import com.valorcraft.veconomy.network.EconomySync;
import com.valorcraft.veconomy.storage.BalanceStorage;
import com.valorcraft.veconomy.storage.TransactionLogger;
import com.valorcraft.veconomy.util.MoneyFormatter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;

import java.util.Objects;
import java.util.UUID;

/**
 * Главный публичный фасад экономики. Используется другими модами для чтения/изменения баланса.
 * <p>
 * Транзакции должны выполняться на логическом сервере (вызывайте в серверных обработчиках
 * событий или в командах). На клиенте баланс только читается (синхронизируется пакетом).
 * <p>
 * Каждая транзакция проходит через события {@link EconomyTransactionEvent} (Pre можно
 * отменить или изменить сумму), пишется в economy_transactions.log и синхронизируется с клиентом.
 */
public final class EconomyAPI {

    private EconomyAPI() {}

    /** Получить capability баланса игрока. Кидает исключение, если capability недоступна. */
    public static IEconomyCapability getCapability(Player player) {
        Objects.requireNonNull(player, "player");
        return player.getCapability(EconomyCapabilities.ECONOMY_CAPABILITY)
                .orElseThrow(() -> new IllegalStateException(
                        "Экономика недоступна для игрока " + player.getGameProfile().getName()));
    }

    /** Текущий баланс игрока. На сервере читается из кеша, на клиенте — из синхронизированной capability. */
    public static double getBalance(Player player) {
        if (player == null) {
            return 0.0;
        }
        if (player.level().isClientSide) {
            return getCapability(player).getBalance();
        }
        UUID uuid = player.getUUID();
        if (!BalanceStorage.contains(uuid)) {
            double balance = getCapability(player).getBalance();
            BalanceStorage.put(uuid, balance);
            return balance;
        }
        return BalanceStorage.get(uuid);
    }

    /** Зачислить сумму на баланс. Возвращает SUCCESS / ERROR. */
    public static TransactionResult deposit(Player player, double amount) {
        if (player == null || player.level().isClientSide || amount <= 0) {
            return TransactionResult.ERROR;
        }
        double value = MoneyFormatter.round(amount);

        EconomyTransactionEvent.Pre pre =
                new EconomyTransactionEvent.Pre(player, EconomyTransactionEvent.Type.DEPOSIT, value);
        if (MinecraftForge.EVENT_BUS.post(pre)) {
            return TransactionResult.ERROR;
        }
        value = MoneyFormatter.round(pre.getAmount());
        if (value <= 0) {
            return TransactionResult.ERROR;
        }

        double newBalance = getBalance(player) + value;
        applyBalance(player, newBalance);
        MinecraftForge.EVENT_BUS.post(
                new EconomyTransactionEvent.Post(player, EconomyTransactionEvent.Type.DEPOSIT, value, newBalance));
        logAndSync(player, "DEPOSIT", value, newBalance);
        return TransactionResult.SUCCESS;
    }

    /** Списать сумму с баланса. Возвращает SUCCESS / INSUFFICIENT_FUNDS / ERROR. */
    public static TransactionResult withdraw(Player player, double amount) {
        if (player == null || player.level().isClientSide || amount <= 0) {
            return TransactionResult.ERROR;
        }
        double value = MoneyFormatter.round(amount);

        EconomyTransactionEvent.Pre pre =
                new EconomyTransactionEvent.Pre(player, EconomyTransactionEvent.Type.WITHDRAW, value);
        if (MinecraftForge.EVENT_BUS.post(pre)) {
            return TransactionResult.ERROR;
        }
        value = MoneyFormatter.round(pre.getAmount());
        if (value <= 0) {
            return TransactionResult.ERROR;
        }

        double balance = getBalance(player);
        if (!EconomyConfig.ALLOW_NEGATIVE_BALANCE.get() && balance < value) {
            return TransactionResult.INSUFFICIENT_FUNDS;
        }

        double newBalance = balance - value;
        applyBalance(player, newBalance);
        MinecraftForge.EVENT_BUS.post(
                new EconomyTransactionEvent.Post(player, EconomyTransactionEvent.Type.WITHDRAW, value, newBalance));
        logAndSync(player, "WITHDRAW", value, newBalance);
        return TransactionResult.SUCCESS;
    }

    /**
     * Перевод между игроками. У отправителя списывается сумма + комиссия (transferTax),
     * получателю зачисляется чистая сумма. Возвращает SUCCESS / INSUFFICIENT_FUNDS / ERROR.
     */
    public static TransactionResult transfer(Player from, Player to, double amount) {
        if (from == null || to == null || from.level().isClientSide || amount <= 0 || from == to) {
            return TransactionResult.ERROR;
        }
        double value = MoneyFormatter.round(amount);

        EconomyTransactionEvent.Pre pre =
                new EconomyTransactionEvent.Pre(from, EconomyTransactionEvent.Type.TRANSFER, value);
        if (MinecraftForge.EVENT_BUS.post(pre)) {
            return TransactionResult.ERROR;
        }
        value = MoneyFormatter.round(pre.getAmount());
        if (value <= 0) {
            return TransactionResult.ERROR;
        }

        double tax = MoneyFormatter.round(value * EconomyConfig.TRANSFER_TAX.get() / 100.0);
        double debit = MoneyFormatter.round(value + tax);

        double fromBalance = getBalance(from);
        if (!EconomyConfig.ALLOW_NEGATIVE_BALANCE.get() && fromBalance < debit) {
            return TransactionResult.INSUFFICIENT_FUNDS;
        }

        double newFromBalance = fromBalance - debit;
        double newToBalance = getBalance(to) + value;
        applyBalance(from, newFromBalance);
        applyBalance(to, newToBalance);

        MinecraftForge.EVENT_BUS.post(
                new EconomyTransactionEvent.Post(from, EconomyTransactionEvent.Type.TRANSFER, debit, newFromBalance));
        MinecraftForge.EVENT_BUS.post(
                new EconomyTransactionEvent.Post(to, EconomyTransactionEvent.Type.DEPOSIT, value, newToBalance));
        logAndSync(from, "TRANSFER_OUT", debit, newFromBalance);
        logAndSync(to, "TRANSFER_IN", value, newToBalance);
        return TransactionResult.SUCCESS;
    }

    /**
     * Принудительно установить баланс (для админ-команд). Не генерирует события Pre/Post,
     * но логируется и синхронизируется с клиентом.
     */
    public static TransactionResult forceSet(Player player, double balance) {
        if (player == null || player.level().isClientSide) {
            return TransactionResult.ERROR;
        }
        double value = MoneyFormatter.round(balance);
        if (!EconomyConfig.ALLOW_NEGATIVE_BALANCE.get() && value < 0) {
            return TransactionResult.ERROR;
        }
        applyBalance(player, value);
        logAndSync(player, "FORCE_SET", value, value);
        return TransactionResult.SUCCESS;
    }

    /** Отформатировать сумму под символ и формат валюты из конфига. */
    public static String format(double amount) {
        return MoneyFormatter.format(amount);
    }

    private static void applyBalance(Player player, double newBalance) {
        getCapability(player).setBalance(newBalance);
        if (!player.level().isClientSide) {
            BalanceStorage.put(player.getUUID(), newBalance);
        }
    }

    private static void logAndSync(Player player, String action, double amount, double newBalance) {
        if (player instanceof ServerPlayer serverPlayer) {
            TransactionLogger.logTransaction(serverPlayer, action, amount, newBalance);
            EconomySync.send(serverPlayer);
        }
    }
}
