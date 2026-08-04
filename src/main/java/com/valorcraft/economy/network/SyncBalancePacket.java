package com.valorcraft.economy.network;

import com.valorcraft.economy.event.ClientSyncHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** S2C-пакет: синхронизация баланса игрока с клиентом (при логине, респауне и изменении). */
public class SyncBalancePacket {

    private final UUID playerUUID;
    private final double balance;

    public SyncBalancePacket(UUID playerUUID, double balance) {
        this.playerUUID = playerUUID;
        this.balance = balance;
    }

    public static void encode(SyncBalancePacket message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.playerUUID);
        buffer.writeDouble(message.balance);
    }

    public static SyncBalancePacket decode(FriendlyByteBuf buffer) {
        return new SyncBalancePacket(buffer.readUUID(), buffer.readDouble());
    }

    public static void handle(SyncBalancePacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getDirection().getReceptionSide() == LogicalSide.CLIENT) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> ClientSyncHandler.apply(message.playerUUID, message.balance));
            }
        });
        context.setPacketHandled(true);
    }
}
