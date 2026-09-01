package ro.server.orderplugin.i18n;

import java.util.UUID;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import ro.server.orderplugin.OrderPlugin;

/**
 * Loads the player's saved language choice on join, drops it from memory on quit.
 *
 * <p>The read happens from disk, but since the file is already kept in memory,
 * the join handling doesn't block the main thread.</p>
 */
public final class LangListener implements Listener {

    private final OrderPlugin plugin;

    public LangListener(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String saved = plugin.getLangStorage().get(id);
        if (saved != null && plugin.getLanguage().isSupported(saved)) {
            plugin.getLanguage().setOverride(id, saved);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        plugin.getLanguage().unload(id);
        if (plugin.levels() != null) plugin.levels().unload(id);
    }
}
