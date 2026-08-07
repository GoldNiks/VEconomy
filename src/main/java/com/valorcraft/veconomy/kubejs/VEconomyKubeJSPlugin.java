package com.valorcraft.veconomy.kubejs;

import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;

/**
 * KubeJS-плагин VEconomy. Обнаруживается автоматически через ресурс
 * {@code kubejs.plugins.txt} в корне jar мода.
 * <p>
 * Добавляет в скрипты биндинг {@code VEconomy} со статическими методами
 * (см. {@link VEconomyBindings}), например:
 * <pre>{@code
 * VEconomy.add(player, 50, 'стартовый бонус')
 * VEconomy.getBalance(player)
 * }</pre>
 */
public final class VEconomyKubeJSPlugin extends KubeJSPlugin {

    @Override
    public void registerBindings(BindingsEvent event) {
        event.add("VEconomy", VEconomyBindings.class);
    }
}
