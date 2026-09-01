package ro.server.orderplugin.i18n;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import ro.server.orderplugin.OrderPlugin;

/**
 * Persistently stores the language players chose with {@code /orderlang}.
 *
 * <p>A single small YAML file ({@code lang-players.yml}) is enough: one
 * language code is kept per record, and the file is loaded entirely into
 * memory on startup. Not requiring a database keeps the language choice
 * independent of the storage type ({@code storage.type}) — the choice stays
 * persistent even on a server running in MEMORY mode.</p>
 */
public final class LangStorage {

    private final OrderPlugin plugin;
    private final File file;
    private FileConfiguration config;

    public LangStorage(OrderPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "lang-players.yml");
        reload();
    }

    public void reload() {
        this.config = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    /** The stored language code, or null if there isn't one. */
    public String get(UUID uuid) {
        return config.getString(uuid.toString());
    }

    /** Saves the language; if {@code code} is null the record is removed (falls back to automatic resolution). */
    public synchronized void set(UUID uuid, String code) {
        config.set(uuid.toString(), code);
        save();
    }

    private void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "lang-players.yml could not be saved!", e);
        }
    }
}
