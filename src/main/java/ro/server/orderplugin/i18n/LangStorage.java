package ro.server.orderplugin.i18n;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import ro.server.orderplugin.OrderPlugin;

/**
 * Oyuncularin {@code /orderlang} ile sectigi dili kalici olarak saklar.
 *
 * <p>Tek bir kucuk YAML dosyasi ({@code lang-players.yml}) yeterli: kayit basina bir
 * dil kodu tutulur ve dosya acilista tamamen bellege alinir. Veritabani kurulumu
 * gerektirmemesi, dil secimini depolama turunden ({@code storage.type}) bagimsiz
 * kilar — MEMORY modunda calisan bir sunucuda da secim kalici olur.</p>
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

    /** Kayitli dil kodu, yoksa null. */
    public String get(UUID uuid) {
        return config.getString(uuid.toString());
    }

    /** Dili kaydeder; {@code code} null ise kayit silinir (otomatik cozume geri doner). */
    public synchronized void set(UUID uuid, String code) {
        config.set(uuid.toString(), code);
        save();
    }

    private void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "lang-players.yml kaydedilemedi!", e);
        }
    }
}
