package ro.server.orderplugin.util;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ro.server.orderplugin.OrderPlugin;

public class PendingMessageManager implements Listener {
    private final OrderPlugin plugin;
    private final File file;
    private FileConfiguration config;

    public PendingMessageManager(OrderPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pending_messages.yml");
        this.loadConfig();
    }

    public void loadConfig() {
        this.config = !this.file.exists() ? new YamlConfiguration() : YamlConfiguration.loadConfiguration(this.file);
    }

    public synchronized void saveConfig() {
        try {
            this.config.save(this.file);
        } catch (IOException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Nu s-a putut salva pending_messages.yml!", e);
        }
    }

    public synchronized void addMessage(UUID playerId, String message) {
        List<String> messages = this.config.getStringList(playerId.toString());
        messages.add(message);
        this.config.set(playerId.toString(), messages);
        this.saveConfig();
    }

    public synchronized List<String> getAndClearMessages(UUID playerId) {
        List<String> messages = this.config.getStringList(playerId.toString());
        if (!messages.isEmpty()) {
            this.config.set(playerId.toString(), null);
            this.saveConfig();
        }
        return messages;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.plugin.getSchedulerAdapter().runOnEntityLater(this.plugin, player, () -> {
            List<String> messages = this.getAndClearMessages(player.getUniqueId());
            for (String message : messages) {
                player.sendMessage(message);
            }
        }, 40L);
    }
}
