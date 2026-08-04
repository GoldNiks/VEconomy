package com.valorcraft.veconomy;

import com.valorcraft.veconomy.config.EconomyConfig;
import com.valorcraft.veconomy.network.ModMessages;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * VEconomy — ядро виртуальной валюты (Forge 1.20.1).
 * Чистый баланс игроков + команды + Java API + события транзакций.
 */
@Mod(VEconomyMod.MODID)
public final class VEconomyMod {

    public static final String MODID = "economy_core";
    public static final String MOD_NAME = "VEconomy";
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    public VEconomyMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, EconomyConfig.SPEC, "economy-core.toml");
        ModMessages.register();
    }
}
