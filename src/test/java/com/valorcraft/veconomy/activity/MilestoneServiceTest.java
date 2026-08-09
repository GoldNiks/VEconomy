package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.config.EconomySettings.MilestoneReward;
import com.valorcraft.veconomy.config.MilestoneConfig;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneServiceTest {

    private static EconomySettings milestonesEnabled() {
        return withMilestones(new EconomySettings.Milestones(true,
                List.of(new MilestoneReward(3600, 10), new MilestoneReward(10800, 25)), true));
    }

    private static EconomySettings withActivityAndMilestones(EconomySettings.Activity activity,
                                                             EconomySettings.Milestones milestones) {
        EconomySettings defaults = EconomySettings.defaults();
        return new EconomySettings(
                defaults.currencyNameSingular, defaults.currencyNameFew, defaults.currencyNameMany,
                defaults.currencySymbol, defaults.decimalPlaces, defaults.maximumBalance,
                defaults.transfersEnabled, defaults.allowOfflineRecipients,
                defaults.minimumTransferAmount, defaults.maximumTransferAmount,
                defaults.transferCooldownSeconds,
                defaults.dbType, defaults.databaseFile, defaults.busyTimeoutMillis, defaults.walEnabled,
                defaults.mysqlHost, defaults.mysqlPort, defaults.mysqlDatabase,
                defaults.mysqlUser, defaults.mysqlPassword, defaults.mysqlPoolSize,
                defaults.broadcastAdminChanges,
                activity,
                milestones,
                defaults.weeklyFund);
    }

    private static EconomySettings withMilestones(EconomySettings.Milestones milestones) {
        EconomySettings defaults = EconomySettings.defaults();
        return new EconomySettings(
                defaults.currencyNameSingular, defaults.currencyNameFew, defaults.currencyNameMany,
                defaults.currencySymbol, defaults.decimalPlaces, defaults.maximumBalance,
                defaults.transfersEnabled, defaults.allowOfflineRecipients,
                defaults.minimumTransferAmount, defaults.maximumTransferAmount,
                defaults.transferCooldownSeconds,
                defaults.dbType, defaults.databaseFile, defaults.busyTimeoutMillis, defaults.walEnabled,
                defaults.mysqlHost, defaults.mysqlPort, defaults.mysqlDatabase,
                defaults.mysqlUser, defaults.mysqlPassword, defaults.mysqlPoolSize,
                defaults.broadcastAdminChanges,
                defaults.activity,
                milestones,
                defaults.weeklyFund);
    }

    private static void setActiveSeconds(TestDb db, UUID player, long seconds, boolean excluded) {
        db.database.inTransaction(connection -> {
            db.activityRepository.upsert(connection, DatabaseManager.Dialect.SQLITE,
                    new PlayerActivityRow(player, 1L, 1L, seconds, seconds, 0,
                            WeekId.current(), 1L, "minecraft:overworld", excluded));
            return null;
        });
    }

    private static void setActiveSeconds(TestDb db, UUID player, long seconds) {
        setActiveSeconds(db, player, seconds, false);
    }

    /** Загрузить конфиг milestones из JSON и пересобрать определения сервиса. */
    private static void loadMilestones(TestDb db, String jsonBody) throws IOException {
        Path dir = Files.createTempDirectory("veconomy-milestones");
        Path file = dir.resolve(MilestoneConfig.FILE_NAME);
        Files.writeString(file, jsonBody, StandardCharsets.UTF_8);
        MilestoneConfig.load(dir, db.settings.maximumBalance);
        db.milestoneService.applySettings(db.settings);
    }

    private static String json(String... definitions) {
        return "{\"milestones\": [" + String.join(",", definitions) + "]}";
    }

    private static String def(String id, String type, long amount, String requirements) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"amount\":" + amount
                + ",\"enabled\":true,\"requirements\":" + requirements + "}";
    }

    private static final String ADVANCEMENT_DEF =
            def("enter_nether", "ADVANCEMENT", 30,
                    "{\"advancement\":\"minecraft:story/enter_the_nether\"}");
    private static final String DIMENSION_DEF =
            def("visit_moon", "DIMENSION_VISIT", 75,
                    "{\"dimension\":\"ad_astra:moon\"}");
    private static final String EXTERNAL_DEF =
            def("event_bonus", "EXTERNAL", 120, "{\"channel\":\"events\"}");

    /** Фейковый контекст проверки: прогресс advancement задаётся вручную. */
    private static final class FakeContext implements MilestoneCheckContext {
        private final UUID playerId;
        private final Map<ResourceLocation, Boolean> advancements = new HashMap<>();
        private final Set<ResourceLocation> registered = new HashSet<>();
        private final Set<ResourceLocation> unregistered = new HashSet<>();

        FakeContext(UUID playerId) {
            this.playerId = playerId;
        }

        /** Зарегистрировать advancement на «сервере» и задать прогресс игрока. */
        FakeContext withAdvancement(ResourceLocation id, boolean done) {
            advancements.put(id, done);
            registered.add(id);
            return this;
        }

        /** Смоделировать advancement, не зарегистрированный на сервере. */
        FakeContext withUnregistered(ResourceLocation id) {
            registered.remove(id);
            unregistered.add(id);
            return this;
        }

        @Override
        public UUID playerId() {
            return playerId;
        }

        @Override
        public Optional<ServerPlayer> player() {
            return Optional.empty();
        }

        @Override
        public Optional<Boolean> advancementDone(ResourceLocation advancementId) {
            return Optional.ofNullable(advancements.get(advancementId));
        }

        @Override
        public Optional<Boolean> advancementRegistered(ResourceLocation advancementId) {
            if (registered.contains(advancementId)) {
                return Optional.of(true);
            }
            if (unregistered.contains(advancementId)) {
                return Optional.of(false);
            }
            return Optional.empty();
        }
    }

    // ---------------------------------------------------------------- PLAYTIME

    @Test
    void paysMilestoneOnceWhenThresholdReached() {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            UUID player = UUID.randomUUID();
            setActiveSeconds(db, player, 3600);

            var granted = db.milestoneService.checkPlayer(player);
            assertEquals(1, granted.size());
            assertEquals(10, db.accountService.getBalance(player));

            db.milestoneService.checkPlayer(player);
            assertEquals(10, db.accountService.getBalance(player));
        }
    }

    @Test
    void paysAllReachedThresholds() {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            UUID player = UUID.randomUUID();
            setActiveSeconds(db, player, 20_000);

            var granted = db.milestoneService.checkPlayer(player);
            assertEquals(2, granted.size());
            assertEquals(35, db.accountService.getBalance(player));
        }
    }

    @Test
    void noRewardBeforeThreshold() {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            UUID player = UUID.randomUUID();
            setActiveSeconds(db, player, 100);

            var granted = db.milestoneService.checkPlayer(player);
            assertTrue(granted.isEmpty());
            assertEquals(0, db.accountService.getBalance(player));
        }
    }

    @Test
    void disabledMilestonesPayNothing() {
        try (TestDb db = TestDb.create(withMilestones(new EconomySettings.Milestones(false,
                List.of(new MilestoneReward(3600, 10)), true)))) {
            UUID player = UUID.randomUUID();
            setActiveSeconds(db, player, 3600);

            var granted = db.milestoneService.checkPlayer(player);
            assertTrue(granted.isEmpty());
            assertEquals(0, db.accountService.getBalance(player));
        }
    }

    @Test
    void activityDisabledBlocksAllMilestoneGrants() throws IOException {
        // Учёт активности выключен: ни один милстоун не выдаётся, даже принудительный
        // админский (деньги основываются на учтённой активности).
        EconomySettings.Activity disabled = new EconomySettings.Activity(false, 300, 20, 60, 0.5);
        try (TestDb db = TestDb.create(withActivityAndMilestones(disabled,
                new EconomySettings.Milestones(true,
                        List.of(new MilestoneReward(3600, 10)), true)))) {
            loadMilestones(db, json(EXTERNAL_DEF, ADVANCEMENT_DEF, DIMENSION_DEF));
            UUID player = UUID.randomUUID();
            setActiveSeconds(db, player, 20_000);
            db.milestoneService.recordDimensionVisit(player, "ad_astra:moon");

            assertTrue(db.milestoneService.checkPlayer(player).isEmpty());

            var external = db.milestoneService.grantExternal(player, "event_bonus", "key-1");
            assertEquals(MilestoneService.MilestoneGrantResult.Status.ACTIVITY_DISABLED,
                    external.status());

            var event = db.milestoneService.grantForEvent(player, MilestoneType.ADVANCEMENT,
                    new FakeContext(player).withAdvancement(
                            ResourceLocation.tryParse("minecraft:story/enter_the_nether"), true));
            assertEquals(MilestoneService.MilestoneGrantResult.Status.ACTIVITY_DISABLED,
                    event.get(0).status());

            var def = db.milestoneService.definition("visit_moon").orElseThrow();
            var admin = db.milestoneService.grant(player, def, null, "admin:test", null);
            assertEquals(MilestoneService.MilestoneGrantResult.Status.ACTIVITY_DISABLED,
                    admin.status());

            assertEquals(0, db.accountService.getBalance(player));
            assertFalse(db.milestoneService.isClaimed(player, "event_bonus"));
            assertFalse(db.milestoneService.isClaimed(player, "enter_nether"));
            assertFalse(db.milestoneService.isClaimed(player, "visit_moon"));
        }
    }

    // ---------------------------------------------------------------- ADVANCEMENT

    @Test
    void advancementMetGrantsOnce() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(ADVANCEMENT_DEF));
            UUID player = UUID.randomUUID();
            FakeContext context = new FakeContext(player).withAdvancement(
                    ResourceLocation.tryParse("minecraft:story/enter_the_nether"), true);

            var result = db.milestoneService.grantForEvent(player, MilestoneType.ADVANCEMENT, context);
            assertEquals(1, result.size());
            assertEquals(MilestoneService.MilestoneGrantResult.Status.GRANTED, result.get(0).status());
            assertEquals(30, db.accountService.getBalance(player));

            db.milestoneService.grantForEvent(player, MilestoneType.ADVANCEMENT, context);
            assertEquals(30, db.accountService.getBalance(player));
            assertTrue(db.milestoneService.isClaimed(player, "enter_nether"));
        }
    }

    @Test
    void advancementNotMetPaysNothing() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(ADVANCEMENT_DEF));
            UUID player = UUID.randomUUID();
            FakeContext context = new FakeContext(player).withAdvancement(
                    ResourceLocation.tryParse("minecraft:story/enter_the_nether"), false);

            var result = db.milestoneService.grantForEvent(player, MilestoneType.ADVANCEMENT, context);
            assertEquals(MilestoneService.MilestoneGrantResult.Status.CONDITION_NOT_MET,
                    result.get(0).status());
            assertEquals(0, db.accountService.getBalance(player));
            assertFalse(db.milestoneService.isClaimed(player, "enter_nether"));
        }
    }

    @Test
    void advancementUnknownIdUnavailable() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(ADVANCEMENT_DEF));
            UUID player = UUID.randomUUID();
            // Игрок офлайн/прогресс недоступен — контекст отвечает empty.
            FakeContext context = new FakeContext(player);

            var result = db.milestoneService.grantForEvent(player, MilestoneType.ADVANCEMENT, context);
            assertEquals(MilestoneService.MilestoneGrantResult.Status.CONDITION_NOT_MET,
                    result.get(0).status());
            assertEquals(0, db.accountService.getBalance(player));
        }
    }

    @Test
    void advancementUnregisteredIsConfigError() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(ADVANCEMENT_DEF));
            UUID player = UUID.randomUUID();
            // Advancement «выполнен», но не зарегистрирован на сервере: это ошибка
            // конфигурации (BAD_CONFIG), а не невыполненное условие.
            FakeContext context = new FakeContext(player)
                    .withAdvancement(ResourceLocation.tryParse("minecraft:story/enter_the_nether"), true)
                    .withUnregistered(ResourceLocation.tryParse("minecraft:story/enter_the_nether"));

            var result = db.milestoneService.grantForEvent(player, MilestoneType.ADVANCEMENT, context);
            assertEquals(MilestoneService.MilestoneGrantResult.Status.BAD_CONFIG,
                    result.get(0).status());
            assertEquals(0, db.accountService.getBalance(player));
            assertFalse(db.milestoneService.isClaimed(player, "enter_nether"));
        }
    }

    @Test
    void advancementUnregisteredCheckReportsReason() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(ADVANCEMENT_DEF));
            UUID player = UUID.randomUUID();
            FakeContext context = new FakeContext(player)
                    .withUnregistered(ResourceLocation.tryParse("minecraft:story/enter_the_nether"));

            var def = db.milestoneService.definition("enter_nether").orElseThrow();
            MilestoneCheckResult check = db.milestoneService.checkMilestone(player, def, context);
            assertEquals(MilestoneCheckResult.Status.BAD_CONFIG, check.status());
            assertEquals("admin.milestone.reason.unregistered", check.reasonKey());
        }
    }

    @Test
    void malformedAdvancementKeepsLastKnownGoodDefinitions() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(ADVANCEMENT_DEF));
            assertTrue(db.milestoneService.definition("enter_nether").isPresent());

            // Повреждённый конфиг (некорректный ResourceLocation) не применяется:
            // остаётся последняя корректная версия, награда по ошибке не выдаётся.
            loadMilestones(db, json(def("broken", "ADVANCEMENT", 500,
                    "{\"advancement\":\"not a resource location\"}")));
            assertTrue(db.milestoneService.definition("enter_nether").isPresent());
            assertTrue(db.milestoneService.definition("broken").isEmpty());
        }
    }

    // ---------------------------------------------------------------- DIMENSION_VISIT

    @Test
    void dimensionVisitFirstEntryGrants() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(DIMENSION_DEF));
            UUID player = UUID.randomUUID();
            db.milestoneService.recordDimensionVisit(player, "ad_astra:moon");

            var result = db.milestoneService.grantForEvent(player, MilestoneType.DIMENSION_VISIT,
                    new FakeContext(player));
            assertEquals(MilestoneService.MilestoneGrantResult.Status.GRANTED, result.get(0).status());
            assertEquals(75, db.accountService.getBalance(player));
        }
    }

    @Test
    void dimensionVisitRecordIsIdempotent() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(DIMENSION_DEF));
            UUID player = UUID.randomUUID();
            db.milestoneService.recordDimensionVisit(player, "ad_astra:moon");
            db.milestoneService.recordDimensionVisit(player, "ad_astra:moon");

            db.milestoneService.grantForEvent(player, MilestoneType.DIMENSION_VISIT,
                    new FakeContext(player));
            assertEquals(75, db.accountService.getBalance(player));
            assertTrue(db.milestoneService.isClaimed(player, "visit_moon"));
        }
    }

    @Test
    void dimensionVisitCheckBeforeGrant() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(DIMENSION_DEF));
            UUID player = UUID.randomUUID();
            db.milestoneService.recordDimensionVisit(player, "ad_astra:moon");

            var def = db.milestoneService.definition("visit_moon").orElseThrow();
            MilestoneCheckResult check = db.milestoneService.checkMilestone(player, def,
                    new FakeContext(player));
            assertEquals(MilestoneCheckResult.Status.MET, check.status());

            db.milestoneService.grantForEvent(player, MilestoneType.DIMENSION_VISIT,
                    new FakeContext(player));
            assertEquals(75, db.accountService.getBalance(player));
        }
    }

    @Test
    void dimensionVisitNotVisited() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(DIMENSION_DEF));
            UUID player = UUID.randomUUID();

            var result = db.milestoneService.grantForEvent(player, MilestoneType.DIMENSION_VISIT,
                    new FakeContext(player));
            assertEquals(MilestoneService.MilestoneGrantResult.Status.CONDITION_NOT_MET,
                    result.get(0).status());
            assertEquals(0, db.accountService.getBalance(player));
        }
    }

    // ---------------------------------------------------------------- EXTERNAL

    @Test
    void externalTrustedGrant() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(EXTERNAL_DEF));
            UUID player = UUID.randomUUID();

            var result = db.milestoneService.grantExternal(player, "event_bonus", "event-2026-01");
            assertEquals(MilestoneService.MilestoneGrantResult.Status.GRANTED, result.status());
            assertEquals(120, db.accountService.getBalance(player));
            assertTrue(db.milestoneService.isClaimed(player, "event_bonus"));
        }
    }

    @Test
    void externalUnavailableToPlayerEvents() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(EXTERNAL_DEF));
            UUID player = UUID.randomUUID();

            // Обычное игровое событие (advancement-стиль) не может выдать EXTERNAL.
            var result = db.milestoneService.grantForEvent(player, MilestoneType.EXTERNAL,
                    new FakeContext(player));
            assertEquals(MilestoneService.MilestoneGrantResult.Status.CONDITION_NOT_MET,
                    result.get(0).status());
            assertEquals(0, db.accountService.getBalance(player));
            assertFalse(db.milestoneService.isClaimed(player, "event_bonus"));
        }
    }

    @Test
    void externalDuplicateIdempotencyKey() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(EXTERNAL_DEF));
            UUID player = UUID.randomUUID();

            db.milestoneService.grantExternal(player, "event_bonus", "key-1");
            var second = db.milestoneService.grantExternal(player, "event_bonus", "key-1");
            assertEquals(MilestoneService.MilestoneGrantResult.Status.ALREADY_CLAIMED,
                    second.status());
            assertEquals(120, db.accountService.getBalance(player));
            assertTrue(db.milestoneService.isClaimed(player, "event_bonus"));
        }
    }

    @Test
    void keyUsedByOtherOperationIsDuplicate() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(EXTERNAL_DEF));
            UUID player = UUID.randomUUID();
            // Ключ уже занят другой операцией (обычный скриптовый начисление).
            db.accountService.deposit(player, 100,
                    com.valorcraft.veconomy.api.TransactionContext.of(
                            com.valorcraft.veconomy.api.TransactionType.PLUGIN_OPERATION,
                            null, "kubejs:add", "key-1"));

            var result = db.milestoneService.grantExternal(player, "event_bonus", "key-1");
            assertEquals(MilestoneService.MilestoneGrantResult.Status.DUPLICATE_OPERATION,
                    result.status());
            // Деньги от milestone не начислены, claim не записан.
            assertEquals(100, db.accountService.getBalance(player));
            assertFalse(db.milestoneService.isClaimed(player, "event_bonus"));
        }
    }

    @Test
    void externalFreshKeyOnExistingClaimIsAlreadyClaimed() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(EXTERNAL_DEF));
            UUID player = UUID.randomUUID();

            db.milestoneService.grantExternal(player, "event_bonus", "key-1");
            var second = db.milestoneService.grantExternal(player, "event_bonus", "key-2");
            assertEquals(MilestoneService.MilestoneGrantResult.Status.ALREADY_CLAIMED,
                    second.status());
            assertEquals(120, db.accountService.getBalance(player));
        }
    }

    @Test
    void externalRequiresKey() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(EXTERNAL_DEF));
            UUID player = UUID.randomUUID();

            var result = db.milestoneService.grantExternal(player, "event_bonus", "  ");
            assertEquals(MilestoneService.MilestoneGrantResult.Status.INVALID_KEY, result.status());
            assertEquals(0, db.accountService.getBalance(player));
        }
    }

    @Test
    void externalNotAllowedForNonExternalType() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(ADVANCEMENT_DEF));
            UUID player = UUID.randomUUID();

            var result = db.milestoneService.grantExternal(player, "enter_nether", "key-1");
            assertEquals(MilestoneService.MilestoneGrantResult.Status.EXTERNAL_ONLY, result.status());
            assertEquals(0, db.accountService.getBalance(player));
        }
    }

    @Test
    void unknownMilestoneNotFound() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(EXTERNAL_DEF));
            UUID player = UUID.randomUUID();

            var result = db.milestoneService.grantExternal(player, "nope", "key-1");
            assertEquals(MilestoneService.MilestoneGrantResult.Status.NOT_FOUND, result.status());
        }
    }

    // ---------------------------------------------------------------- revoke / admin

    @Test
    void revokeThenRegrantPaysAgain() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(EXTERNAL_DEF));
            UUID player = UUID.randomUUID();

            db.milestoneService.grantExternal(player, "event_bonus", "key-1");
            assertTrue(db.milestoneService.revoke(player, "event_bonus"));
            assertFalse(db.milestoneService.isClaimed(player, "event_bonus"));
            assertEquals(120, db.accountService.getBalance(player));

            var regrant = db.milestoneService.grantExternal(player, "event_bonus", "key-2");
            assertEquals(MilestoneService.MilestoneGrantResult.Status.GRANTED, regrant.status());
            assertEquals(240, db.accountService.getBalance(player));
        }
    }

    @Test
    void revokeWithoutClaimIsFalse() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(EXTERNAL_DEF));
            UUID player = UUID.randomUUID();

            assertFalse(db.milestoneService.revoke(player, "event_bonus"));
        }
    }

    @Test
    void adminGrantForcesWithoutCondition() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(DIMENSION_DEF));
            UUID player = UUID.randomUUID();
            var def = db.milestoneService.definition("visit_moon").orElseThrow();

            // Игрок не посещал измерение, но админ выдаёт принудительно.
            var result = db.milestoneService.grant(player, def, null, "admin:test", null);
            assertEquals(MilestoneService.MilestoneGrantResult.Status.GRANTED, result.status());
            assertEquals(75, db.accountService.getBalance(player));
        }
    }

    @Test
    void limitExceededLeavesNoClaimAndNoMoney() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(EXTERNAL_DEF));
            UUID player = UUID.randomUUID();
            long excessive = db.settings.maximumBalance + 1;
            MilestoneDefinition huge = new MilestoneDefinition("huge", MilestoneType.EXTERNAL,
                    excessive, true, Map.of("channel", "test"), null);

            var result = db.milestoneService.grant(player, huge, null, "admin:test", null);
            assertEquals(MilestoneService.MilestoneGrantResult.Status.LIMIT_EXCEEDED, result.status());
            assertEquals(0, db.accountService.getBalance(player));
            assertFalse(db.milestoneService.isClaimed(player, "huge"));
        }
    }

    @Test
    void excludedPlayerGetsNothing() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(ADVANCEMENT_DEF));
            UUID player = UUID.randomUUID();
            setActiveSeconds(db, player, 100, true);
            FakeContext context = new FakeContext(player).withAdvancement(
                    ResourceLocation.tryParse("minecraft:story/enter_the_nether"), true);

            var result = db.milestoneService.grantForEvent(player, MilestoneType.ADVANCEMENT, context);
            assertEquals(MilestoneService.MilestoneGrantResult.Status.EXCLUDED, result.get(0).status());
            assertEquals(0, db.accountService.getBalance(player));
        }
    }

    @Test
    void exclusionCheckErrorFailsClosedWithoutGrant() throws IOException {
        // Ошибка базы при чтении флага исключения не должна трактоваться как «не исключён»:
        // награда удерживается (fail-closed), денег и claim не появляется.
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(ADVANCEMENT_DEF));
            UUID player = UUID.randomUUID();
            FakeContext context = new FakeContext(player).withAdvancement(
                    ResourceLocation.tryParse("minecraft:story/enter_the_nether"), true);

            db.database.close();
            var result = db.milestoneService.grantForEvent(player, MilestoneType.ADVANCEMENT, context);
            assertEquals(MilestoneService.MilestoneGrantResult.Status.DATABASE_ERROR,
                    result.get(0).status());
            assertEquals(0, db.accountService.getBalance(player));
            assertFalse(db.milestoneService.isClaimed(player, "enter_nether"));
        }
    }

    @Test
    void frozenAccountRejectsMilestoneGrant() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(EXTERNAL_DEF));
            UUID player = UUID.randomUUID();
            db.accountService.createOrTouch(player, "Frozen");
            db.accountService.freeze(player, "модерация");

            var result = db.milestoneService.grantExternal(player, "event_bonus", "key-1");
            assertEquals(MilestoneService.MilestoneGrantResult.Status.ACCOUNT_FROZEN, result.status());
            assertEquals(0, db.accountService.getBalance(player));
            assertFalse(db.milestoneService.isClaimed(player, "event_bonus"));
        }
    }

    @Test
    void databaseErrorLeavesNoMoneyAndNoClaim() throws IOException {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            loadMilestones(db, json(EXTERNAL_DEF));
            UUID player = UUID.randomUUID();
            db.database.close();

            var result = db.milestoneService.grantExternal(player, "event_bonus", "key-1");
            assertEquals(MilestoneService.MilestoneGrantResult.Status.DATABASE_ERROR, result.status());
            assertFalse(db.milestoneService.isClaimed(player, "event_bonus"));
        }
    }
}
