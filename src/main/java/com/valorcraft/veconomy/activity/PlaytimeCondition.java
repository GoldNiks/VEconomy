package com.valorcraft.veconomy.activity;

/** Условие PLAYTIME: достижение порога активного времени (данные базы + живая сессия). */
public final class PlaytimeCondition implements MilestoneCondition {

    private final ActivityService activity;

    public PlaytimeCondition(ActivityService activity) {
        this.activity = activity;
    }

    @Override
    public MilestoneCheckResult check(MilestoneCheckContext context, MilestoneDefinition definition) {
        long threshold = parseSeconds(definition);
        if (threshold <= 0) {
            return MilestoneCheckResult.unavailable("admin.milestone.reason.badConfig");
        }
        long totalActive = activity.activeSecondsTotal(context.playerId());
        return totalActive >= threshold
                ? MilestoneCheckResult.met()
                : MilestoneCheckResult.notMet(null);
    }

    static long parseSeconds(MilestoneDefinition definition) {
        String value = definition.requirement("activeSeconds");
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
