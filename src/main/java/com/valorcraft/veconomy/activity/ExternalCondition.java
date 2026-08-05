package com.valorcraft.veconomy.activity;

/**
 * Условие EXTERNAL: выдается только доверенными системами (KubeJS, консоль,
 * административные события). Автоматическая проверка невозможна — игрок никогда
 * не получает EXTERNAL-награду сам по себе (события и периодические проверки
 * получают NOT_AVAILABLE и ничего не начисляют).
 */
public final class ExternalCondition implements MilestoneCondition {

    @Override
    public MilestoneCheckResult check(MilestoneCheckContext context, MilestoneDefinition definition) {
        return MilestoneCheckResult.unavailable("admin.milestone.reason.external");
    }
}
