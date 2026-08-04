package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.config.EconomySettings.MilestoneReward;
import com.valorcraft.veconomy.economy.AccountService;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Личные этапы за наигранное время (PLAYTIME). Каждый порог активных секунд выплачивается
 * игроку один раз: отметка в {@code claimed_milestones} (первичный ключ
 * {@code (player_uuid, milestone_id)}) + идемпотентный ключ начисления.
 */
public final class MilestoneService {

    private static final String SOURCE = "PLAYTIME";

    private final DatabaseManager database;
    private final MilestoneRepository milestones;
    private final AccountService accounts;
    private final ActivityService activity;
    private volatile EconomySettings settings;

    public MilestoneService(DatabaseManager database, MilestoneRepository milestones,
                            AccountService accounts, ActivityService activity, EconomySettings settings) {
        this.database = database;
        this.milestones = milestones;
        this.accounts = accounts;
        this.activity = activity;
        this.settings = settings;
    }

    public void applySettings(EconomySettings settings) {
        this.settings = settings;
    }

    /** Выдать игроку все достигнутые, ещё не выданные этапы. Возвращает только что выданные. */
    public List<MilestoneReward> checkPlayer(UUID playerId) {
        List<MilestoneReward> granted = new ArrayList<>();
        if (!settings.milestones.enabled) {
            return granted;
        }
        try {
            Set<String> claimedIds = database.inTransaction(connection ->
                    milestones.claimedIds(connection, playerId, SOURCE));
            long totalActive = activity.activeSecondsTotal(playerId);
            for (MilestoneReward reward : settings.milestones.rewards) {
                if (totalActive < reward.thresholdSeconds()) {
                    break;
                }
                String milestoneId = "playtime:" + reward.thresholdSeconds();
                if (!claimedIds.contains(milestoneId)) {
                    grant(playerId, milestoneId, reward, claimedIds, granted);
                }
            }
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка проверки милстоунов для {}", playerId, e);
        }
        return granted;
    }

    private void grant(UUID playerId, String milestoneId, MilestoneReward reward,
                       Set<String> claimedIds, List<MilestoneReward> granted) {
        TransactionResult result = accounts.deposit(playerId, reward.amountMinor(),
                TransactionContext.of(TransactionType.MILESTONE_REWARD, playerId,
                        "milestone:" + milestoneId,
                        "milestone:" + milestoneId + ":" + playerId));
        if (result.status() == TransactionResult.Status.SUCCESS
                || result.status() == TransactionResult.Status.DUPLICATE_OPERATION) {
            claimedIds.add(milestoneId);
            long now = System.currentTimeMillis();
            String txId = result.transactionId();
            database.inTransaction(connection -> {
                milestones.claim(connection, database.dialect(), new MilestoneRow(
                        playerId, milestoneId, reward.amountMinor(), now, SOURCE, txId));
                return null;
            });
            if (result.status() == TransactionResult.Status.SUCCESS) {
                granted.add(reward);
                VEconomyMod.LOGGER.info("Милстоун {} выдан игроку {} ({} монет)",
                        milestoneId, playerId, reward.amountMinor());
            }
        }
    }
}
