package com.valorcraft.veconomy.activity;

/**
 * Обработчик условия milestone для конкретного типа. Тип связывается с обработчиком
 * в {@link MilestoneConditionRegistry}; события и команды не содержат switch по типам —
 * только {@code registry.condition(type).check(context, definition)}.
 */
public interface MilestoneCondition {

    /** Проверить условие. Не выдаёт деньги и не пишет claim. */
    MilestoneCheckResult check(MilestoneCheckContext context, MilestoneDefinition definition);
}
