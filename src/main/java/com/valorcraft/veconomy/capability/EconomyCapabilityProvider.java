package com.valorcraft.veconomy.capability;

import com.valorcraft.veconomy.api.IEconomyCapability;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Провайдер capability баланса. Данные сохраняются в .dat игрока через
 * INBTSerializable (Forge вызывает serializeNBT/deserializeNBT автоматически).
 */
public class EconomyCapabilityProvider implements ICapabilityProvider, INBTSerializable<CompoundTag> {

    private final EconomyCapability instance = new EconomyCapability();
    private final LazyOptional<IEconomyCapability> lazy = LazyOptional.of(() -> instance);

    @NotNull
    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return cap == EconomyCapabilities.ECONOMY_CAPABILITY ? lazy.cast() : LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        return instance.serializeNBT();
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        instance.deserializeNBT(nbt);
    }
}
