package com.valorcraft.veconomy.capability;

import com.valorcraft.veconomy.api.IEconomyCapability;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.util.INBTSerializable;

import java.util.UUID;

/** Реализация capability баланса. */
public class EconomyCapability implements IEconomyCapability, INBTSerializable<CompoundTag> {

    public static final String NBT_BALANCE = "Balance";
    public static final String NBT_PLAYER_UUID = "PlayerUUID";
    public static final String NBT_INITIALIZED = "Initialized";

    private double balance;
    private UUID playerUUID;
    private boolean initialized;

    @Override
    public double getBalance() {
        return balance;
    }

    @Override
    public void setBalance(double balance) {
        this.balance = balance;
    }

    @Override
    public double addBalance(double amount) {
        this.balance += amount;
        return this.balance;
    }

    @Override
    public UUID getPlayerUUID() {
        return playerUUID;
    }

    @Override
    public void setPlayerUUID(UUID playerUUID) {
        this.playerUUID = playerUUID;
    }

    /** Флаг «баланс уже инициализирован» (чтобы выдавать стартовый баланс только новым игрокам). */
    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    @Override
    public void copyFrom(IEconomyCapability other) {
        if (!(other instanceof EconomyCapability source)) {
            return;
        }
        this.balance = source.balance;
        this.initialized = source.initialized;
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble(NBT_BALANCE, balance);
        if (playerUUID != null) {
            tag.putUUID(NBT_PLAYER_UUID, playerUUID);
        }
        tag.putBoolean(NBT_INITIALIZED, initialized);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        this.balance = nbt.getDouble(NBT_BALANCE);
        this.playerUUID = nbt.hasUUID(NBT_PLAYER_UUID) ? nbt.getUUID(NBT_PLAYER_UUID) : null;
        this.initialized = nbt.getBoolean(NBT_INITIALIZED);
    }
}
