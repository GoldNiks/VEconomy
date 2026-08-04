package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;

import java.sql.Connection;
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
    private volatile EconomySettings settings;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public ActivityService(DatabaseManager database, PlayerActivityRepository repository,
                           EconomySettings settings) {
        this.database = database;
        this.repository = repository;
        this.settings = settings;
    }

    public void applySettings(EconomySettings settings) {
        this.settings = settings;
    }

    // ---------------------------------------------------------------- events

    public void onPlayerJoined(UUID playerId, String dimension) {
        long now = System.currentTimeMillis();
        onPlayerJoinedAt(playerId, dimension, now);
    }

    void onPlayerJoinedAt(UUID playerId, String dimension, long startMillis) {
        sessions.put(playerId, new Session(playerId, startMillis, startMillis,
                settings.activity.afkTimeoutSeconds * 1000L, dimension));
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
        long activeMillis = Math.max(0, Math.min(dtMillis, activeUntil - fromSample));
        session.activeSeconds += activeMillis / 1000;
        long afkMillis = dtMillis - activeMillis;
        session.afkSeconds += afkMillis / 1000;
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
                long weekly = (row == null ? 0 : row.weeklyActiveSeconds())
                        + (session == null ? 0 : session.activeSeconds);
                boolean afkNow = session != null && session.afk;
                long lastActivityAt = row != null ? row.lastActivityAt() : 0;
                String lastDimension = row != null ? row.lastDimension()
                        : (session == null ? null : session.dimension);
                String week = row != null && row.currentWeekId() != null
                        ? row.currentWeekId() : WeekId.current();
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

    /** Записать все онлайн-сессии в базу (периодически и при остановке). */
    public void persistAll() {
        if (sessions.isEmpty()) {
            return;
        }
        String week = WeekId.current();
        long now = System.currentTimeMillis();
        try {
            database.inTransaction(connection -> {
                for (Session session : sessions.values()) {
                    persist(connection, session, week, now);
                }
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка сохранения активности", e);
        }
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
        String week = WeekId.current();
        try {
            database.inTransaction(connection -> {
                persist(connection, session, week, now);
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка сохранения активности при выходе {}", playerId, e);
        }
    }

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
                (existing == null ? 0 : existing.weeklyActiveSeconds()) + session.activeSeconds,
                session.lastActiveAt,
                session.dimension,
                excluded);
        repository.upsert(connection, database.dialect(), merged);
        session.onlineSeconds = 0;
        session.activeSeconds = 0;
        session.afkSeconds = 0;
        session.lastSample = now;
    }

    private static void markActive(Session session) {
        session.lastActiveAt = System.currentTimeMillis();
        session.afk = false;
    }

    /** Активная сессия игрока в памяти. */
    private static final class Session {
        final UUID playerId;
        final long afkTimeoutMillis;
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

        Session(UUID playerId, long lastSample, long lastActiveAt, long afkTimeoutMillis,
                String dimension) {
            this.playerId = playerId;
            this.lastSample = lastSample;
            this.lastActiveAt = lastActiveAt;
            this.afkTimeoutMillis = afkTimeoutMillis;
            this.dimension = dimension;
        }

        void setPosition(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.hasPosition = true;
        }
    }
}
