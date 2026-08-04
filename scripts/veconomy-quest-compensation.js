// VEconomy: разовая компенсация за квесты, пройденные до установки мода.
// Файл кладётся в kubejs/server_scripts/ на сервере.
//
// Настройка:
//   MONEY_PER_QUEST — сумма (в минимальных единицах валюты) за каждый пройденный квест.
//   При decimalPlaces = 0 значение 100 = «100 монет», при decimalPlaces = 2 — «1.00».
//
// Безопасность:
//   - Скрипт срабатывает один раз (флаг в persistentData мира) — повторный запуск
//     на следующих стартах сервера ничего не выдаст.
//   - Каждому игроку деньги начисляются с идемпотентным ключом (questcomp:v1:<uuid>):
//     даже если флаг сбросить и запустить снова, дубль не пройдёт.
//   - Сумма берётся по количеству выполненных квестов команды на момент первого запуска.

// priority: high

const MONEY_PER_QUEST = 100;

const FTBQuestsAPI = Java.loadClass('dev.ftb.mods.ftbquests.api.FTBQuestsAPI');
const FTBTeamsAPI = Java.loadClass('dev.ftb.mods.ftbteams.api.FTBTeamsAPI');

const MARKER_KEY = 'veconomy_quest_compensation_done';

ServerEvents.loaded(event => {
    const marker = event.server.persistentData;
    if (marker.getBoolean(MARKER_KEY)) {
        console.info('[VEconomy] Компенсация за квесты уже выполнена ранее, пропускаем');
        return;
    }

    const questFile = FTBQuestsAPI.api().getQuestFile(true);
    if (questFile == null) {
        console.warn('[VEconomy] Квестовый файл не найден, компенсация отменена');
        return;
    }

    const quests = [];
    for (const chapter of questFile.getAllChapters()) {
        for (const quest of chapter.getQuests()) {
            quests.push(quest);
        }
    }
    console.info('[VEconomy] Квестов в файле: ' + quests.length);

    const teamManager = FTBTeamsAPI.api().getManager();
    let paidTeams = 0;
    let paidPlayers = 0;
    let paidTotal = 0;

    for (const teamData of questFile.getAllTeamData()) {
        const teamOpt = teamManager.getTeamByID(teamData.getTeamId());
        if (!teamOpt.isPresent()) {
            continue;
        }
        const team = teamOpt.get();

        let completed = 0;
        for (const quest of quests) {
            if (teamData.isCompleted(quest)) {
                completed++;
            }
        }
        if (completed === 0) {
            continue;
        }

        const amount = completed * MONEY_PER_QUEST;
        for (const memberId of team.getMembers()) {
            const result = VEconomy.add(
                memberId, amount,
                'Компенсация за пройденные квесты',
                'questcomp:v1:' + memberId
            );
            if (VEconomy.ok(result)) {
                paidPlayers++;
                paidTotal += amount;
                console.info('[VEconomy] Начислено ' + amount + ' игроку ' + memberId + ' (квестов: ' + completed + ')');
            } else {
                console.warn('[VEconomy] Игрок ' + memberId + ': ' + result);
            }
        }
        paidTeams++;
    }

    marker.putBoolean(MARKER_KEY, true);
    console.info('[VEconomy] Компенсация завершена: команд ' + paidTeams
        + ', игроков ' + paidPlayers + ', сумма ' + paidTotal);
});
