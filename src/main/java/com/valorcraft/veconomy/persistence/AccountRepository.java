package com.valorcraft.veconomy.persistence;

import com.valorcraft.veconomy.api.AccountStatus;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Репозиторий аккаунтов (таблица {@code accounts}). */
public class AccountRepository {

    public Optional<AccountRow> find(Connection connection, UUID playerId) {
        try (var statement = connection.prepareStatement(
                "SELECT player_uuid, last_known_name, balance_minor, status, created_at, updated_at, version "
                        + "FROM accounts WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения аккаунта " + playerId, e);
        }
    }

    public boolean exists(Connection connection, UUID playerId) {
        return find(connection, playerId).isPresent();
    }

    public void insert(Connection connection, AccountRow account) {
        try (var statement = connection.prepareStatement(
                "INSERT INTO accounts (player_uuid, last_known_name, balance_minor, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, account.playerId().toString());
            statement.setString(2, account.lastKnownName());
            statement.setLong(3, account.balanceMinor());
            statement.setString(4, account.status().name());
            statement.setLong(5, account.createdAt());
            statement.setLong(6, account.updatedAt());
            statement.setInt(7, account.version());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка создания аккаунта " + account.playerId(), e);
        }
    }

    /**
     * Оптимистичное обновление баланса. Меняет строку только если её version совпадает
     * с ожидаемой (защита от гонок), и увеличивает version. Возвращает true при успехе.
     */
    public boolean updateBalance(Connection connection, UUID playerId, long newBalance, int expectedVersion, long now) {
        try (var statement = connection.prepareStatement(
                "UPDATE accounts SET balance_minor = ?, updated_at = ?, version = version + 1 "
                        + "WHERE player_uuid = ? AND version = ?")) {
            statement.setLong(1, newBalance);
            statement.setLong(2, now);
            statement.setString(3, playerId.toString());
            statement.setInt(4, expectedVersion);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка обновления баланса " + playerId, e);
        }
    }

    public boolean updateName(Connection connection, UUID playerId, String name, long now) {
        try (var statement = connection.prepareStatement(
                "UPDATE accounts SET last_known_name = ?, updated_at = ? WHERE player_uuid = ?")) {
            statement.setString(1, name);
            statement.setLong(2, now);
            statement.setString(3, playerId.toString());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка обновления имени " + playerId, e);
        }
    }

    public boolean setStatus(Connection connection, UUID playerId, AccountStatus status, long now) {
        try (var statement = connection.prepareStatement(
                "UPDATE accounts SET status = ?, updated_at = ? WHERE player_uuid = ?")) {
            statement.setString(1, status.name());
            statement.setLong(2, now);
            statement.setString(3, playerId.toString());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка смены статуса аккаунта " + playerId, e);
        }
    }

    public List<AccountRow> all(Connection connection) {
        List<AccountRow> result = new ArrayList<>();
        try (var statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT player_uuid, last_known_name, balance_minor, status, created_at, updated_at, version FROM accounts")) {
            while (rs.next()) {
                result.add(map(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения списка аккаунтов", e);
        }
    }

    /**
     * Аккаунты ровно по конкретным идентификаторам — для {@code scanPlayer}: вместо
     * загрузки ВСЕХ аккаунтов граф анализируемых переводов подгружает только
     * участников с их возрастом/статусом. Пустое множество — пустой результат.
     */
    public List<AccountRow> findByIds(Connection connection, java.util.Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<UUID> unique = ids.stream().distinct().toList();
        List<AccountRow> result = new ArrayList<>();
        int batch = 0;
        while (batch < unique.size()) {
            int end = Math.min(unique.size(), batch + MAX_IN_PARAMETERS);
            List<UUID> slice = unique.subList(batch, end);
            StringBuilder sql = new StringBuilder(
                    "SELECT player_uuid, last_known_name, balance_minor, status, created_at, updated_at, version "
                            + "FROM accounts WHERE player_uuid IN (");
            for (int i = 0; i < slice.size(); i++) {
                sql.append('?');
                if (i < slice.size() - 1) {
                    sql.append(',');
                }
            }
            sql.append(')');
            try (var statement = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < slice.size(); i++) {
                    statement.setString(i + 1, slice.get(i).toString());
                }
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(map(rs));
                    }
                }
            } catch (SQLException e) {
                throw new DatabaseException("Ошибка чтения аккаунтов участников", e);
            }
            batch = end;
        }
        return result;
    }

    /** Максимум элементов в IN-списке (ниже порогов обоих диалектов). */
    private static final int MAX_IN_PARAMETERS = 200;

    public long count(Connection connection) {
        try (var statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM accounts")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка подсчёта аккаунтов", e);
        }
    }

    public long sumBalance(Connection connection, boolean excludeSystem) {
        String sql = excludeSystem
                ? "SELECT COALESCE(SUM(balance_minor), 0) FROM accounts WHERE status != 'SYSTEM'"
                : "SELECT COALESCE(SUM(balance_minor), 0) FROM accounts";
        try (var statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка подсчёта суммы балансов", e);
        }
    }

    private static AccountRow map(ResultSet rs) throws SQLException {
        return new AccountRow(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("last_known_name"),
                rs.getLong("balance_minor"),
                AccountStatus.valueOf(rs.getString("status")),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getInt("version"));
    }
}
