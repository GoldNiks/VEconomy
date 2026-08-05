package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Долговечное состояние остатка недельного фонда (таблица {@code weekly_fund_treasury}).
 * <p>
 * Остаток фиксируется в статусе {@link #STATUS_PENDING} ДО попытки перевода в казну и лишь
 * затем помечается {@link #STATUS_PAID} после успешного перевода. Так долг казне переживает
 * сбой на любом шаге: если перевод или отметка {@code PAID} не прошли, статус остаётся
 * {@code PENDING}, и очередь недельного фонда не продвинется мимо этой недели.
 */
public final class WeeklyTreasuryRepository {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAID = "PAID";

    private static final String COLUMNS = "week_id, remainder_amount, treasury_status, transaction_id, updated_at";

    /** Задолженность казне за неделю: есть строка не в статусе {@code PAID}. */
    public boolean hasPending(Connection connection, String weekId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM weekly_fund_treasury WHERE week_id = ? AND treasury_status <> ? LIMIT 1")) {
            statement.setString(1, weekId);
            statement.setString(2, STATUS_PAID);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка проверки остатка недели " + weekId, e);
        }
    }

    /** Зафиксировать долг: вставить/обновить строку недели в статусе {@code PENDING}. */
    public void upsertPending(Connection connection, DatabaseManager.Dialect dialect, String weekId,
                              long remainderAmount, long updatedAt) {
        String sql;
        if (dialect == DatabaseManager.Dialect.MYSQL) {
            sql = "INSERT INTO weekly_fund_treasury (" + COLUMNS + ") VALUES (?, ?, ?, NULL, ?) "
                    + "ON DUPLICATE KEY UPDATE remainder_amount = VALUES(remainder_amount), "
                    + "treasury_status = ?, transaction_id = NULL, updated_at = VALUES(updated_at)";
        } else {
            sql = "INSERT OR REPLACE INTO weekly_fund_treasury (" + COLUMNS + ") VALUES (?, ?, ?, NULL, ?)";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, weekId);
            statement.setLong(2, remainderAmount);
            statement.setString(3, STATUS_PENDING);
            statement.setLong(4, updatedAt);
            if (dialect == DatabaseManager.Dialect.MYSQL) {
                statement.setString(5, STATUS_PENDING);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка записи остатка недели " + weekId, e);
        }
    }

    /** Отметить долг выплаченным и сохранить идентификатор транзакции перевода. */
    public void markPaid(Connection connection, String weekId, String transactionId, long updatedAt) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE weekly_fund_treasury SET treasury_status = ?, transaction_id = ?, updated_at = ? "
                        + "WHERE week_id = ?")) {
            statement.setString(1, STATUS_PAID);
            statement.setString(2, transactionId);
            statement.setLong(3, updatedAt);
            statement.setString(4, weekId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка отметки выплаты остатка недели " + weekId, e);
        }
    }

    /** Остаток, задолженный казне за неделю (актуальный, в статусе PENDING). */
    public long pendingAmount(Connection connection, String weekId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT remainder_amount FROM weekly_fund_treasury "
                        + "WHERE week_id = ? AND treasury_status = ?")) {
            statement.setString(1, weekId);
            statement.setString(2, STATUS_PENDING);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения остатка недели " + weekId, e);
        }
    }
}