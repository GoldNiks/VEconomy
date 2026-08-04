package com.valorcraft.economy;

import com.valorcraft.economy.config.EconomyConfig;
import com.valorcraft.economy.network.ModMessages;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Aurum Core — ядро виртуальной валюты (Forge 1.20.1).
 * Чистый баланс игроков + команды + Java API + события транзакций.
 */
@Mod(EconomyCoreMod.MODID)
public final class EconomyCoreMod {

    public static final String MODID = "economy_core";
    public static final String MOD_NAME = "Aurum Core";
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    public EconomyCoreMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, EconomyConfig.SPEC, "economy-core.toml");
        ModMessages.register();
    }
}
