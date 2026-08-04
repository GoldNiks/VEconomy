package com.valorcraft.veconomy.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.AccountStatus;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.EconomySettings;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Одноразовый импорт устаревшего хранилища {@code <мир>/economy/balances.json}
 * (формат старого прототипа: {@code {"balances": {"<uuid>": <double>}}}).
 * <p>
 * Импорт выполняется только для пустой/новой базы и только один раз (флаг в таблице
 * {@code meta}). Суммы переводятся из double в минимальные единицы с округлением.
 * Каждая запись получает ledger-запись типа {@link TransactionType#LEGACY_IMPORT}.
 */
public final class LegacyImporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FLAG_KEY = "legacy_import_done";
    private static final String FILE_NAME = "balances.json";

    private LegacyImporter() {}

    /**
     * Попытаться импортировать старые балансы.
     *
     * @param legacyDir каталог, где лежит {@code balances.json} (обычно {@code <мир>/economy})
     * @return true, если импорт выполнен
     */
    public static boolean importIfPresent(DatabaseManager database, Path legacyDir, EconomySettings settings) {
        Path legacyFile = legacyDir.resolve(FILE_NAME);
        if (!Files.exists(legacyFile)) {
            return false;
        }
        return database.inTransaction(connection -> importAll(connection, legacyFile, settings, database.dialect()));
    }

    private static boolean importAll(Connection connection, Path legacyFile, EconomySettings settings,
                                     DatabaseManager.Dialect dialect) {
        try {
            if (metaGet(connection, FLAG_KEY) != null) {
                VEconomyMod.LOGGER.info("Импорт legacy-балансов уже выполнен ранее, пропуск");
                return false;
            }
            Map<UUID, Long> balances = parse(legacyFile, settings.decimalPlaces);
            if (balances.isEmpty()) {
                // Флаг НЕ ставим: пустой или повреждённый файл не должен навсегда
                // блокировать импорт после того, как в нём появятся реальные данные.
                VEconomyMod.LOGGER.info("Файл {} не содержит балансов, импорт пропущен", legacyFile);
                return false;
            }
            long now = System.currentTimeMillis();
            long imported = 0;
            long sum = 0;
            AccountRepository accounts = new AccountRepository();
            TransactionRepository transactions = new TransactionRepository();
            for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
                UUID uuid = entry.getKey();
                long minor = entry.getValue();
                if (minor <= 0) {
                    continue;
                }
                if (accounts.exists(connection, uuid)) {
                    // Аккаунт уже есть (создан модом) — баланс не трогаем и деньги
                    // не приписываем: ledger-запись о создании средств была бы ложной.
                    VEconomyMod.LOGGER.info("Аккаунт {} уже существует, legacy-баланс {} пропущен", uuid, minor);
                    continue;
                }
                accounts.insert(connection, new AccountRow(uuid, null, minor, AccountStatus.ACTIVE, now, now, 0));
                transactions.insert(connection, new TransactionRow(
                        null, TransactionType.LEGACY_IMPORT, null, uuid, minor, now,
                        null, "Импорт из устаревшего хранилища balances.json",
                        "legacy-import:" + uuid, Map.of("source", "legacy-balances.json"),
                        null, minor));
                imported++;
                sum += minor;
            }
            // Флаг ставим только после успешной обработки файла с реальными балансами.
            metaSet(connection, dialect, FLAG_KEY, String.valueOf(imported));
            VEconomyMod.LOGGER.info("Импортировано {} аккаунтов ({} монет) из {}", imported, sum, legacyFile);
            return imported > 0;
        } catch (SQLException | IOException e) {
            throw new DatabaseException("Ошибка импорта legacy-балансов из " + legacyFile, e);
        }
    }

    private static Map<UUID, Long> parse(Path legacyFile, int decimalPlaces) throws IOException {
        Map<UUID, Long> result = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(legacyFile, StandardCharsets.UTF_8)) {
            LegacyData data = GSON.fromJson(reader, LegacyData.class);
            if (data == null || data.balances == null) {
                return result;
            }
            double factor = Math.pow(10, decimalPlaces);
            for (Map.Entry<String, Double> entry : data.balances.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(entry.getKey());
                    double value = entry.getValue();
                    if (!Double.isFinite(value)) {
                        continue;
                    }
                    long minor = Math.round(value * factor);
                    if (minor > 0) {
                        result.put(uuid, minor);
                    }
                } catch (IllegalArgumentException ignored) {
                    // некорректный UUID — пропускаем запись
                }
            }
        }
        return result;
    }

    private static String metaGet(Connection connection, String key) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT value FROM meta WHERE meta_key = ?")) {
            statement.setString(1, key);
            try (var rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void metaSet(Connection connection, DatabaseManager.Dialect dialect, String key, String value) throws SQLException {
        String sql = dialect == DatabaseManager.Dialect.MYSQL
                ? "INSERT INTO meta (meta_key, value) VALUES (?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value)"
                : "INSERT OR REPLACE INTO meta (meta_key, value) VALUES (?, ?)";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private static final class LegacyData {
        Map<String, Double> balances = new HashMap<>();
    }
}
