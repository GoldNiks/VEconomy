package com.valorcraft.veconomy.persistence;

import com.valorcraft.veconomy.TestDb;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyImporterTest {

    @Test
    void importsBalancesFromLegacyJson() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Path dir = Files.createTempDirectory("legacy-test");
        Path file = dir.resolve("balances.json");
        Files.writeString(file,
                "{\"balances\":{\"" + alice + "\":100.0,\"" + bob + "\":250.0,\"not-a-uuid\":50.0}}",
                StandardCharsets.UTF_8);

        try (TestDb db = TestDb.create()) {
            boolean imported = LegacyImporter.importIfPresent(db.database, dir, db.settings);
            assertTrue(imported);
            assertEquals(100, db.accountService.getBalance(alice));
            assertEquals(250, db.accountService.getBalance(bob));
            // кривой UUID игнорируется; создано ровно 2 ledger-записи LEGACY_IMPORT
            assertEquals(2, db.ledger.countAll());
        }
    }

    @Test
    void importIsOneTime() throws Exception {
        UUID alice = UUID.randomUUID();
        Path dir = Files.createTempDirectory("legacy-test-2");
        Files.writeString(dir.resolve("balances.json"),
                "{\"balances\":{\"" + alice + "\":100.0}}", StandardCharsets.UTF_8);

        try (TestDb db = TestDb.create()) {
            assertTrue(LegacyImporter.importIfPresent(db.database, dir, db.settings));
            assertFalse(LegacyImporter.importIfPresent(db.database, dir, db.settings));
            assertEquals(100, db.accountService.getBalance(alice));
        }
    }

    @Test
    void noLegacyFileMeansNoImport() throws Exception {
        Path dir = Files.createTempDirectory("legacy-test-3");
        try (TestDb db = TestDb.create()) {
            assertFalse(LegacyImporter.importIfPresent(db.database, dir, db.settings));
        }
    }

    @Test
    void emptyFileDoesNotBlockLaterImport() throws Exception {
        UUID alice = UUID.randomUUID();
        Path dir = Files.createTempDirectory("legacy-test-4");
        Files.writeString(dir.resolve("balances.json"), "{\"balances\":{}}", StandardCharsets.UTF_8);

        try (TestDb db = TestDb.create()) {
            // пустой файл — флаг НЕ ставится, импорт не считается выполненным
            assertFalse(LegacyImporter.importIfPresent(db.database, dir, db.settings));

            // после появления реальных данных импорт проходит
            Files.writeString(dir.resolve("balances.json"),
                    "{\"balances\":{\"" + alice + "\":75.0}}", StandardCharsets.UTF_8);
            assertTrue(LegacyImporter.importIfPresent(db.database, dir, db.settings));
            assertEquals(75, db.accountService.getBalance(alice));
        }
    }

    @Test
    void existingAccountSkipsLedgerEntry() throws Exception {
        UUID alice = UUID.randomUUID();
        Path dir = Files.createTempDirectory("legacy-test-5");
        Files.writeString(dir.resolve("balances.json"),
                "{\"balances\":{\"" + alice + "\":100.0}}", StandardCharsets.UTF_8);

        try (TestDb db = TestDb.create()) {
            // аккаунт уже создан модом (например, стартовым бонусом)
            db.accountService.deposit(alice, 1000, com.valorcraft.veconomy.api.TransactionContext.of(
                    com.valorcraft.veconomy.api.TransactionType.ADMIN_DEPOSIT, null, "test", "test:setup"));

            boolean imported = LegacyImporter.importIfPresent(db.database, dir, db.settings);
            // баланс существующего аккаунта не трогаем и деньги ему не приписываем
            assertFalse(imported);
            assertEquals(1000, db.accountService.getBalance(alice));
            // ложной ledger-записи о создании средств нет
            assertEquals(1, db.ledger.countAll());
        }
    }
}
