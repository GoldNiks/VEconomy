// VEconomy: разовая компенсация за квесты, пройденные до установки мода.
// Файл кладётся в kubejs/server_scripts/ на сервере.
//
// Суммы задаются не здесь, а в конфиге config/VMods/VEconomy/veconomy-quests.json
// (таблица по главам). Скрипт лишь запускает компенсацию один раз.
//
// Безопасность:
//   - Скрипт срабатывает один раз (флаг в persistentData мира).
//   - Каждому игроку начисление защищено идемпотентным ключом в БД
//     (questcomp:v1:<uuid>) — даже при повторном запуске дубля не будет.

// priority: high

const MARKER_KEY = 'veconomy_quest_compensation_done';

ServerEvents.loaded(event => {
    const marker = event.server.persistentData;
    if (marker.getBoolean(MARKER_KEY)) {
        console.info('[VEconomy] Компенсация за квесты уже выполнена ранее, пропускаем');
        return;
    }

    const result = VEconomy.compensatePastQuests();
    console.info('[VEconomy] ' + result);

    // Отметить как выполненную только если не было ошибки инициализации.
    if (result !== 'НЕ ИНИЦИАЛИЗИРОВАНО' && !result.startsWith('Ошибка')) {
        marker.putBoolean(MARKER_KEY, true);
    }
});
