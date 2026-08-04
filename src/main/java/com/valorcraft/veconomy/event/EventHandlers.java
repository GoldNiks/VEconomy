package com.valorcraft.veconomy.event;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.IEconomyCapability;
import com.valorcraft.veconomy.capability.EconomyCapabilities;
import com.valorcraft.veconomy.capability.EconomyCapability;
import com.valorcraft.veconomy.capability.EconomyCapabilityProvider;
import com.valorcraft.veconomy.command.EconomyCommand;
import com.valorcraft.veconomy.config.EconomyConfig;
import com.valorcraft.veconomy.network.EconomySync;
import com.valorcraft.veconomy.storage.BalanceStorage;
import com.valorcraft.veconomy.storage.TransactionLogger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Серверные обработчики: capability, логин/логаут, смерть, команды, автосейв. */
@Mod.EventBusSubscriber(modid = VEconomyMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EventHandlers {

    /** 5 минут в тиках. */
    private static final int AUTOSAVE_INTERVAL = 20 * 60 * 5;

    private static int ticksSinceSave;

    @SubscribeEvent
    public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.register(IEconomyCapability.class);
    }

    @SubscribeEvent
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            event.addCapability(new ResourceLocation(VEconomyMod.MODID, "economy"),
                    new EconomyCapabilityProvider());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        player.getCapability(EconomyCapabilities.ECONOMY_CAPABILITY).ifPresent(cap -> {
            cap.setPlayerUUID(player.getUUID());
            if (!isInitialized(cap)) {
                cap.setBalance(EconomyConfig.STARTING_BALANCE.get());
                markInitialized(cap);
            }
            BalanceStorage.put(player.getUUID(), cap.getBalance());
            EconomySync.send(player);
            VEconomyMod.LOGGER.debug("Баланс игрока {} при логине: {}",
                    player.getGameProfile().getName(), cap.getBalance());
        });
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        flushAndSave(player);
        BalanceStorage.remove(player.getUUID());
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        player.getCapability(EconomyCapabilities.ECONOMY_CAPABILITY).ifPresent(current ->
                event.getOriginal().getCapability(EconomyCapabilities.ECONOMY_CAPABILITY).ifPresent(original -> {
                    if (EconomyConfig.DEATH_RESET.get()) {
                        current.setBalance(EconomyConfig.STARTING_BALANCE.get());
                        markInitialized(current);
                    } else {
                        current.copyFrom(original);
                        current.setPlayerUUID(player.getUUID());
                    }
                    BalanceStorage.put(player.getUUID(), current.getBalance());
                    VEconomyMod.LOGGER.debug("Баланс игрока {} после смерти: {}",
                            player.getGameProfile().getName(), current.getBalance());
                }));
        EconomySync.send(player);
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EconomySync.send(player);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        EconomyCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++ticksSinceSave < AUTOSAVE_INTERVAL) {
            return;
        }
        ticksSinceSave = 0;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            flushAndSave(player);
        }
        TransactionLogger.logLine("Автосейв балансов (" +
                event.getServer().getPlayerList().getPlayers().size() + " игроков)");
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        TransactionLogger.init(event.getServer().getServerDirectory().toPath().resolve("logs"));
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            flushAndSave(player);
        }
        TransactionLogger.close();
    }

    /** Записать кеш в NBT capability игрока и сохранить .dat всех онлайн-игроков. */
    private static void flushAndSave(ServerPlayer player) {
        player.getCapability(EconomyCapabilities.ECONOMY_CAPABILITY).ifPresent(cap -> {
            cap.setPlayerUUID(player.getUUID());
            cap.setBalance(BalanceStorage.get(player.getUUID()));
            if (!isInitialized(cap)) {
                markInitialized(cap);
            }
        });
        if (player.server != null) {
            player.server.getPlayerList().saveAll();
        }
    }

    private static boolean isInitialized(IEconomyCapability cap) {
        return cap instanceof EconomyCapability eco && eco.isInitialized();
    }

    private static void markInitialized(IEconomyCapability cap) {
        if (cap instanceof EconomyCapability eco) {
            eco.setInitialized(true);
        }
    }

    private EventHandlers() {}
}
