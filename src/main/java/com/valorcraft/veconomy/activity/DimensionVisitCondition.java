package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;

/**
 * Условие DIMENSION_VISIT: личное посещение измерения, сохранённое в
 * {@code dimension_visits} при смене измерения. Доказательством является сам
 * факт входа (не предметы в инвентаре); повторный вход ничего не меняет.
 * Проверка работает и для офлайн-игроков (данные базы).
 */
public final class DimensionVisitCondition implements MilestoneCondition {

    private final DatabaseManager database;
    private final DimensionVisitRepository visits;

    public DimensionVisitCondition(DatabaseManager database, DimensionVisitRepository visits) {
        this.database = database;
        this.visits = visits;
    }

    @Override
    public MilestoneCheckResult check(MilestoneCheckContext context, MilestoneDefinition definition) {
        String dimension = definition.requirement("dimension");
        if (dimension == null || dimension.isBlank()) {
            return MilestoneCheckResult.unavailable("admin.milestone.reason.badConfig");
        }
        try {
            boolean visited = database.inTransaction(connection ->
                    visits.find(connection, context.playerId(), dimension).isPresent());
            return visited
                    ? MilestoneCheckResult.met()
                    : MilestoneCheckResult.notMet(null);
        } catch (DatabaseException e) {
            return MilestoneCheckResult.unavailable("admin.milestone.reason.database");
        }
    }
}
