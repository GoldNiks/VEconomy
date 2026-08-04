package com.valorcraft.veconomy.event;

import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.config.EconomySettings.MilestoneReward;
import com.valorcraft.veconomy.util.ServerHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Крючки Forge для учёта активности: движение игрока (AFK), чат, логин/выход и
 * периодические задачи (накопление счётчиков, сохранение, милстоуны, недельный фонд).
 */
@Mod.EventBusSubscriber(modid = VEconomyMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ActivityHandlers {

    private static int tickCounter;
    private static int lastPersistTick;

    private ActivityHandlers() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (!EconomyCore.isStarted()) {
            return;
        }
        EconomyCore.activity().onPlayerMove(player.getUUID(),
                player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(),
                player.level().dimension().location().toString());
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (EconomyCore.isStarted() && player != null) {
            EconomyCore.activity().onPlayerActive(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (EconomyCore.isStarted() && event.getEntity() instanceof ServerPlayer player) {
            EconomyCore.activity().onPlayerJoined(player.getUUID(),
                    player.level().dimension().location().toString());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (EconomyCore.isStarted() && event.getEntity() instanceof ServerPlayer player) {
            EconomyCore.activity().onPlayerLeft(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !EconomyCore.isStarted()) {
            return;
        }
        EconomySettings settings = EconomyCore.settings();
        tickCounter++;
        if (tickCounter % settings.activity.sampleIntervalTicks == 0) {
            EconomyCore.activity().sampleNow();
        }
        int persistTicks = settings.activity.persistIntervalSeconds * 20;
        if (tickCounter - lastPersistTick >= persistTicks) {
            lastPersistTick = tickCounter;
            onPersistInterval(settings);
        }
    }

    private static void onPersistInterval(EconomySettings settings) {
        EconomyCore.activity().persistAll();
        checkMilestones(settings);
        distributeWeeklyFund(settings);
    }

    private static void checkMilestones(EconomySettings settings) {
        if (!settings.milestones.enabled) {
            return;
        }
        MinecraftServer server = ServerHolder.get();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            List<MilestoneReward> granted = EconomyCore.milestones().checkPlayer(player.getUUID());
            if (settings.milestones.notify) {
                for (MilestoneReward reward : granted) {
                    player.sendSystemMessage(Component.translatable("notify.milestone.reward",
                            EconomyCore.formatter().format(reward.amountMinor()))
                            .withStyle(ChatFormatting.GOLD));
                }
            }
        }
    }

    private static void distributeWeeklyFund(EconomySettings settings) {
        Map<UUID, Long> payments = EconomyCore.weeklyFund().maybeDistribute();
        if (!settings.weeklyFund.notify || payments.isEmpty()) {
            return;
        }
        MinecraftServer server = ServerHolder.get();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Long amount = payments.get(player.getUUID());
            if (amount != null) {
                player.sendSystemMessage(Component.translatable("notify.weekly.reward",
                        EconomyCore.formatter().format(amount))
                        .withStyle(ChatFormatting.GOLD));
            }
        }
    }
}
