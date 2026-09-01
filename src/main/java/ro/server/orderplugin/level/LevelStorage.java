package ro.server.orderplugin.level;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import ro.server.orderplugin.OrderPlugin;

/**
 * Stores players' level/xp records ({@code levels-players.yml}).
 *
 * <p>Same rationale as {@link ro.server.orderplugin.i18n.LangStorage}: a single
 * small YAML file, independent of {@code storage.type}. Even on a server
 * running in MEMORY mode, levels persist and the server owner doesn't need to
 * set up a database.</p>
 *
 * <p>Writing is <b>deferred</b>: instead of writing to disk on every xp gain,
 * the change is flagged and saved periodically. A player delivering orders can
 * gain xp several times a second; writing the file every time would block the
 * main thread.</p>
 */
public final class LevelStorage {

    private final OrderPlugin plugin;
    private final File file;
    private FileConfiguration config;
    private volatile boolean dirty = false;

    public LevelStorage(OrderPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "levels-players.yml");
        reload();
    }

    public void reload() {
        this.config = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    /** Null if there is no record. */
    public PlayerLevel load(UUID uuid) {
        ConfigurationSection section = config.getConfigurationSection(uuid.toString());
        if (section == null) return null;
        return new PlayerLevel(uuid,
                section.getDouble("xp", 0d),
                Math.max(1, section.getInt("level", 1)),
                section.getDouble("daily-xp", 0d),
                section.getLong("daily-reset", System.currentTimeMillis()));
    }

    public synchronized void save(PlayerLevel value) {
        if (value == null) return;
        String key = value.uuid().toString();
        config.set(key + ".xp", value.xp());
        config.set(key + ".level", value.level());
        config.set(key + ".daily-xp", value.dailyXp());
        config.set(key + ".daily-reset", value.dailyReset());
        dirty = true;
    }

    /**
     * Writes pending changes to disk. Leaves the file untouched if nothing changed.
     *
     * <p>The write happens <b>in the background</b>: as the file grows (thousands
     * of players), a synchronous write would block the cleanup task, and thus the
     * main thread. The text to be written is produced on the main thread, so
     * {@code config} can't be mutated from elsewhere while it's being read in the
     * background.</p>
     */
    public void flush() {
        String data;
        synchronized (this) {
            if (!dirty) return;
            dirty = false;
            data = config.saveToString();
        }
        plugin.getSchedulerAdapter().runAsync(plugin, () -> write(data));
    }

    /** On shutdown: since the scheduler has stopped, the write happens on this thread. */
    public void flushNow() {
        String data;
        synchronized (this) {
            if (!dirty) return;
            dirty = false;
            data = config.saveToString();
        }
        write(data);
    }

    private synchronized void write(String data) {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            java.nio.file.Files.writeString(temp.toPath(), data, java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.move(temp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "levels-players.yml kaydedilemedi!", e);
            // Write failed: the flag is set again so the next flush retries.
            dirty = true;
        }
    }
}
