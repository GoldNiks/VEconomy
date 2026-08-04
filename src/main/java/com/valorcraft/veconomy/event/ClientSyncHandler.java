package com.valorcraft.veconomy.event;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.capability.EconomyCapabilities;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Клиентский приём баланса. Если пакет пришёл раньше, чем клиент успел создать игрока,
 * значение откладывается в pending-кеш и применяется в ближайшем клиентском тике.
 */
@Mod.EventBusSubscriber(modid = VEconomyMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientSyncHandler {

    private static final Map<UUID, Double> PENDING = new HashMap<>();

    public static void apply(UUID playerUUID, double balance) {
        Player player = Minecraft.getInstance().player;
        if (player != null && player.getUUID().equals(playerUUID)) {
            player.getCapability(EconomyCapabilities.ECONOMY_CAPABILITY)
                    .ifPresent(cap -> cap.setBalance(balance));
        } else {
            PENDING.put(playerUUID, balance);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null || PENDING.isEmpty()) {
            return;
        }
        Double balance = PENDING.remove(player.getUUID());
        if (balance != null) {
            player.getCapability(EconomyCapabilities.ECONOMY_CAPABILITY)
                    .ifPresent(cap -> cap.setBalance(balance));
        }
    }

    private ClientSyncHandler() {}
}
