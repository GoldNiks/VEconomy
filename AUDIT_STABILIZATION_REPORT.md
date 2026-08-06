# VEconomy — Стабилизационный проход аудит-модуля (round 4)

Дата: 2026-08-07
База: `7130cc2` (`fix: audit review round 3 — ...`)
Ветка: `master` → переименована в `main` (чит для origin/main)
Статус: **BUILD SUCCESSFUL**, 238 тестов, 0 провалов, 6 skipped (MySQL-интеграция, нет Docker).

## Что было сделано

### Задача 1 — `writeSignal` больше не скрывает ошибки записи
**Файл:** `src/main/java/com/valorcraft/veconomy/audit/SuspicionScanner.java`
- Убран `try/catch (DatabaseException)`, который превращал сбой вставки сигнала в «успех». `DatabaseException` теперь пробрасывается наверх — внешний сканер получает `failed(error)`, а не `completed`.
- Осталось единственное проглоченное сужение: «дубль собственного dedupe_key» — это штатная ситуация (уже записанный инцидент), а не ошибка БД; комментарий это документирует.

### Задача 2 — `EconomyCore.shutdown()` сбрасывает очередь и честно закрывается
**Файл:** `src/main/java/com/valorcraft/veconomy/EconomyCore.java`
- `activity.persistAll()` → `auditService.shutdown()` (с полным тайм-аут воркеров).
- `auditService.flushPending()` — ОДНА ограниченная попытка дописать отложенные события.
- Перед собарем: если идеи, даёте «before close» проверка `AuditService.health()`: при `pendingRetries > 0` — `LOGGER.error` с числом событий + последняя ошибка.
- Только после этого `database.close()`. Восстановлена вариация «не потерять данные безмолвно».

### Задача 3 — `maxTransfersPerScan` безопасный int
**Файл:** `src/main/java/com/valorcraft/veconomy/config/AuditConfig.java`
- Константа `MAX_TRANSFERS_PER_SCAN = 1_000_000`, дефолт `DEFAULT_MAX_TRANSFERS_PER_SCAN = 200_000L`.
- Новый метод `scanLimit()`: читает `maxTransfersPerDay` как `long`, **срещивает** в `[1 .. 1_000_000]`; вне диапазона — кламп + `warning`, чтобы конфиг не сломал (память) сканы.
- `defaults()` обновлены.

### Задача 4 — персональный скан с реальным SQL LIMIT
**Файлы:** `persistence/TransactionRepository.java`, `audit/SuspicionScanner.java`
- новый метод `transfersSinceForPlayerLimited(...)`: `SELECT ... ORDER BY created_at DESC, transaction_id DESC LIMIT ?` (работает и в SQLite, и в MySQL).
- В скане игровой ветки запрос теперь `LIMIT max+1`, затем `subList(0, max)` — без загрузки всего стека в память; при превышении выставлен флаг `limited` и `LOGGER.warning`.
- Исправлена опечатка `transaction(` → `inTransaction(` в ветке периодического скана и восстановлен консистентный SQL.

### Задача 5 — дедап-ключи инцидентов не зависят от «bucket» времени
**Файл:** `audit/SuspicionScanner.java`
- Дубликация устранена: отдельные инциденты (`OVERSIZED`, `RAPID_FORWARDING`, `LOOP`) теперь используют `incidentDedupeKey` без bucket времени; при окне/скане-переходе дедупликация выживает («SurvivesBucketChange»).
- Агрегатные сигналы (`spam`, `roundtrip`) осталось по bucket — это правильный scope: они естественно группируются по окну.

### Тесты (новые, все зелёные)
- `SuspicionScannerTest`:
  - `maxTransfersAboveIntRangeIsClampedToSafeDefault`, `maxTransfersZeroOrNegativeIsClamped`,
  - `scanRecordingFailureDeliversFailedInsteadOfSilentCompleted` (DROP TABLE audit_events → `failed`),
  - `oversizedSignalDedupeSurvivesWindowBucketChange`, `rapidForwardingDedupeSurvivesScanBucketChange`,
  - `loopSignalDeduplicatedAcrossWindowBucketChange`, `aggregateSignalStillBucketScoped`,
  - `maxTransfersPerScanLimitsAndFlagsSummary`.
- `TransactionRepositoryTest`: `transfersSinceForPlayerLimitedReadsOnlyLimitRows`, `...OrderIsStableByTimeThenTransactionId`.
- `AuditServiceTest`: `shutdownOneShotFlushOnDeadDatabaseReportsQueueInHealth` (одна попытка flush не виснет, очередь видна в health), плюс проверка `failedWriteIsQueuedAndFlushedAfterRecovery`.

## Результат сборки
```
./gradlew clean test build --no-daemon --console=plain
BUILD SUCCESSFUL in 1m12s
tests=238  failures=0  skipped=6   (skipped = MySqlIntegrationTest; Docker недоступен)
```
Артефакты:
- `build/libs/VEconomy-1.20.1-1.0.0-all.jar` (~18.2 МБ, jarJar с зависимостями)
- `build/libs/VEconomy-1.20.1-1.0.0.jar`, `...-sources.jar`

MySQL-интеграционные тесты (старт реальной БД, `mysqlIntegrationTest`) не исполнены:
Docker-раз под этой машины не установлен. Код покрыт SQLite unit-тестами и статическим планом v7-миграции для MySQL.

## Изменённые файлы
- `src/main/java/com/valorcraft/veconomy/EconomyCore.java` (задача 2)
- `src/main/java/com/valorcraft/veconomy/audit/SuspicionScanner.java` (задачи 1, 4, 5)
- `src/main/java/com/valorcraft/veconomy/config/AuditConfig.java` (задача 3)
- `src/main/java/com/valorcraft/veconomy/persistence/TransactionRepository.java` (задача 4)
- тесты: `AuditServiceTest`, `SuspicionScannerTest`, `TransactionRepositoryTest`

Замечание: `gradle.properties` — правка локально смонтирован JDK-пути (`org.gradle.java.home`); в коммит не вклячена, чтобы не конфликтить сборку на чужих машинах. Для сборки нужен JDK 21 на `C:/Program Files/Java/jdk-21.0.11`.
</content>
</invoke>