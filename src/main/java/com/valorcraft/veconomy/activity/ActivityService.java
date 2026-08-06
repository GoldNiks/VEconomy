package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.audit.AuditActorType;
import com.valorcraft.veconomy.audit.AuditEventType;
import com.valorcraft.veconomy.audit.AuditService;
import com.valorcraft.veconomy.audit.AuditSeverity;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Учёт активности игроков: время в сети, активное время и AFK. Активные счётчики
 * хранятся в памяти (сессия), периодически и в момент выхода записываются в таблицу
 * {@code player_activity}. AFK определяется как отсутствие движения/поворота/чата
 * дольше {@code afkTimeoutSeconds}.
 */
public final class ActivityService {

    private final DatabaseManager database;
    private final PlayerActivityRepository repository;
    private final WeeklyActivityDayRepository days;
    private final AuditService audit;
    private volatile EconomySettings settings;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Выходы, данные которых ещё не подтверждены в базе (офлайн, повторно записываются).
     *  На один UUID хранится одна слитая сессия: при втором выходе до сохранения счётчики
     *  и дни складываются, иначе вторая сессия заменила бы первую несохранённую. */
    private final Map<UUID, Session> pendingLogouts = new ConcurrentHashMap<>();

    public ActivityService(DatabaseManager database, PlayerActivityRepository repository,
                           WeeklyActivityDayRepository days, AuditService audit, EconomySettings settings) {
        this.database = database;
        this.repository = repository;
        this.days = days;
        this.audit = audit;
        this.settings = settings;
        WeekId.useZone(WeeklyMath.zoneOf(settings.weeklyFund.timeZone));
    }

    public void applySettings(EconomySettings settings) {
        this.settings = settings;
        WeekId.useZone(WeeklyMath.zoneOf(settings.weeklyFund.timeZone));
    }

    // ---------------------------------------------------------------- events

    public void onPlayerJoined(UUID playerId, String dimension) {
        long now = System.currentTimeMillis();
        onPlayerJoinedAt(playerId, dimension, now);
    }

    void onPlayerJoinedAt(UUID playerId, String dimension, long startMillis) {
        sessions.put(playerId, new Session(playerId, startMillis, startMillis,
                settings.activity.afkTimeoutSeconds * 1000L, dimension,
                settings.weeklyFund.timeZone));
    }

    public void onPlayerMove(UUID playerId, double x, double y, double z,
                             float yaw, float pitch, String dimension) {
        Session session = sessions.get(playerId);
        if (session == null) {
            return;
        }
        if (!dimension.equals(session.dimension)) {
            session.dimension = dimension;
            session.hasPosition = false;
            markActive(session);
            return;
        }
        if (!session.hasPosition) {
            session.setPosition(x, y, z, yaw, pitch);
            return;
        }
        double dx = x - session.x;
        double dy = y - session.y;
        double dz = z - session.z;
        session.setPosition(x, y, z, yaw, pitch);
        if (dx == 0 && dy == 0 && dz == 0) {
            return;
        }
        // Поворот камеры (yaw/pitch) активностью не считается: активность поддерживает только
        // реальное перемещение (накопленное расстояние), и только при пересечении порога.
        session.movedDistance += Math.hypot(dx, Math.hypot(dy, dz));
        double threshold = settings.activity.movementActivityThreshold;
        if (threshold <= 0 || session.movedDistance >= threshold) {
            session.movedDistance = 0;
            markActive(session);
        }
    }

    /** Отметить игрока активным (чат, действие). */
    public void onPlayerActive(UUID playerId) {
        Session session = sessions.get(playerId);
        if (session != null) {
            markActive(session);
        }
    }

    /** Отметить игрока активным в указанный момент (для тестов с фиксированным временем). */
    void onPlayerActiveAt(UUID playerId, long nowMillis) {
        Session session = sessions.get(playerId);
        if (session != null) {
            session.lastActiveAt = nowMillis;
            session.afk = false;
        }
    }

    /** Накапливать счётчики всех онлайн-сессий (вызывается раз в sampleIntervalTicks). */
    public void sampleNow() {
        sampleAt(System.currentTimeMillis());
    }

    void sampleAt(long nowMillis) {
        for (Session session : sessions.values()) {
            sampleSession(session, nowMillis);
        }
    }

    private static void sampleSession(Session session, long nowMillis) {
        long afkTimeoutMillis = session.afkTimeoutMillis;
        long fromSample = session.lastSample;
        long dtMillis = nowMillis - fromSample;
        session.lastSample = nowMillis;
        long dtSeconds = dtMillis / 1000;
        if (dtMillis < 1000) {
            return;
        }
        session.onlineSeconds += dtSeconds;
        if (session.afk) {
            session.afkSeconds += dtSeconds;
            return;
        }
        // Интервал делится на границе таймаута: активная часть длится от lastActiveAt
        // ровно afkTimeoutMillis, а всё, что после неё, относится к AFK. Без этого деления
        // весь интервал засчитывался бы активным, итерация пересечения границы переоценивала
        // активное время.
        long activeUntil = session.lastActiveAt + afkTimeoutMillis;
        long activeFrom = fromSample;
        long activeTo = Math.min(nowMillis, activeUntil);
        long activeMillis = Math.max(0, activeTo - activeFrom);
        long activeSeconds = activeMillis / 1000;
        session.activeSeconds += activeSeconds;
        session.afkSeconds += dtSeconds - activeSeconds;
        if (activeMillis >= 1000) {
            // Активный интервал делится по локальным полуночам (зона фонда): каждая часть
            // относится к своему календарному дню, неделя дня определится по самому дню —
            // активность никогда не смешает недели на границе и не запишется целиком
            // на день конца интервала.
            List<WeeklyMath.DaySegment> segments = WeeklyMath.splitInterval(
                    activeFrom, activeTo, WeeklyMath.zoneOf(session.timeZone));
            for (WeeklyMath.DaySegment segment : segments) {
                long segmentSeconds =
                        (segment.endExclusiveMillis() - segment.fromInclusiveMillis()) / 1000;
                if (segmentSeconds > 0) {
                    String dayKey = Long.toString(segment.date().toEpochDay());
                    session.daySeconds.merge(dayKey, segmentSeconds, Long::sum);
                }
            }
        }
        long afkMillis = dtMillis - activeMillis;
        if (afkMillis > 0) {
            session.afk = true;
        }
    }

    public boolean isAfk(UUID playerId) {
        Session session = sessions.get(playerId);
        return session != null && session.afk;
    }

    // ---------------------------------------------------------------- reads

    /** Полное активное время игрока: в базе + несохранённая часть текущей сессии. */
    public long activeSecondsTotal(UUID playerId) {
        try {
            return database.inTransaction(connection -> {
                long db = repository.find(connection, playerId)
                        .map(PlayerActivityRow::totalActiveSeconds).orElse(0L);
                Session session = sessions.get(playerId);
                return db + (session == null ? 0 : session.activeSeconds);
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения активного времени {}", playerId, e);
            return 0;
        }
    }

    /**
     * Исключён ли игрок из наград (weekly, автоматические milestones). Флаг хранится
     * в {@code player_activity.excluded_from_rewards}; {@link RewardExclusionStatus#NOT_EXCLUDED}
     * при отсутствии записи. Ошибка базы возвращает {@link RewardExclusionStatus#UNKNOWN} —
     * вызывающий обязан удержать награду (fail-closed), а не трактовать ошибку как «не исключён».
     */
    public RewardExclusionStatus excludedFromRewards(UUID playerId) {
        try {
            boolean excluded = database.inTransaction(connection ->
                    repository.find(connection, playerId)
                            .map(PlayerActivityRow::excludedFromRewards).orElse(false));
            return excluded ? RewardExclusionStatus.EXCLUDED : RewardExclusionStatus.NOT_EXCLUDED;
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения флага исключения {}", playerId, e);
            return RewardExclusionStatus.UNKNOWN;
        }
    }

    public AccountFlagUpdateResult setExcludedFromRewards(UUID playerId, boolean excluded) {
        return setExcludedFromRewards(playerId, excluded, null);
    }

    /**
     * Установить флаг исключения из наград (админ-команда {@code exclude-rewards}/
     * {@code include-rewards}). Работает и для игроков без записи активности:
     * создаётся минимальная запись. Успех возвращается явным результатом: при ошибке
     * базы изменение не применено и {@link AccountFlagUpdateResult.Status#DATABASE_ERROR}
     * не должен отображаться как успех. {@code actorId} — кто менял флаг (null = консоль).
     */
    public AccountFlagUpdateResult setExcludedFromRewards(UUID playerId, boolean excluded,
                                                          UUID actorId) {
        try {
            database.inTransaction(connection -> {
                PlayerActivityRow existing = repository.find(connection, playerId).orElse(null);
                long now = System.currentTimeMillis();
                PlayerActivityRow row = existing != null
                        ? new PlayerActivityRow(existing.playerId(), existing.firstSeenAt(),
                                existing.lastSeenAt(), existing.totalOnlineSeconds(),
                                existing.totalActiveSeconds(), existing.totalAfkSeconds(),
                                existing.currentWeekId(), existing.lastActivityAt(),
                                existing.lastDimension(), excluded)
                        : new PlayerActivityRow(playerId, now, now, 0L, 0L, 0L,
                                WeekId.current(), now, "minecraft:overworld", excluded);
                repository.upsert(connection, database.dialect(), row);
                return null;
            });
            if (audit != null) {
                // Запись аудита — отдельная транзакция после подтверждённого изменения.
                audit.record(AuditEventType.EXCLUSION_CHANGED, AuditSeverity.INFO, playerId,
                        actorId, AuditActorType.of(actorId), null, "excluded=" + excluded);
            }
            return new AccountFlagUpdateResult(AccountFlagUpdateResult.Status.OK, excluded, null);
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка установки флага исключения {}", playerId, e);
            return new AccountFlagUpdateResult(AccountFlagUpdateResult.Status.DATABASE_ERROR,
                    false, "db_error");
        }
    }

    public Optional<ActivityInfo> info(UUID playerId) {
        try {
            return database.inTransaction(connection -> {
                PlayerActivityRow row = repository.find(connection, playerId).orElse(null);
                Session session = sessions.get(playerId);
                long online = (row == null ? 0 : row.totalOnlineSeconds())
                        + (session == null ? 0 : session.onlineSeconds);
                long active = (row == null ? 0 : row.totalActiveSeconds())
                        + (session == null ? 0 : session.activeSeconds);
                long afk = (row == null ? 0 : row.totalAfkSeconds())
                        + (session == null ? 0 : session.afkSeconds);
                boolean afkNow = session != null && session.afk;
                long lastActivityAt = row != null ? row.lastActivityAt() : 0;
                String lastDimension = row != null ? row.lastDimension()
                        : (session == null ? null : session.dimension);
                // Активность за текущую календарную неделю считается по дням
                // (weekly_activity_days): сохранённая часть + несохранённые дни сессии.
                String week = WeekId.current();
                long weekly = 0;
                for (WeeklyActivityDayRow day : days.listByWeekAndPlayer(connection, week, playerId)) {
                    weekly += day.activeSeconds();
                }
                if (session != null) {
                    for (Map.Entry<String, Long> entry : session.daySeconds.entrySet()) {
                        if (week.equals(WeeklyMath.weekOfDay(entry.getKey()))) {
                            weekly += entry.getValue();
                        }
                    }
                }
                return Optional.of(new ActivityInfo(playerId, online, active, afk, week, weekly,
                        afkNow, lastActivityAt, lastDimension));
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения активности {}", playerId, e);
            return Optional.empty();
        }
    }

    /** Снимок активности игрока для команды {@code /money activity}. */
    public record ActivityInfo(UUID playerId, long totalOnlineSeconds, long totalActiveSeconds,
                               long totalAfkSeconds, String currentWeekId, long weeklyActiveSeconds,
                               boolean afkNow, long lastActivityAt, String lastDimension) {
    }

    // ---------------------------------------------------------------- persistence

    /**
     * Записать все онлайн-сессии и несохранённые выходы в базу (периодически и при остановке).
     * Возвращает {@code true} только после подтверждённого commit: счётчики очищаются уже
     * после фиксации; при неудаче данные остаются в памяти (с перенесённой точкой отсчёта),
     * и следующая попытка запишет их целиком, без дублей.
     */
    public boolean persistAll() {
        return persistAllAt(System.currentTimeMillis());
    }

    /** Записать все сессии в базу в указанный момент времени (для тестов). */
    boolean persistAllAt(long nowMillis) {
        List<Session> toSave = new ArrayList<>(sessions.size() + pendingLogouts.size());
        toSave.addAll(sessions.values());
        toSave.addAll(pendingLogouts.values());
        if (toSave.isEmpty()) {
            return true;
        }
        String week = WeekId.current();
        try {
            database.inTransaction(connection -> {
                for (Session session : toSave) {
                    persist(connection, session, week, nowMillis);
                }
                return null;
            });
        } catch (DatabaseException e) {
            // Транзакция не подтверждена (недоступна база или ошибка commit): счётчики не
            // очищаем. Точку отсчёта переносим на момент попытки, чтобы время не считалось
            // дважды на следующих сэмплах, а сами накопленные секунды остались в памяти.
            VEconomyMod.LOGGER.error("Ошибка сохранения активности: данные удержаны в памяти "
                    + "и будут повторены на следующем сохранении", e);
            for (Session session : toSave) {
                session.lastSample = nowMillis;
            }
            return false;
        }
        // Транзакция подтверждена: сохранённые значения можно освободить.
        for (Session session : toSave) {
            pendingLogouts.remove(session.playerId);
            session.lastSample = nowMillis;
            session.daySeconds.clear();
            session.onlineSeconds = 0;
            session.activeSeconds = 0;
            session.afkSeconds = 0;
        }
        return true;
    }

    /** Закрыть сессию игрока (выход): финальный сэмпл и запись. */
    public void onPlayerLeft(UUID playerId) {
        onPlayerLeftAt(playerId, System.currentTimeMillis());
    }

    void onPlayerLeftAt(UUID playerId, long now) {
        Session session = sessions.get(playerId);
        if (session == null) {
            return;
        }
        // Сэмплируем именно эту сессию ДО удаления: иначе последний несэмплированный
        // фрагмент (от lastSample до выхода) потерялся бы из-за sampleNow() по sessions.
        sampleSession(session, now);
        sessions.remove(playerId);
        // Данные выхода удерживаем в памяти до подтверждённого сохранения: если запись не
        // удалась, сессия остаётся в pendingLogouts и попадёт в базу на следующем persistAll().
        // Если до сохранения был ещё один выход того же игрока (зашёл и вышел снова), сессии
        // сливаются в одну, чтобы первая несохранённая не потерялась при put.
        pendingLogouts.compute(playerId, (id, existing) -> {
            if (existing == null) {
                return session;
            }
            existing.merge(session);
            return existing;
        });
        saveLogout(session, now);
    }

    private void saveLogout(Session session, long now) {
        String week = WeekId.current();
        try {
            database.inTransaction(connection -> {
                persist(connection, session, week, now);
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка сохранения активности при выходе {}: данные "
                    + "удержаны до следующего сохранения", session.playerId, e);
            return;
        }
        pendingLogouts.remove(session.playerId);
        session.lastSample = now;
        session.daySeconds.clear();
        session.onlineSeconds = 0;
        session.activeSeconds = 0;
        session.afkSeconds = 0;
    }

    /** Записать счётчики сессии в базу внутри транзакции (без очистки памяти). */
    private void persist(Connection connection, Session session, String week, long now) {
        PlayerActivityRow existing = repository.find(connection, session.playerId).orElse(null);
        long firstSeen = existing == null ? now : existing.firstSeenAt();
        boolean excluded = existing != null && existing.excludedFromRewards();
        PlayerActivityRow merged = new PlayerActivityRow(
                session.playerId,
                firstSeen,
                now,
                (existing == null ? 0 : existing.totalOnlineSeconds()) + session.onlineSeconds,
                (existing == null ? 0 : existing.totalActiveSeconds()) + session.activeSeconds,
                (existing == null ? 0 : existing.totalAfkSeconds()) + session.afkSeconds,
                week,
                session.lastActiveAt,
                session.dimension,
                excluded);
        repository.upsert(connection, database.dialect(), merged);
        // Накопленная по дням активность: неделя дня берётся по самому дню, а не по текущей
        // неделе сессии — это гарантирует корректную атрибуцию на границе недель.
        if (!session.daySeconds.isEmpty()) {
            for (Map.Entry<String, Long> entry : session.daySeconds.entrySet()) {
                String dayKey = entry.getKey();
                String dayWeek = WeeklyMath.weekOfDay(dayKey);
                if (dayWeek != null) {
                    days.addSeconds(connection, database.dialect(), session.playerId,
                            dayWeek, dayKey, entry.getValue());
                }
            }
        }
    }

    private static void markActive(Session session) {
        session.lastActiveAt = System.currentTimeMillis();
        session.afk = false;
    }

    /** Активная сессия игрока в памяти. */
    private static final class Session {
        final UUID playerId;
        final long afkTimeoutMillis;
        final String timeZone;
        long lastSample;
        long lastActiveAt;
        long onlineSeconds;
        long activeSeconds;
        long afkSeconds;
        boolean afk;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        boolean hasPosition;
        double movedDistance;
        String dimension;
        final Map<String, Long> daySeconds = new ConcurrentHashMap<>();

        Session(UUID playerId, long lastSample, long lastActiveAt, long afkTimeoutMillis,
                String dimension, String timeZone) {
            this.playerId = playerId;
            this.lastSample = lastSample;
            this.lastActiveAt = lastActiveAt;
            this.afkTimeoutMillis = afkTimeoutMillis;
            this.dimension = dimension;
            this.timeZone = timeZone;
        }

        void setPosition(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.hasPosition = true;
        }

        /** Слить несохранённый выход того же игрока с уже удержанным: счётчики и дни складываются. */
        void merge(Session other) {
            onlineSeconds += other.onlineSeconds;
            activeSeconds += other.activeSeconds;
            afkSeconds += other.afkSeconds;
            for (Map.Entry<String, Long> entry : other.daySeconds.entrySet()) {
                daySeconds.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
            if (other.lastActiveAt > lastActiveAt) {
                lastActiveAt = other.lastActiveAt;
                dimension = other.dimension;
            }
            lastSample = Math.max(lastSample, other.lastSample);
        }
    }
}
