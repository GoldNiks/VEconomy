package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.audit.AuditEventType;
import com.valorcraft.veconomy.audit.AuditService;
import com.valorcraft.veconomy.audit.AuditSeverity;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.config.EconomySettings.MilestoneReward;
import com.valorcraft.veconomy.config.MilestoneConfig;
import com.valorcraft.veconomy.economy.AccountService;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Личные этапы разовых наград. Единая цепочка для всех типов:
 * <pre>
 * событие или административный запрос
 *   → MilestoneDefinition (по id/типу)
 *   → проверка условия (MilestoneConditionRegistry, без switch по типам)
 *   → проверка claimed_milestones
 *   → атомарное начисление (деньги + ledger + claim в одной транзакции)
 *   → результат
 * </pre>
 * Начисление и запись claim неразделимы: транзакция либо подтверждена целиком,
 * либо не создаёт ни денег, ни отметки выдачи. Повторная выплата исключена
 * первичным ключом {@code (player_uuid, milestone_id)} и идемпотентным ключом.
 */
public final class MilestoneService {

    private final DatabaseManager database;
    private final MilestoneRepository milestones;
    private final AccountService accounts;
    private final ActivityService activity;
    private final DimensionVisitRepository visits;
    private final MilestoneConditionRegistry conditions;
    private final AuditService audit;
    private volatile EconomySettings settings;
    private volatile List<MilestoneDefinition> definitions = List.of();

    public MilestoneService(DatabaseManager database, MilestoneRepository milestones,
                            AccountService accounts, ActivityService activity,
                            DimensionVisitRepository visits, AuditService audit,
                            EconomySettings settings) {
        this.database = database;
        this.milestones = milestones;
        this.accounts = accounts;
        this.activity = activity;
        this.visits = visits;
        this.conditions = new MilestoneConditionRegistry(activity, database, visits);
        this.audit = audit;
        this.settings = settings;
        rebuildDefinitions(settings);
    }

    public void applySettings(EconomySettings settings) {
        this.settings = settings;
        rebuildDefinitions(settings);
    }

    // ---------------------------------------------------------------- definitions

    /** Пересобрать определения: PLAYTIME из toml + настраиваемые из MilestoneConfig. */
    private void rebuildDefinitions(EconomySettings settings) {
        List<MilestoneDefinition> defs = new ArrayList<>();
        if (settings.milestones.enabled) {
            for (MilestoneReward reward : settings.milestones.rewards) {
                defs.add(new MilestoneDefinition(
                        "playtime:" + reward.thresholdSeconds(),
                        MilestoneType.PLAYTIME,
                        reward.amountMinor(),
                        true,
                        Map.of("activeSeconds", Long.toString(reward.thresholdSeconds())),
                        null));
            }
        }
        defs.addAll(MilestoneConfig.definitions());
        this.definitions = List.copyOf(defs);
    }

    /** Все загруженные определения (PLAYTIME + настраиваемые). */
    public List<MilestoneDefinition> definitions() {
        return definitions;
    }

    public Optional<MilestoneDefinition> definition(String id) {
        for (MilestoneDefinition def : definitions) {
            if (def.id().equals(id)) {
                return Optional.of(def);
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- automatic (events / periodic)

    /**
     * Периодическая проверка PLAYTIME для одного игрока (вызывается по онлайн-игрокам).
     * Выдаёт все достигнутые и ещё не выданные PLAYTIME-этапы; возвращает выданные
     * (для уведомления). Работает и для офлайн-игрока (данные базы).
     */
    public List<MilestoneReward> checkPlayer(UUID playerId) {
        List<MilestoneReward> granted = new ArrayList<>();
        if (!settings.milestones.enabled) {
            return granted;
        }
        MilestoneCheckContext context = OfflineMilestoneCheckContext.of(playerId);
        for (MilestoneDefinition def : definitions) {
            if (def.type() != MilestoneType.PLAYTIME || !def.enabled()) {
                continue;
            }
            MilestoneGrantResult result = grantForEvent(playerId, def, context);
            if (result.status() == MilestoneGrantResult.Status.GRANTED) {
                long threshold = PlaytimeCondition.parseSeconds(def);
                long amount = result.amountMinor() == null ? def.amountMinor() : result.amountMinor();
                granted.add(new MilestoneReward(Math.max(0, threshold), amount));
            }
        }
        return granted;
    }

    /**
     * Выдать все milestone заданного типа, у которых выполнено условие (события
     * advancement/смена измерения). Не трогает EXTERNAL: автоматическая проверка для
     * него невозможна, поэтому обычный игрок никогда не получит EXTERNAL-награду сам.
     * Возвращает результаты по всем определениям типа (включая CONDITION_NOT_MET).
     */
    public List<MilestoneGrantResult> grantForEvent(UUID playerId, MilestoneType type,
                                                    MilestoneCheckContext context) {
        List<MilestoneGrantResult> results = new ArrayList<>();
        if (!settings.milestones.enabled) {
            return List.of(new MilestoneGrantResult(MilestoneGrantResult.Status.MILESTONES_DISABLED,
                    null, null));
        }
        for (MilestoneDefinition def : definitions) {
            if (def.type() != type || !def.enabled()) {
                continue;
            }
            results.add(grantForEvent(playerId, def, context));
        }
        return results;
    }

    /**
     * Выдать milestone, если условие выполнено (события advancement/измерение,
     * периодические проверки). Не трогает EXTERNAL: автоматическая проверка для него
     * невозможна, поэтому обычный игрок никогда не получит EXTERNAL-награду сам.
     */
    public MilestoneGrantResult grantForEvent(UUID playerId, MilestoneDefinition def,
                                              MilestoneCheckContext context) {
        if (!settings.milestones.enabled) {
            return MilestoneGrantResult.failed(MilestoneGrantResult.Status.MILESTONES_DISABLED);
        }
        if (!def.enabled()) {
            return MilestoneGrantResult.failed(MilestoneGrantResult.Status.DISABLED);
        }
        RewardExclusionStatus exclusion = activity.excludedFromRewards(playerId);
        if (exclusion == RewardExclusionStatus.EXCLUDED) {
            return MilestoneGrantResult.failed(MilestoneGrantResult.Status.EXCLUDED);
        }
        if (exclusion == RewardExclusionStatus.UNKNOWN) {
            // Флаг проверить не удалось (ошибка базы): награду удерживаем (fail-closed).
            return MilestoneGrantResult.failed(MilestoneGrantResult.Status.DATABASE_ERROR);
        }
        MilestoneCheckResult check = conditions.condition(def.type()).check(context, def);
        return switch (check.status()) {
            case MET -> grant(playerId, def, null, "milestone:" + def.id(), null);
            case BAD_CONFIG -> MilestoneGrantResult.failed(MilestoneGrantResult.Status.BAD_CONFIG);
            default -> MilestoneGrantResult.failed(MilestoneGrantResult.Status.CONDITION_NOT_MET);
        };
    }

    /** Проверить только условие (админ-команда {@code check}); деньги не выдаются. */
    public MilestoneCheckResult checkMilestone(UUID playerId, MilestoneDefinition def,
                                               MilestoneCheckContext context) {
        return conditions.condition(def.type()).check(context, def);
    }

    /**
     * Контекст проверки для административных команд: живой игрок, если онлайн,
     * иначе офлайн-контекст (advancement недоступен — возвращает NOT_AVAILABLE).
     */
    public MilestoneCheckContext contextFor(UUID playerId, net.minecraft.server.MinecraftServer server) {
        net.minecraft.server.level.ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            return ServerMilestoneCheckContext.of(online);
        }
        return OfflineMilestoneCheckContext.of(playerId);
    }

    // ---------------------------------------------------------------- admin / trusted

    /**
     * Принудительная административная выдача. Не проверяет условие; не выдаёт повторно,
     * если milestone уже получен. Записывает деньги и claim в одной транзакции.
     */
    public MilestoneGrantResult grant(UUID playerId, MilestoneDefinition def, UUID actorId,
                                      String reason, String idempotencyKey) {
        if (playerId == null || def == null) {
            return MilestoneGrantResult.failed(MilestoneGrantResult.Status.NOT_FOUND);
        }
        if (!def.enabled()) {
            return MilestoneGrantResult.failed(MilestoneGrantResult.Status.DISABLED);
        }
        return doGrant(playerId, def, actorId, reason, idempotencyKey);
    }

    /**
     * Trusted-выдача EXTERNAL milestone (KubeJS bridge, внутренняя команда, консоль).
     * Тип обязан быть EXTERNAL, idempotencyKey обязателен — без него повторный вызов
     * или подмена ключа невозможны.
     */
    public MilestoneGrantResult grantExternal(UUID playerId, String milestoneId, String idempotencyKey) {
        Optional<MilestoneDefinition> def = definition(milestoneId);
        if (def.isEmpty()) {
            return MilestoneGrantResult.failed(MilestoneGrantResult.Status.NOT_FOUND);
        }
        if (def.get().type() != MilestoneType.EXTERNAL) {
            return MilestoneGrantResult.failed(MilestoneGrantResult.Status.EXTERNAL_ONLY);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return MilestoneGrantResult.failed(MilestoneGrantResult.Status.INVALID_KEY);
        }
        return grant(playerId, def.get(), null, "external:" + def.get().id(), idempotencyKey);
    }

    /**
     * Снять отметку о выдаче (чистый revoke). Не удаляет ledger-запись и не трогает
     * баланс; повторное выполнение условия снова сможет выдать milestone.
     *
     * @return true, если отметка существовала и снята
     */
    public boolean revoke(UUID playerId, String milestoneId) {
        boolean existed;
        try {
            existed = database.inTransaction(connection -> {
                boolean found = milestones.find(connection, playerId, milestoneId).isPresent();
                milestones.revoke(connection, playerId, milestoneId);
                VEconomyMod.LOGGER.info("Отозван milestone {} у игрока {}", milestoneId, playerId);
                return found;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка отзыва milestone {} у {}", milestoneId, playerId, e);
            return false;
        }
        if (existed && audit != null) {
            // Запись аудита — отдельная транзакция после подтверждённого отзыва.
            audit.record(AuditEventType.MILESTONE_REVOKED, AuditSeverity.INFO, playerId, null,
                    "milestone=" + milestoneId);
        }
        return existed;
    }

    /** Выдан ли milestone игроку. */
    public boolean isClaimed(UUID playerId, String milestoneId) {
        try {
            return database.inTransaction(connection ->
                    milestones.find(connection, playerId, milestoneId).isPresent());
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения milestone {} у {}", milestoneId, playerId, e);
            return false;
        }
    }

    /** Все выдачи player'а (для {@code list <игрок>}). */
    public List<MilestoneRow> claims(UUID playerId) {
        try {
            return database.inTransaction(connection -> milestones.claims(connection, playerId));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения выданных milestone {}", playerId, e);
            return List.of();
        }
    }

    /** Записать посещение измерения (для условий DIMENSION_VISIT). Идемпотентно. */
    public void recordDimensionVisit(UUID playerId, String dimension) {
        try {
            database.inTransaction(connection -> {
                visits.recordVisit(connection, database.dialect(), playerId, dimension,
                        System.currentTimeMillis());
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка записи посещения измерения {} {}", playerId, dimension, e);
        }
    }

    // ---------------------------------------------------------------- atomic grant

    /**
     * Атомарная выдача: проверка claim → начисление с ledger → запись claim — в одной
     * транзакции. Ошибка базы откатывает всё: не появится ни денег без claim, ни claim
     * без ledger-записи.
     * <p>
     * Идемпотентный ключ: если вызывающий не задал его, генерируется случайный —
     * повторную выплату отсекает сам claim (первичный ключ {@code (player_uuid,
     * milestone_id)}). Явный ключ (EXTERNAL) остаётся обязательным у доверенных
     * интеграций: повторная подача с тем же ключом даёт {@code DUPLICATE_OPERATION}.
     */
    private MilestoneGrantResult doGrant(UUID playerId, MilestoneDefinition def, UUID actorId,
                                         String reason, String idempotencyKey) {
        MilestoneGrantResult result;
        try {
            result = database.inTransaction(connection -> {
                if (milestones.find(connection, playerId, def.id()).isPresent()) {
                    return MilestoneGrantResult.failed(MilestoneGrantResult.Status.ALREADY_CLAIMED);
                }
                String key = idempotencyKey != null ? idempotencyKey
                        : "milestone:" + def.id() + ":" + playerId + ":" + UUID.randomUUID();
                TransactionResult deposit = accounts.depositIn(connection, playerId, def.amountMinor(),
                        TransactionContext.of(TransactionType.MILESTONE_REWARD, actorId,
                                reason != null ? reason : "milestone:" + def.id(), key));
                if (deposit.status() == TransactionResult.Status.DUPLICATE_OPERATION) {
                    // Ключ уже использован для другой операции — награду не выдаём и claim не пишем.
                    return new MilestoneGrantResult(MilestoneGrantResult.Status.DUPLICATE_OPERATION,
                            deposit.transactionId(), null);
                }
                MilestoneGrantResult.Status status = mapDepositStatus(deposit);
                if (status != MilestoneGrantResult.Status.GRANTED) {
                    return MilestoneGrantResult.failed(status);
                }
                long now = System.currentTimeMillis();
                milestones.claim(connection, database.dialect(), new MilestoneRow(
                        playerId, def.id(), def.amountMinor(), now, def.type().name(),
                        deposit.transactionId()));
                return new MilestoneGrantResult(MilestoneGrantResult.Status.GRANTED,
                        deposit.transactionId(), def.amountMinor());
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка выдачи milestone {} игроку {}", def.id(), playerId, e);
            return MilestoneGrantResult.failed(MilestoneGrantResult.Status.DATABASE_ERROR);
        }
        if (result.status() == MilestoneGrantResult.Status.GRANTED && audit != null) {
            // Запись аудита — отдельная транзакция после подтверждённой выдачи.
            audit.record(AuditEventType.MILESTONE_GRANTED, AuditSeverity.INFO, playerId, actorId,
                    result.amountMinor(),
                    "milestone=" + def.id() + ";type=" + def.type().name()
                            + ";tx=" + result.transactionId());
        }
        return result;
    }

    private static MilestoneGrantResult.Status mapDepositStatus(TransactionResult deposit) {
        return switch (deposit.status()) {
            case SUCCESS -> MilestoneGrantResult.Status.GRANTED;
            case LIMIT_EXCEEDED -> MilestoneGrantResult.Status.LIMIT_EXCEEDED;
            case ACCOUNT_DISABLED -> MilestoneGrantResult.Status.ACCOUNT_FROZEN;
            default -> MilestoneGrantResult.Status.FAILED;
        };
    }

    /** Результат выдачи milestone. */
    public record MilestoneGrantResult(Status status, String transactionId, Long amountMinor) {

        public enum Status {
            /** Начислено (деньги + claim записаны). */
            GRANTED,
            /** Уже был получен ранее — повторная выплата не создана. */
            ALREADY_CLAIMED,
            /** Условие автоматической проверки не выполнено. */
            CONDITION_NOT_MET,
            /** Конфигурация milestone некорректна (неверный ресурс, незарегистрированный advancement). */
            BAD_CONFIG,
            /** Игрок исключён из наград. */
            EXCLUDED,
            /** Milestone отключён. */
            DISABLED,
            /** Система milestones отключена в конфиге. */
            MILESTONES_DISABLED,
            /** Милстоун с таким id не загружен. */
            NOT_FOUND,
            /** Выдача разрешена только для типа EXTERNAL (trusted-путь). */
            EXTERNAL_ONLY,
            /** Идемпотентный ключ обязателен. */
            INVALID_KEY,
            /** Идемпотентный ключ уже использован другой операцией. */
            DUPLICATE_OPERATION,
            /** Аккаунт заморожен. */
            ACCOUNT_FROZEN,
            /** Сумма превысила лимит баланса. */
            LIMIT_EXCEEDED,
            /** Ошибка базы. */
            DATABASE_ERROR,
            /** Общая ошибка начисления. */
            FAILED
        }

        static MilestoneGrantResult failed(Status status) {
            return new MilestoneGrantResult(status, null, null);
        }
    }
}