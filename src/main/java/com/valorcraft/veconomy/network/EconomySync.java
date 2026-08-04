package com.valorcraft.veconomy.network;

import com.valorcraft.veconomy.capability.EconomyCapabilities;
import net.minecraft.server.level.ServerPlayer;

/** Отправка актуального баланса игроку на клиент. */
public final class EconomySync {

    public static void send(ServerPlayer player) {
        player.getCapability(EconomyCapabilities.ECONOMY_CAPABILITY)
                .ifPresent(cap -> ModMessages.sendToPlayer(
                        new SyncBalancePacket(player.getUUID(), cap.getBalance()), player));
    }

    private EconomySync() {}
}
