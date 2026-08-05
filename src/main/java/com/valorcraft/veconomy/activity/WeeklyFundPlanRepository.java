package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;

/**
 * Репозиторий планов недельного фонда (таблица {@code weekly_fund_plans}).
 * План пишется один раз при закрытии недели (идемпотентно) и только читается далее.
 */
public final class WeeklyFundPlanRepository {

    private static final String COLUMNS = "week_id, fund_amount, base_fund_amount, economy_coefficient_bps, "
            + "money_supply, supply_per_eligible, target_supply_per_eligible, eligible_players, "
            + "total_points, total_share, remainder_amount, payout_status, planned_at, auto_payout_at, paid_at";

    public Optional<WeeklyFundPlanRow> find(Connection connection, String weekId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM weekly_fund_plans WHERE week_id = ?")) {
            statement.setString(1, weekId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения плана недели " + weekId, e);
        }
    }

    /** Записать план. Идемпотентно: повторная запись той же недели игнорируется. */
    public void insert(Connection connection, DatabaseManager.Dialect dialect, WeeklyFundPlanRow row) {
        String sql = dialect == DatabaseManager.Dialect.MYSQL
                ? "INSERT IGNORE INTO weekly_fund_plans (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                : "INSERT OR IGNORE INTO weekly_fund_plans (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, row.weekId());
            statement.setLong(2, row.fundAmount());
            statement.setLong(3, row.baseFundAmount());
            statement.setLong(4, row.economyCoefficientBps());
            statement.setLong(5, row.moneySupply());
            statement.setLong(6, row.supplyPerEligible());
            statement.setLong(7, row.targetSupplyPerEligible());
            statement.setInt(8, row.eligiblePlayers());
            statement.setLong(9, row.totalPoints());
            statement.setLong(10, row.totalShare());
            statement.setLong(11, row.remainderAmount());
            statement.setString(12, row.payoutStatus());
            statement.setLong(13, row.plannedAt());
            setNullableLong(statement, 14, row.autoPayoutAt());
            setNullableLong(statement, 15, row.paidAt());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка записи плана недели " + row.weekId(), e);
        }
    }

    /** Отметить план завершённым (все игроки + казна получили суммы). */
    public void markPaid(Connection connection, String weekId, long paidAt) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE weekly_fund_plans SET payout_status = ?, paid_at = ? WHERE week_id = ?")) {
            statement.setString(1, WeeklyFundPlanRow.STATUS_PAID);
            statement.setLong(2, paidAt);
            statement.setString(3, weekId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка отметки плана недели " + weekId, e);
        }
    }

    private static WeeklyFundPlanRow map(ResultSet rs) throws SQLException {
        return new WeeklyFundPlanRow(
                rs.getString("week_id"),
                rs.getLong("fund_amount"),
                rs.getLong("base_fund_amount"),
                rs.getLong("economy_coefficient_bps"),
                rs.getLong("money_supply"),
                rs.getLong("supply_per_eligible"),
                rs.getLong("target_supply_per_eligible"),
                rs.getInt("eligible_players"),
                rs.getLong("total_points"),
                rs.getLong("total_share"),
                rs.getLong("remainder_amount"),
                rs.getString("payout_status"),
                rs.getLong("planned_at"),
                rs.getObject("auto_payout_at") == null ? null : rs.getLong("auto_payout_at"),
                rs.getObject("paid_at") == null ? null : rs.getLong("paid_at"));
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
