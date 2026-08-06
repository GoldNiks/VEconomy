package com.valorcraft.veconomy.event;

import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.activity.MilestoneType;
import com.valorcraft.veconomy.activity.ServerMilestoneCheckContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Игровые события, закрывающие милстоуны ADVANCEMENT и DIMENSION_VISIT.
 * ADVANCEMENT обрабатывается на серверной стороне (AdvancementEarnEvent отдаёт
 * живой прогресс игрока); DIMENSION_VISIT записывает факт входа в измерение
 * и при входе в игру, и при смене измерения — запись идемпотентна, повторный
 * вход награду не дублирует.
 */
@Mod.EventBusSubscriber(modid = VEconomyMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MilestoneHandlers {

    private MilestoneHandlers() {}

    @SubscribeEvent
    public static void onAdvancementEarned(AdvancementEvent.AdvancementEarnEvent event) {
        if (!EconomyCore.isStarted() || event.getAdvancement() == null
                || event.getAdvancement().getId() == null) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        EconomyCore.milestones().grantForEvent(player.getUUID(),
                MilestoneType.ADVANCEMENT, ServerMilestoneCheckContext.of(player));
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!EconomyCore.isStarted() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // Вход — тоже посещение: измерение, в котором игрок появился, должно
        // закрывать DIMENSION_VISIT-милстоуны без смены измерения.
        recordAndGrantDimension(player, player.level().dimension().location().toString());
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!EconomyCore.isStarted() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        recordAndGrantDimension(player, event.getTo().location().toString());
    }

    private static void recordAndGrantDimension(ServerPlayer player, String dimension) {
        EconomyCore.milestones().recordDimensionVisit(player.getUUID(), dimension);
        EconomyCore.milestones().grantForEvent(player.getUUID(),
                MilestoneType.DIMENSION_VISIT, ServerMilestoneCheckContext.of(player));
    }
}
