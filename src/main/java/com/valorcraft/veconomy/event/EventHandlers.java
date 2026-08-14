package com.valorcraft.veconomy.event;

import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.command.EconomyAdminCommand;
import com.valorcraft.veconomy.command.InternalCommand;
import com.valorcraft.veconomy.command.MoneyCommand;
import com.valorcraft.veconomy.command.PayCommand;
import com.valorcraft.veconomy.config.EconomyConfig;
import com.valorcraft.veconomy.persistence.LegacyImporter;
import com.valorcraft.veconomy.util.MessageService;
import com.valorcraft.veconomy.util.ServerHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;

/** Серверные обработчики: запуск БД, импорт legacy, команды, логин, остановка. */
@Mod.EventBusSubscriber(modid = VEconomyMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EventHandlers {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MoneyCommand.register(event.getDispatcher());
        PayCommand.register(event.getDispatcher());
        EconomyAdminCommand.register(event.getDispatcher());
        InternalCommand.register(event.getDispatcher());
        VEconomyMod.LOGGER.info("Команды экономики зарегистрированы");
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerHolder.set(server);
        Path worldPath = server.getWorldPath(LevelResource.ROOT);
        var settings = EconomyConfig.toSettings();
        Path databasePath = worldPath.resolve(settings.databaseFile);

        EconomyCore.start(databasePath, settings);

        // Одноразовый импорт устаревшего хранилища (balances.json) в новую базу.
        LegacyImporter.importIfPresent(EconomyCore.database(), worldPath.resolve("economy"), settings);

        VEconomyMod.LOGGER.info("Экономика инициализирована, схема БД v{}",
                EconomyCore.database().schemaVersion());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String clientLanguage;
        try {
            clientLanguage = player.getLanguage();
        } catch (Throwable t) {
            clientLanguage = "<unavailable: " + t.getClass().getSimpleName() + ">";
        }
        VEconomyMod.LOGGER.info("Язык игрока {} ({}): clientLanguage={}, locale={}",
                player.getGameProfile().getName(), player.getUUID(), clientLanguage,
                MessageService.normalizeLocale(clientLanguage));
        if (EconomyCore.isStarted()) {
            EconomyCore.accounts().createOrTouch(player.getUUID(), player.getGameProfile().getName());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (EconomyCore.isStarted()) {
            EconomyCore.shutdown();
        }
        ServerHolder.clear();
    }

    private EventHandlers() {}
}
