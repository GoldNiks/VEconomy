package com.valorcraft.veconomy.activity;

import java.util.Map;

/**
 * Один настраиваемый milestone. Определения PLAYTIME синтезируются из пар
 * {@code (секунды, награда)} конфига {@code milestones.rewards}; определения
 * ADVANCEMENT/DIMENSION_VISIT/EXTERNAL загружаются из
 * {@code config/VMods/VEconomy/veconomy-milestones.json} ({@link MilestoneConfig}).
 *
 * @param requirements обязательные требования типа: для PLAYTIME — {@code activeSeconds};
 *                     для ADVANCEMENT — {@code advancement} (ResourceLocation);
 *                     для DIMENSION_VISIT — {@code dimension} (ResourceLocation);
 *                     для EXTERNAL — не обязательны (необязательный {@code channel}).
 * @param message       необязательный текст уведомления игроку при выдаче (null — стандартное)
 */
public record MilestoneDefinition(
        String id,
        MilestoneType type,
        long amountMinor,
        boolean enabled,
        Map<String, String> requirements,
        String message) {

    /** Требование по ключу (null, если не задано). */
    public String requirement(String key) {
        return requirements.get(key);
    }
}
