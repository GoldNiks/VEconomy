package com.valorcraft.economy.capability;

import com.valorcraft.economy.api.IEconomyCapability;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;

/** Реестр capability экономики. */
public final class EconomyCapabilities {

    /** Capability виртуального баланса игрока (persistent, сохраняется в .dat игрока). */
    public static final Capability<IEconomyCapability> ECONOMY_CAPABILITY =
            CapabilityManager.get(new CapabilityToken<IEconomyCapability>() {});

    private EconomyCapabilities() {}
}
