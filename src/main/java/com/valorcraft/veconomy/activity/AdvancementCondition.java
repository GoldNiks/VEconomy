package com.valorcraft.veconomy.activity;

import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * Условие ADVANCEMENT: личный прогресс игрока по конкретному advancement.
 * Проверяется живой прогресс конкретного игрока (не команды и не командные
 * награды FTB Quests). Для офлайн-игрока проверка недоступна — её выполняет
 * администратор командой {@code check} при следующем входе.
 * <p>
 * Ошибки конфигурации отделены от невыполненного условия: отсутствующее или
 * некорректное значение требования, а также advancement, не зарегистрированный
 * на сервере, дают BAD_CONFIG — награда не выдаётся, администратор видит причину.
 */
public final class AdvancementCondition implements MilestoneCondition {

    @Override
    public MilestoneCheckResult check(MilestoneCheckContext context, MilestoneDefinition definition) {
        String value = definition.requirement("advancement");
        if (value == null) {
            return MilestoneCheckResult.badConfig("admin.milestone.reason.badConfig");
        }
        ResourceLocation advancementId = ResourceLocation.tryParse(value);
        if (advancementId == null) {
            return MilestoneCheckResult.badConfig("admin.milestone.reason.badConfig");
        }
        Optional<Boolean> registered = context.advancementRegistered(advancementId);
        if (registered.isEmpty()) {
            // Игрок офлайн: реестр и живой прогресс недоступны; проверку выполнит админ при входе.
            return MilestoneCheckResult.unavailable("admin.milestone.reason.offline");
        }
        if (!registered.get()) {
            // Синтаксически корректный id не зарегистрирован на сервере: конфигурация
            // ссылается на несуществующий advancement — это ошибка конфига, а не условие.
            return MilestoneCheckResult.badConfig("admin.milestone.reason.unregistered");
        }
        Optional<Boolean> done = context.advancementDone(advancementId);
        if (done.isEmpty()) {
            // Игрок офлайн: живой прогресс недоступен; проверку выполнит админ при входе.
            return MilestoneCheckResult.unavailable("admin.milestone.reason.offline");
        }
        return done.get()
                ? MilestoneCheckResult.met()
                : MilestoneCheckResult.notMet(null);
    }
}
