package com.valorcraft.economy.storage;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Серверный кеш балансов. Наполняется при логине игрока, обновляется при каждой
 * транзакции. Записывается в NBT игрока по PlayerLoggedOutEvent и раз в 5 минут (автосейв).
 */
public final class BalanceStorage {

    private static final Map<UUID, Double> CACHE = new ConcurrentHashMap<>();

    public static double get(UUID playerUUID) {
        return CACHE.getOrDefault(playerUUID, 0.0);
    }

    public static boolean contains(UUID playerUUID) {
        return CACHE.containsKey(playerUUID);
    }

    public static void put(UUID playerUUID, double balance) {
        CACHE.put(playerUUID, balance);
    }

    public static void remove(UUID playerUUID) {
        CACHE.remove(playerUUID);
    }

    public static Map<UUID, Double> snapshot() {
        return Collections.unmodifiableMap(CACHE);
    }

    private BalanceStorage() {}
}
