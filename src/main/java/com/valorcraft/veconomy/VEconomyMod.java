package com.valorcraft.veconomy;

import com.valorcraft.veconomy.config.EconomyConfig;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * VEconomy — серверное ядро цифровой экономики (Forge 1.20.1).
 * <p>
 * Личный денежный баланс привязан к UUID игрока. Деньги хранятся в минимальных
 * единицах (long), все изменения атомарны и записываются в журнал операций в SQLite.
 * Никаких предметов-монет, магазинов и клиентских интерфейсов — только сервер, команды
 * и безопасный Java API.
 */
@Mod(VEconomyMod.MODID)
public final class VEconomyMod {

    public static final String MODID = "economy_core";
    public static final String MOD_NAME = "VEconomy";

    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    public VEconomyMod() {
        EconomyConfig.register();

        // Опциональные интеграции. Классы загружаются только при наличии мода на сервере.
        if (ModList.get().isLoaded("ftbquests")) {
            com.valorcraft.veconomy.integration.ftbquests.FTBQuestsIntegration.register();
        }
        // KubeJS-плагин подхватывается автоматически через kubejs.plugins.txt.

        VEconomyMod.LOGGER.info("VEconomy загружается (серверное ядро экономики)");
    }
}
