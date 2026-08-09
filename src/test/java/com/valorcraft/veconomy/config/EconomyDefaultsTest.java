package com.valorcraft.veconomy.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.valorcraft.veconomy.config.EconomySettings.MilestoneReward;
import com.valorcraft.veconomy.integration.ftbquests.QuestRewardConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Гарантирует синхронизацию денежных значений по умолчанию между
 * {@link EconomySettings}, Forge-конфигом {@link EconomyConfig} и шаблоном
 * {@link MilestoneConfig}. Проверяет новую «крупную» шкалу монеты.
 */
class EconomyDefaultsTest {

    private static final EconomySettings DEFAULTS = EconomySettings.defaults();

    @Test
    void maximumBalanceIsOneMillion() {
        assertEquals(1_000_000L, DEFAULTS.maximumBalance);
    }

    @Test
    void maximumTransferIsOneHundredThousand() {
        assertEquals(100_000L, DEFAULTS.maximumTransferAmount);
    }

    @Test
    void playtimeRewardsAreNewDenomination() {
        List<MilestoneReward> rewards = DEFAULTS.milestones.rewards;
        assertEquals(List.of(3600L, 10800L, 43200L, 86400L),
                rewards.stream().map(MilestoneReward::thresholdSeconds).toList());
        assertEquals(List.of(10L, 25L, 60L, 125L),
                rewards.stream().map(MilestoneReward::amountMinor).toList());
    }

    @Test
    void playtimeRewardsSumToTwoHundredTwenty() {
        long sum = DEFAULTS.milestones.rewards.stream().mapToLong(MilestoneReward::amountMinor).sum();
        assertEquals(220L, sum);
    }

    @Test
    void weeklyFundDefaultsAreNewScale() {
        EconomySettings.WeeklyFund weekly = DEFAULTS.weeklyFund;
        assertEquals(50L, weekly.baseAmountPerEligiblePlayer);
        assertEquals(100L, weekly.minimumFund);
        assertEquals(10_000L, weekly.maximumFund);
        assertEquals(2_000L, weekly.targetSupplyPerEligiblePlayer);
    }

    @Test
    void weeklyFundUnchangedValuesStay() {
        EconomySettings.WeeklyFund weekly = DEFAULTS.weeklyFund;
        assertEquals(7L, weekly.minAccountAgeDays);
        assertEquals(7_200L, weekly.minActiveSeconds);
        assertEquals(2, weekly.minActiveDays);
        assertEquals(1_800L, weekly.minActiveDaySeconds);
        assertEquals(6, weekly.payoutDelayHours);
        assertEquals(10, weekly.maximumPlayerSharePercent);
        assertEquals(List.of(70L, 12000L, 90L, 11000L, 110L, 10000L, 140L, 8500L, 100_000L, 7000L),
                weekly.economyCoefficientTiers.stream().flatMap(tier ->
                        java.util.stream.Stream.of(tier.upperRatioPercent(), tier.coefficientBps())).toList());
    }

    @Test
    void forgeConfigMatchesEconomySettingsDefaults() {
        CommentedConfig config = CommentedConfig.inMemory();
        ForgeConfigSpec spec = EconomyConfig.SPEC;
        spec.correct(config);

        assertEquals(1_000_000L, longAt(config, "currency", "maximumBalance"));
        assertEquals(100_000L, longAt(config, "transfers", "maximumAmount"));
        assertEquals(50L, longAt(config, "weeklyFund", "baseAmountPerEligiblePlayer"));
        assertEquals(100L, longAt(config, "weeklyFund", "minimumFund"));
        assertEquals(10_000L, longAt(config, "weeklyFund", "maximumFund"));
        assertEquals(2_000L, longAt(config, "weeklyFund", "targetSupplyPerEligiblePlayer"));
        assertEquals(0, intAt(config, "currency", "decimalPlaces"));

        List<?> flat = config.get(List.of("milestones", "rewards"));
        assertEquals(List.of(3600, 10, 10800, 25, 43200, 60, 86400, 125), flat);
    }

    @Test
    void milestoneTemplateUsesNewScale() throws IOException {
        Path dir = Files.createTempDirectory("veconomy-milestones-template");
        MilestoneConfig.load(dir, 1_000_000L);
        String template = Files.readString(dir.resolve(MilestoneConfig.FILE_NAME), StandardCharsets.UTF_8);
        assertTrue(template.contains("\"amount\": 30"), "шаблон enter_nether = 30");
        assertTrue(template.contains("\"amount\": 75"), "шаблон visit_moon = 75");
        assertTrue(template.contains("\"amount\": 120"), "шаблон event_bonus = 120");
    }

    @Test
    void milestoneTemplateSamplesAreDisabledByDefault() throws IOException {
        Path dir = Files.createTempDirectory("veconomy-milestones-template-disabled");
        MilestoneConfig.load(dir, 1_000_000L);
        String template = Files.readString(dir.resolve(MilestoneConfig.FILE_NAME), StandardCharsets.UTF_8);
        int occurrences = 0;
        int idx = 0;
        while ((idx = template.indexOf("\"enabled\": false", idx)) >= 0) {
            occurrences++;
            idx++;
        }
        assertEquals(3, occurrences, "все примеры шаблона должны быть отключены (enabled: false)");
    }

    @Test
    void questTemplateStartsWithEmptyChaptersAndQuests() throws IOException {
        Path dir = Files.createTempDirectory("veconomy-quests-template");
        QuestRewardConfig.load(dir);
        String template = Files.readString(dir.resolve("veconomy-quests.json"), StandardCharsets.UTF_8);
        assertTrue(template.contains("\"chapters\": {"));
        assertTrue(template.contains("\"quests\": {"));
        String chaptersBlock = template.substring(template.indexOf("\"chapters\": {"),
                template.indexOf("\"quests\": {"));
        assertFalse(chaptersBlock.contains("\"Глава"), "шаблон не должен содержать примеры глав");
        String questsBlock = template.substring(template.indexOf("\"quests\": {"));
        assertFalse(questsBlock.contains("\"123"), "шаблон не должен содержать примеры квестов");
    }

    @Test
    void auditOversizedDefaultIs75k() {
        // Порог «крупного перевода» для аудита согласован между кодом и шаблоном конфига.
        assertEquals(75_000L, AuditConfig.Settings.defaults().oversizedTransferAmount());
    }

    private static long longAt(CommentedConfig config, String section, String key) {
        Object value = config.get(List.of(section, key));
        return ((Number) value).longValue();
    }

    private static int intAt(CommentedConfig config, String section, String key) {
        Object value = config.get(List.of(section, key));
        return ((Number) value).intValue();
    }
}