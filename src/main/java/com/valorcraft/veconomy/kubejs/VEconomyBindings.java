package com.valorcraft.veconomy.kubejs;

import com.valorcraft.veconomy.EconomyCore;
import com.valorcraft.veconomy.api.EscrowApi;
import com.valorcraft.veconomy.api.EscrowResult;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.economy.TreasuryService;
import com.valorcraft.veconomy.util.PlayerResolver;
import com.valorcraft.veconomy.util.ServerHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Набор статических методов, доступных из KubeJS-скриптов как {@code VEconomy}.
 * <p>
 * Принимаемые представления игрока: {@link Player}/{@link ServerPlayer}, {@link UUID}
 * или строка (ник/UUID). Возвращаемые коды операций — строки-статусы
 * (например {@code SUCCESS}, {@code INSUFFICIENT_FUNDS}) либо {@code false}/0.
 * <p>
 * Используется будущим аукционом и другими скриптовыми механиками сервера.
 */
public final class VEconomyBindings {

    private VEconomyBindings() {}

    /** Текущий баланс игрока в минимальных единицах (0, если аккаунта нет). */
    public static long getBalance(Object player) {
        UUID id = resolve(player);
        return id == null || !EconomyCore.isStarted() ? 0L : EconomyCore.accounts().getBalance(id);
    }

    /** {@code true}, если на балансе не меньше {@code amount} минимальных единиц. */
    public static boolean has(Object player, long amount) {
        UUID id = resolve(player);
        return id != null && EconomyCore.isStarted() && EconomyCore.accounts().has(id, amount);
    }

    /** Начислить {@code amount} игроку. Возвращает код статуса операции. */
    public static String add(Object player, long amount, String reason) {
        return add(player, amount, reason, null);
    }

    /**
     * Начислить {@code amount} игроку с ключом идемпотентности. Повторный вызов с тем же
     * ключом вернёт {@code DUPLICATE_OPERATION} и не зачислит деньги повторно.
     */
    public static String add(Object player, long amount, String reason, String idempotencyKey) {
        UUID id = resolve(player);
        if (id == null || !EconomyCore.isStarted()) {
            return "PLAYER_NOT_FOUND";
        }
        return EconomyCore.api().deposit(id, amount,
                TransactionContext.of(TransactionType.PLUGIN_OPERATION, null,
                        reason == null ? "kubejs:add" : reason, idempotencyKey))
                .status().name();
    }

    /** Списать {@code amount} у игрока. Возвращает код статуса операции. */
    public static String withdraw(Object player, long amount, String reason) {
        return withdraw(player, amount, reason, null);
    }

    /** Списать {@code amount} у игрока с ключом идемпотентности. */
    public static String withdraw(Object player, long amount, String reason, String idempotencyKey) {
        UUID id = resolve(player);
        if (id == null || !EconomyCore.isStarted()) {
            return "PLAYER_NOT_FOUND";
        }
        return EconomyCore.api().withdraw(id, amount,
                TransactionContext.of(TransactionType.PLUGIN_OPERATION, id,
                        reason == null ? "kubejs:withdraw" : reason, idempotencyKey))
                .status().name();
    }

    /** Перевести {@code amount} от {@code from} к {@code to}. Возвращает код статуса операции. */
    public static String transfer(Object from, Object to, long amount, String reason) {
        UUID fromId = resolve(from);
        UUID toId = resolve(to);
        if (fromId == null || toId == null || !EconomyCore.isStarted()) {
            return "PLAYER_NOT_FOUND";
        }
        TransactionResult result = EconomyCore.api().transfer(fromId, toId, amount,
                TransactionContext.of(TransactionType.PLAYER_TRANSFER, fromId,
                        reason == null ? "kubejs:transfer" : reason));
        return result.status().name();
    }

    /** Отформатировать сумму минимальных единиц в читаемый вид (напр. «1 500 монет»). */
    public static String format(long amount) {
        return EconomyCore.isStarted() ? EconomyCore.formatter().format(amount) : String.valueOf(amount);
    }

    /** Баланс системной казны. */
    public static long treasury() {
        return EconomyCore.isStarted()
                ? EconomyCore.accounts().getBalance(TreasuryService.TREASURY_UUID) : 0L;
    }

    /**
     * Разовая компенсация за квесты, пройденные до установки мода (см. FTB Quests).
     * Возвращает краткий отчёт. Безопасно вызывать многократно — повторной выдачи не будет.
     */
    public static String compensatePastQuests() {
        return com.valorcraft.veconomy.integration.ftbquests.FTBQuestsIntegration.compensatePastQuests();
    }

    /** Награда за один квест в главе {@code chapterTitle} (из конфига наград), минимальные единицы. */
    public static long questReward(String chapterTitle) {
        return com.valorcraft.veconomy.integration.ftbquests.QuestRewardConfig.rewardForChapter(chapterTitle);
    }

    /** Перечитать конфиг наград по главам. Возвращает отчёт. */
    public static String reloadQuestRewards() {
        try {
            com.valorcraft.veconomy.integration.ftbquests.FTBQuestsIntegration.reloadRewards();
            return "OK";
        } catch (Throwable t) {
            return "Ошибка: " + t;
        }
    }

    /** Зарезервировать {@code amount} у игрока под {@code referenceId}. Код статуса. */
    public static String escrowReserve(Object player, long amount, String referenceId, String reason) {
        UUID id = resolve(player);
        if (id == null || !EconomyCore.isStarted()) {
            return "PLAYER_NOT_FOUND";
        }
        EscrowResult result = escrow().reserveMoney(id, amount, referenceId,
                TransactionContext.of(TransactionType.ESCROW_RESERVE, id,
                        reason == null ? "kubejs:escrow:reserve" : reason));
        return result.status().name();
    }

    /** Выдать зарезервированные средства получателю. Код статуса. */
    public static String escrowCapture(String referenceId, Object recipient, String reason) {
        UUID id = resolve(recipient);
        if (id == null || !EconomyCore.isStarted()) {
            return "PLAYER_NOT_FOUND";
        }
        EscrowResult result = escrow().captureMoney(referenceId, id,
                TransactionContext.of(TransactionType.ESCROW_CAPTURE, id,
                        reason == null ? "kubejs:escrow:capture" : reason));
        return result.status().name();
    }

    /** Вернуть зарезервированные средства владельцу. Код статуса. */
    public static String escrowRelease(String referenceId, String reason) {
        if (!EconomyCore.isStarted()) {
            return "NOT_STARTED";
        }
        EscrowResult result = escrow().releaseMoney(referenceId,
                TransactionContext.of(TransactionType.ESCROW_RELEASE, null,
                        reason == null ? "kubejs:escrow:release" : reason));
        return result.status().name();
    }

    /** Успешно ли закончился статус операции (SUCCESS). */
    public static boolean ok(String status) {
        return status != null && status.equals("SUCCESS");
    }

    /**
     * Выдать EXTERNAL-милстоун из доверенного скрипта. Идемпотентность обеспечивается
     * ключом: повторный вызов с тем же ключом вернёт {@code DUPLICATE_OPERATION}.
     * Деньги начисляются только если милстоун имеет тип EXTERNAL.
     */
    public static String milestoneGrant(Object player, String milestoneId, String idempotencyKey) {
        UUID id = resolve(player);
        if (id == null || !EconomyCore.isStarted()) {
            return "PLAYER_NOT_FOUND";
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return "INVALID_KEY";
        }
        return EconomyCore.milestones().grantExternal(id, milestoneId, idempotencyKey)
                .status().name();
    }

    /** Выдан ли EXTERNAL-милстоун игроку. */
    public static boolean milestoneClaimed(Object player, String milestoneId) {
        UUID id = resolve(player);
        return id != null && EconomyCore.isStarted()
                && EconomyCore.milestones().isClaimed(id, milestoneId);
    }

    private static EscrowApi escrow() {
        return EconomyCore.escrow();
    }

    /** Привести объект к UUID игрока; null, если не удалось. */
    private static UUID resolve(Object player) {
        if (player == null) {
            return null;
        }
        if (player instanceof Player p) {
            return p.getUUID();
        }
        if (player instanceof UUID uuid) {
            return uuid;
        }
        if (player instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                // дальше — по нику
            }
            if (ServerHolder.get() == null) {
                return null;
            }
            PlayerResolver.Resolved resolved = PlayerResolver.resolve(ServerHolder.get(), s);
            return resolved.exists() ? resolved.uuid() : null;
        }
        return null;
    }
}
