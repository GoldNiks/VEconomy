package com.valorcraft.veconomy.audit;

import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.economy.TreasuryService;
import com.valorcraft.veconomy.persistence.AccountRepository;
import com.valorcraft.veconomy.persistence.AccountRow;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.TransactionRepository;
import com.valorcraft.veconomy.persistence.EscrowRepository;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Статистика экономики для команды {@code /economy admin stats}.
 * Средний/медианный баланс считаются по всем личным аккаунтам.
 */
public final class EconomyStatistics {

    private final DatabaseManager database;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final EscrowRepository escrow;

    public EconomyStatistics(DatabaseManager database, AccountRepository accounts,
                             TransactionRepository transactions, EscrowRepository escrow) {
        this.database = database;
        this.accounts = accounts;
        this.transactions = transactions;
        this.escrow = escrow;
    }

    public Stats compute() {
        return database.inTransaction(connection -> {
            List<AccountRow> all = accounts.all(connection).stream()
                    .filter(row -> !row.playerId().equals(TreasuryService.TREASURY_UUID))
                    .toList();
            long escrowBalance = escrow.sumReserved(connection);
            // Денежная масса = все аккаунты + зарезервированный escrow (замороженные,
            // но не уничтоженные деньги). Сумма по аккаунтам без escrow «худела» бы при
            // резервировании, хотя деньги никуда не делись.
            long totalSupply = accounts.sumBalance(connection, false) + escrowBalance;
            long playerMoney = accounts.sumBalance(connection, true);
            long treasury = totalSupply - playerMoney - escrowBalance;

            long accountCount = all.size();
            List<Long> sorted = all.stream().map(AccountRow::balanceMinor)
                    .sorted(Comparator.naturalOrder()).toList();
            long median = median(sorted);
            long max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);

            long now = System.currentTimeMillis();
            long day = now - 24L * 3600_000L;
            long week = now - 7L * 24L * 3600_000L;

            long emissionDay = emissionSince(connection, day);
            long emissionWeek = emissionSince(connection, week);
            long emissionTotal = emissionSince(connection, 0);

            long transferCount = transactions.countByTypeSince(connection, TransactionType.PLAYER_TRANSFER, 0);
            long transferVolume = transactions.sumAmountByTypeSince(connection, TransactionType.PLAYER_TRANSFER, 0);

            return new Stats(totalSupply, playerMoney, treasury, escrowBalance, accountCount,
                    median, max, transactions.countAll(connection),
                    transferCount, transferVolume, emissionDay, emissionWeek, emissionTotal);
        });
    }

    private long emissionSince(java.sql.Connection connection, long sinceMillis) {
        long sum = 0;
        for (TransactionType type : List.of(
                TransactionType.MILESTONE_REWARD,
                TransactionType.WEEKLY_REWARD,
                TransactionType.QUEST_REWARD,
                TransactionType.ADMIN_DEPOSIT,
                TransactionType.LEGACY_IMPORT)) {
            sum += transactions.sumAmountByTypeSince(connection, type, sinceMillis);
        }
        return sum;
    }

    private static long median(List<Long> sorted) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }

    /** Итоговая статистика (все суммы — в минимальных единицах). */
    public record Stats(
            long totalMoneySupply,
            long playerMoney,
            long treasuryBalance,
            long escrowBalance,
            long accountCount,
            long medianBalance,
            long maxBalance,
            long transactionCount,
            long transferCount,
            long transferVolume,
            long emissionDay,
            long emissionWeek,
            long emissionTotal
    ) {
    }
}
