package com.valorcraft.veconomy.integration.permissions;

import com.valorcraft.veconomy.VEconomyMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Мост прав доступа для команд экономики.
 * <p>
 * Порядок проверки узла {@code veconomy.*}: если установлен LuckPerms — запрашиваем у него;
 * иначе если установлен FTB Ranks — у него; иначе (или если узел не задан) используем
 * классический уровень оператора {@code opLevelFallback}.
 * <p>
 * Моды-провайдеры прав вызываются только через загрузку отдельного хука-класса после
 * проверки {@link ModList#isLoaded}, чтобы не вызывать {@code NoClassDefFoundError},
 * когда провайдер не установлен.
 */
public final class PermissionBridge {

    private PermissionBridge() {}

    /**
     * Разрешена ли команда источником {@code source}.
     *
     * @param source          командный контекст
     * @param node            узел прав, например {@code veconomy.command.admin}
     * @param opLevelFallback уровень оператора для фолбэка (0-4)
     */
    public static boolean has(CommandSourceStack source, String node, int opLevelFallback) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            // Консоль/командный блок считаются полноправными.
            return source.hasPermission(opLevelFallback);
        }
        Boolean granted = checkProvider(player, node);
        if (granted != null) {
            return granted;
        }
        return player.hasPermissions(opLevelFallback);
    }

    private static Boolean checkProvider(ServerPlayer player, String node) {
        ModList mods = ModList.get();
        if (mods.isLoaded("luckperms")) {
            return LuckPermsHook.hasPermission(player, node);
        }
        if (mods.isLoaded("ftbranks")) {
            return FTBRanksHook.hasPermission(player, node);
        }
        return null;
    }

    /** Хук LuckPerms — загружается только когда установлен LuckPerms. */
    private static final class LuckPermsHook {
        private LuckPermsHook() {}

        static Boolean hasPermission(ServerPlayer player, String node) {
            try {
                var api = net.luckperms.api.LuckPermsProvider.get();
                var user = api.getUserManager().getUser(player.getUUID());
                if (user == null) {
                    return null;
                }
                return user.getCachedData().getPermissionData().checkPermission(node).asBoolean();
            } catch (Exception e) {
                VEconomyMod.LOGGER.warn("Не удалось проверить право {} через LuckPerms: {}", node, e.toString());
                return null;
            }
        }
    }

    /** Хук FTB Ranks — загружается только когда установлен FTB Ranks. */
    private static final class FTBRanksHook {
        private FTBRanksHook() {}

        static Boolean hasPermission(ServerPlayer player, String node) {
            try {
                var value = dev.ftb.mods.ftbranks.api.FTBRanksAPI.getPermissionValue(player, node);
                if (value.isEmpty()) {
                    return null;
                }
                return value.asBooleanOrFalse();
            } catch (Exception e) {
                VEconomyMod.LOGGER.warn("Не удалось проверить право {} через FTB Ranks: {}", node, e.toString());
                return null;
            }
        }
    }
}
