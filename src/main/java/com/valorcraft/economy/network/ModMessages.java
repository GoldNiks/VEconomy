package com.valorcraft.economy.network;

import com.valorcraft.economy.EconomyCoreMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/** Канал сети мода (кодировщик: main). */
public final class ModMessages {

    private static final String PROTOCOL_VERSION = "1";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(EconomyCoreMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, SyncBalancePacket.class,
                SyncBalancePacket::encode, SyncBalancePacket::decode, SyncBalancePacket::handle);
    }

    public static void sendToPlayer(Object message, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    private ModMessages() {}
}
