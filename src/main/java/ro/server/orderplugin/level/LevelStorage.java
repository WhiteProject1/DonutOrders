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
 * Oyuncularin seviye/xp kayitlarini saklar ({@code levels-players.yml}).
 *
 * <p>{@link ro.server.orderplugin.i18n.LangStorage} ile ayni gerekce: tek bir
 * kucuk YAML dosyasi, {@code storage.type}'dan bagimsiz. MEMORY modunda calisan
 * bir sunucuda da seviyeler kalici olur ve sunucu sahibinin veritabani kurmasi
 * gerekmez.</p>
 *
 * <p>Yazma islemi <b>gecikmelidir</b>: her xp kazaniminda diske yazmak yerine
 * degisiklik isaretlenir ve periyodik olarak kaydedilir. Siparis teslim eden bir
 * oyuncu saniyede birkac kez xp kazanabilir; her seferinde dosya yazmak ana is
 * parcacigini bloklardi.</p>
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

    /** Kayit yoksa null. */
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
     * Bekleyen degisiklikleri diske yazar. Degisiklik yoksa dosyaya dokunmaz.
     *
     * <p>Yazma <b>arka planda</b> yapilir: dosya buyudukce (binlerce oyuncu)
     * senkron yazma temizlik gorevini, dolayisiyla ana is parcacigini bloklardi.
     * Yazilacak metin ana is parcaciginda uretilir, boylece {@code config} arka
     * planda okunurken baska bir yerden degistirilmis olmaz.</p>
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

    /** Kapanista: scheduler durdugu icin yazma bu is parcaciginda yapilir. */
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
            // Yazamadik: bir sonraki flush tekrar denesin diye isaret geri konur.
            dirty = true;
        }
    }
}
