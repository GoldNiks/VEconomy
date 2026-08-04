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
}
