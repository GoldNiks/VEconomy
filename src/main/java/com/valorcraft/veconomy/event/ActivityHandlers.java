package com.valorcraft.veconomy.event;

import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.util.MessageService;
import com.valorcraft.veconomy.util.ServerHolder;
import com.valorcraft.veconomy.ui.EconomyComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;

/**
 * Крючки Forge для учёта активности: реальные действия игрока (блоки, предметы,
 * контейнеры, атаки, чат — сбрасывают AFK), существенное перемещение (анти-AFK),
 * логин/выход и периодические задачи (накопление счётчиков, сохранение, милстоуны,
 * недельный фонд).
 */
@Mod.EventBusSubscriber(modid = VEconomyMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ActivityHandlers {

    private static int tickCounter;
    private static int lastPersistTick;
    private static int lastAuditPruneTick;

    /** Очистка старых аудит-событий по политике удержания: раз в час, не на тике. */
    private static final int AUDIT_PRUNE_INTERVAL_TICKS = 20 * 60 * 60;

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
    public static void onCommand(CommandEvent event) {
        if (!EconomyCore.isStarted()) {
            return;
        }
        net.minecraft.commands.CommandSourceStack source =
                event.getParseResults().getContext().getSource();
        if (source != null && source.getEntity() instanceof ServerPlayer player) {
            markActive(player);
        }
    }

    @SubscribeEvent
    public static void onBreakBlock(BlockEvent.BreakEvent event) {
        if (EconomyCore.isStarted()) {
            markActive(event.getPlayer());
        }
    }

    @SubscribeEvent
    public static void onPlaceBlock(BlockEvent.EntityPlaceEvent event) {
        if (EconomyCore.isStarted() && event.getEntity() instanceof ServerPlayer player) {
            markActive(player);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (EconomyCore.isStarted()) {
            markActive(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (EconomyCore.isStarted()) {
            markActive(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (EconomyCore.isStarted()) {
            markActive(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (EconomyCore.isStarted() && event.getEntity() instanceof ServerPlayer player) {
            markActive(player);
        }
    }

    @SubscribeEvent
    public static void onDamageDealt(LivingDamageEvent event) {
        if (EconomyCore.isStarted() && event.getSource().getEntity() instanceof ServerPlayer player) {
            markActive(player);
        }
    }

    private static void markActive(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            EconomyCore.activity().onPlayerActive(serverPlayer.getUUID());
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
        if (tickCounter - lastAuditPruneTick >= AUDIT_PRUNE_INTERVAL_TICKS) {
            lastAuditPruneTick = tickCounter;
            // Лёгкая периодическая очистка аудита (DELETE по индексам severity/created_at);
            // полное сканирование сигналов выполняется только явной админ-командой.
            EconomyCore.audit().prune(
                    com.valorcraft.veconomy.config.AuditConfig.settings().retentionDays());
        }
    }

    private static void onPersistInterval(EconomySettings settings) {
        // Сначала пишем в базу активность по дням, и только потом распределяем фонд по
        // завершённой неделе: ротация читает снимок days-таблицы, поэтому без persist
        // последние минуты старой недели (воскресные часы онлайн-игроков) потерялись бы.
        // Начало новой недели при этом попадает в дни текущей недели и не влияет на план.
        // Ротация запускается только после подтверждённого сохранения: при ошибке базы
        // активность удерживается в памяти и не потеряется, а план недели останется
        // открытым до следующего успешного persist.
        if (EconomyCore.activity().persistAll()) {
            distributeWeeklyFund(settings);
        }
        checkMilestones(settings);
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
            // Уведомление о выдаче отправляет сам MilestoneService (по общему
            // флагу milestones.notify) — здесь только периодическая проверка.
            EconomyCore.milestones().checkPlayer(player.getUUID());
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
                player.sendSystemMessage(EconomyComponents.reward(
                        MessageService.text(player, "notify.weekly.reward"),
                        EconomyCore.formatter().format(amount)));
            }
        }
    }
}
