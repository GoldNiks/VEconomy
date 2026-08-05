package com.valorcraft.veconomy.activity;

import net.minecraft.resources.ResourceLocation;

/**
 * Условие ADVANCEMENT: личный прогресс игрока по конкретному advancement.
 * Проверяется живой прогресс конкретного игрока (не команды и не командные
 * награды FTB Quests). Для офлайн-игрока проверка недоступна — её выполняет
 * администратор командой {@code check} при следующем входе.
 */
public final class AdvancementCondition implements MilestoneCondition {

    @Override
    public MilestoneCheckResult check(MilestoneCheckContext context, MilestoneDefinition definition) {
        String value = definition.requirement("advancement");
        if (value == null) {
            return MilestoneCheckResult.unavailable("admin.milestone.reason.badConfig");
        }
        ResourceLocation advancementId = ResourceLocation.tryParse(value);
        if (advancementId == null) {
            return MilestoneCheckResult.unavailable("admin.milestone.reason.badConfig");
        }
        var done = context.advancementDone(advancementId);
        if (done.isEmpty()) {
            // Игрок офлайн: живой прогресс недоступен; проверку выполнит админ при входе.
            return MilestoneCheckResult.unavailable("admin.milestone.reason.offline");
        }
        return done.get()
                ? MilestoneCheckResult.met()
                : MilestoneCheckResult.notMet(null);
    }
}
