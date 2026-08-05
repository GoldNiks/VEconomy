package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.persistence.DatabaseManager;

import java.util.EnumMap;
import java.util.Map;

/**
 * Реестр обработчиков условий milestone: тип → {@link MilestoneCondition}.
 * Единственная точка связывания типов с обработчиками; события, команды и сервис
 * не содержат switch по типам.
 */
public final class MilestoneConditionRegistry {

    private final Map<MilestoneType, MilestoneCondition> conditions = new EnumMap<>(MilestoneType.class);

    public MilestoneConditionRegistry(ActivityService activity, DatabaseManager database,
                                      DimensionVisitRepository visits) {
        conditions.put(MilestoneType.PLAYTIME, new PlaytimeCondition(activity));
        conditions.put(MilestoneType.ADVANCEMENT, new AdvancementCondition());
        conditions.put(MilestoneType.DIMENSION_VISIT, new DimensionVisitCondition(database, visits));
        conditions.put(MilestoneType.EXTERNAL, new ExternalCondition());
    }

    /** Обработчик условия для типа (все четыре типа всегда зарегистрированы). */
    public MilestoneCondition condition(MilestoneType type) {
        return conditions.get(type);
    }
}
