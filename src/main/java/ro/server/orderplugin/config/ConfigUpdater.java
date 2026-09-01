package ro.server.orderplugin.config;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import ro.server.orderplugin.OrderPlugin;

/**
 * Surum yukseltmesinde {@code config.yml}'e yeni ayarlari ekler.
 *
 * <p>Var olan hicbir degere dokunulmaz — yalnizca dosyada <b>hic bulunmayan</b>
 * anahtarlar jar'daki varsayilandan alinir. Amac, sunucu sahibinin eklentiyi
 * guncelledikten sonra "config'i silip bastan yapilandirin" durumuna hic
 * dusmemesi.</p>
 *
 * <p>Bukkit'in YamlConfiguration'i 1.18'den beri yorum satirlarini koruyarak
 * kaydettigi icin mevcut aciklamalar da kaybolmaz; yalnizca yeni eklenen
 * anahtarlar yorumsuz gelir ve hangileri oldugu konsola yazilir.</p>
 *
 * <h2>Neden {@code menus/*.yml}'den farkli davraniyor</h2>
 *
 * <p>{@link ro.server.orderplugin.menu.MenuRegistry} silinmis bir <b>bolumu</b>
 * geri getirmez: orada bir butonu silmek "bu buton olmasin" demektir ve geri
 * gelmesi sunucu sahibinin kararini bozar.</p>
 *
 * <p>config.yml'de ayni kural ters teperdi. 3.0'da eklenen {@code tax},
 * {@code text}, {@code custom-items}, {@code network} bolumlerinin hicbiri eski
 * dosyada yoktur; "ust bolumu yoksa ekleme" kurali bunlarin <b>hicbirinin</b>
 * eklenmemesine yol acardi ve sunucu sahibi yeni ozellikleri ancak config'ini
 * silerek yapilandirabilirdi — tam da kacinilmasi gereken durum. Ayrica
 * config.yml'de bir ayarin yoklugu ile varsayilan degeri ayni anlama gelir,
 * bu yuzden eksik anahtari eklemek davranisi degistirmez; yalnizca ayari
 * gorunur ve duzenlenebilir yapar.</p>
 */
public final class ConfigUpdater {

    private ConfigUpdater() {}

    public static void update(OrderPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) return; // saveDefaultConfig zaten tam dosyayi yazdi

        YamlConfiguration bundled = loadBundled(plugin);
        if (bundled == null) return;

        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file);
        boolean migrated = migrate(plugin, onDisk);

        List<String> added = new ArrayList<>();
        for (String path : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(path)) continue;
            if (onDisk.contains(path)) continue;
            onDisk.set(path, bundled.get(path));
            added.add(path);
        }

        if (added.isEmpty() && !migrated) return;

        try {
            onDisk.save(file);
            if (!added.isEmpty()) {
                plugin.getLogger().info("config.yml guncellendi, eklenen yeni ayarlar: " + String.join(", ", added));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("config.yml guncellenemedi (" + e.getMessage()
                    + "); yeni ayarlar varsayilan degerleriyle calisacak.");
        }
    }

    /**
     * Yeri degisen ayarlari tasir — sunucu sahibinin degeri kaybolmasin.
     *
     * <p>Yalnizca <b>tasima</b> yapilir: eski anahtar silinir, degeri yeni yere
     * yazilir. Yeni yerde zaten bir deger varsa eskisi atilir, cunku bu durumda
     * sunucu sahibi yeni ayari bilerek doldurmustur.</p>
     *
     * @return dosyaya yazilmasi gereken bir degisiklik olduysa true
     */
    private static boolean migrate(OrderPlugin plugin, YamlConfiguration onDisk) {
        boolean changed = false;

        // 2.0 -> 3.0: sounds.error / sounds.success artik sounds.events altinda.
        changed |= moveString(plugin, onDisk, "sounds.error", "sounds.events.error");
        changed |= moveString(plugin, onDisk, "sounds.success", "sounds.events.success");

        // 2.0 -> 3.0: vergi orders. altindan tax. altina tasindi VE oran bicimi
        // degisti (0.05 -> 5.0). Donusum yapilmazsa %5 isteyen sunucu %0.05
        // vergi alirdi; sessizce yanlis calismaktansa tasiyoruz.
        if (onDisk.isSet("orders.creation-tax-percent") && !onDisk.isSet("tax.creation-percent")) {
            double old = onDisk.getDouble("orders.creation-tax-percent", 0d);
            onDisk.set("tax.creation-percent", old * 100d);
            // Eski dosyada oran 0 ise sunucu sahibi vergiyi istemiyordu: acmayalim.
            if (!onDisk.isSet("tax.enabled")) onDisk.set("tax.enabled", old > 0d);
            onDisk.set("orders.creation-tax-percent", null);
            plugin.getLogger().info("config.yml: vergi orders.creation-tax-percent ("
                    + old + ") -> tax.creation-percent (" + (old * 100d) + "%) tasindi.");
            changed = true;
        }
        if (onDisk.isConfigurationSection("orders.rank-tax") && !onDisk.isSet("tax.rank-rates")) {
            ConfigurationSection old = onDisk.getConfigurationSection("orders.rank-tax");
            for (String key : old.getKeys(false)) {
                onDisk.set("tax.rank-rates." + key, old.getDouble(key) * 100d);
            }
            onDisk.set("orders.rank-tax", null);
            plugin.getLogger().info("config.yml: orders.rank-tax -> tax.rank-rates tasindi (yuzdeye cevrildi).");
            changed = true;
        }

        return changed;
    }

    private static boolean moveString(OrderPlugin plugin, YamlConfiguration config, String from, String to) {
        if (!config.isString(from)) return false;
        String value = config.getString(from);
        config.set(from, null);
        if (config.isSet(to)) {
            plugin.getLogger().info("config.yml: '" + from + "' kaldirildi ('" + to + "' zaten tanimli).");
            return true;
        }
        config.set(to, value);
        plugin.getLogger().info("config.yml: '" + from + "' -> '" + to + "' tasindi (deger korundu: " + value + ").");
        return true;
    }

    private static YamlConfiguration loadBundled(OrderPlugin plugin) {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("Paketli config.yml okunamadi: " + e.getMessage());
            return null;
        }
    }
}
