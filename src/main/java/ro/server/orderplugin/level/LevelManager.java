package ro.server.orderplugin.level;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import ro.server.orderplugin.OrderPlugin;

/**
 * Siparis seviye sistemi.
 *
 * <p><b>Sifir sabit deger kurali:</b> seviye sayisi, xp katsayilari, bonus
 * turleri, ilerleme cubugunun karakterleri — hepsi {@code levels.yml}'den gelir.
 * Java tarafinda tek bir seviye/xp sayisi yoktur.</p>
 *
 * <p>{@code enabled: false} yapildiginda sistem yalnizca gizlenmez, <b>hic
 * calismaz</b>: tablo okunmaz, xp olaylari islenmez, tum bonuslar sifirdir.
 * Kapali sistemin performans maliyeti yoktur.</p>
 */
public final class LevelManager {

    private final OrderPlugin plugin;

    private boolean enabled = false;

    // xp kazanc kalemleri
    private double xpOnCreate;
    private double xpOnCreatePer1000;
    private double xpOnDeliverPerItem;
    private double xpOnComplete;
    private double xpOnSellPer1000;
    private double dailyCap;

    // gorunum
    private int barLength;
    private String barFilledChar;
    private String barEmptyChar;
    private String barFilledColor;
    private String barEmptyColor;
    private boolean titleEnabled;
    private int titleFadeIn;
    private int titleStay;
    private int titleFadeOut;

    /** Seviye numarasina gore artan sirali tablo. Bos ise sistem kapali sayilir. */
    private List<LevelTier> tiers = List.of();

    private final Map<UUID, PlayerLevel> cache = new ConcurrentHashMap<>();
    private final AntiAbuse antiAbuse;

    public LevelManager(OrderPlugin plugin) {
        this.plugin = plugin;
        this.antiAbuse = new AntiAbuse(plugin);
    }

    public AntiAbuse antiAbuse() {
        return antiAbuse;
    }

    // ------------------------------------------------------------------ yukleme

    public void load() {
        File file = new File(plugin.getDataFolder(), "levels.yml");
        if (!file.exists()) {
            plugin.saveResource("levels.yml", false);
        }

        YamlConfiguration onDisk = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration bundled = loadBundled();
        int added = mergeMissing(onDisk, bundled);
        if (added > 0) {
            try {
                onDisk.save(file);
                plugin.getLogger().info(added + " yeni seviye ayari levels.yml'ye eklendi (surum yukseltmesi).");
            } catch (Exception e) {
                plugin.getLogger().warning("levels.yml guncellenemedi (" + e.getMessage() + ").");
            }
        }

        enabled = onDisk.getBoolean("enabled", true);
        if (!enabled) {
            tiers = List.of();
            cache.clear();
            plugin.getLogger().info("Siparis seviye sistemi kapali (levels.yml -> enabled: false).");
            return;
        }

        antiAbuse.load(onDisk.getConfigurationSection("anti-abuse"));

        xpOnCreate = onDisk.getDouble("xp.on-create", 0d);
        xpOnCreatePer1000 = onDisk.getDouble("xp.on-create-per-1000", 0d);
        xpOnDeliverPerItem = onDisk.getDouble("xp.on-deliver-per-item", 0d);
        xpOnComplete = onDisk.getDouble("xp.on-complete", 0d);
        xpOnSellPer1000 = onDisk.getDouble("xp.on-sell-per-1000", 0d);
        dailyCap = onDisk.getDouble("daily-cap", -1d);

        barLength = Math.max(1, onDisk.getInt("progress-bar.length", 20));
        barFilledChar = onDisk.getString("progress-bar.filled-char", "|");
        barEmptyChar = onDisk.getString("progress-bar.empty-char", "|");
        barFilledColor = onDisk.getString("progress-bar.filled-color", "&a");
        barEmptyColor = onDisk.getString("progress-bar.empty-color", "&7");

        titleEnabled = onDisk.getBoolean("level-up-title.enabled", true);
        titleFadeIn = onDisk.getInt("level-up-title.fade-in", 10);
        titleStay = onDisk.getInt("level-up-title.stay", 40);
        titleFadeOut = onDisk.getInt("level-up-title.fade-out", 10);

        tiers = readTiers(onDisk.getConfigurationSection("levels"));
        if (tiers.isEmpty()) {
            plugin.getLogger().warning("levels.yml -> 'levels' bolumu bos, seviye sistemi devre disi.");
            enabled = false;
            return;
        }
        plugin.getLogger().info("Siparis seviye sistemi acik: " + tiers.size() + " seviye.");
    }

    private List<LevelTier> readTiers(ConfigurationSection section) {
        if (section == null) return List.of();
        List<LevelTier> out = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            int level;
            try {
                level = Integer.parseInt(key.trim());
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("levels.yml -> '" + key + "' bir seviye numarasi degil, atlandi.");
                continue;
            }
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;
            out.add(new LevelTier(level,
                    s.getDouble("xp-required", 0d),
                    s.getDouble("tax-discount", 0d),
                    s.getInt("extra-orders", 0),
                    s.getInt("extra-items", 0),
                    s.getStringList("reward-commands"),
                    s.getBoolean("broadcast", false)));
        }
        out.sort((a, b) -> Integer.compare(a.level(), b.level()));
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ erisim

    public boolean enabled() {
        return enabled;
    }

    /** Bilinen en yuksek seviye. Sistem kapaliysa 1. */
    public int maxLevel() {
        return tiers.isEmpty() ? 1 : tiers.get(tiers.size() - 1).level();
    }

    public PlayerLevel get(UUID uuid) {
        PlayerLevel cached = cache.get(uuid);
        if (cached != null) return cached;
        PlayerLevel loaded = plugin.getLevelStorage().load(uuid);
        PlayerLevel value = loaded != null ? loaded : PlayerLevel.fresh(uuid);
        cache.put(uuid, value);
        return value;
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    /** Oyuncunun mevcut seviye tanimi. Sistem kapaliysa taban (tum bonuslar 0). */
    public LevelTier tierOf(Player player) {
        if (!enabled || player == null) return LevelTier.base();
        return tierForLevel(get(player.getUniqueId()).level());
    }

    private LevelTier tierForLevel(int level) {
        LevelTier best = LevelTier.base();
        for (LevelTier tier : tiers) {
            if (tier.level() <= level) best = tier;
            else break;
        }
        return best;
    }

    public double taxDiscountPercent(Player player) {
        return enabled ? tierOf(player).taxDiscount() : 0d;
    }

    public int maxOrdersBonus(Player player) {
        return enabled ? tierOf(player).extraOrders() : 0;
    }

    public int maxItemsBonus(Player player) {
        return enabled ? tierOf(player).extraItems() : 0;
    }

    /** Bir sonraki seviyeye gereken toplam xp; zaten en ustteyse mevcut esik. */
    public double xpForNext(int level) {
        for (LevelTier tier : tiers) {
            if (tier.level() > level) return tier.xpRequired();
        }
        return tiers.isEmpty() ? 0d : tiers.get(tiers.size() - 1).xpRequired();
    }

    // ------------------------------------------------------------------ xp

    /**
     * Bir olaydan xp kazandirir.
     *
     * @param source {@code create}, {@code create-money}, {@code deliver},
     *               {@code complete} ya da {@code sell-money}
     * @param value  kaynaga gore: adet ya da para tutari (kalem basina katsayi
     *               {@code levels.yml}'den gelir)
     */
    public void award(Player player, String source, double value) {
        award(player, source, value, 1d);
    }

    /**
     * Teslim XP'si — istismar korumasindan gecirilir.
     *
     * <p>Ayri bir metot olmasinin sebebi, katsayinin <b>kimle</b> ticaret
     * yapildigina bagli olmasi: {@link #award} bunu bilemez ve her cagri yerine
     * partner parametresi eklemek, teslim disindaki kalemlerde anlamsiz olurdu.</p>
     *
     * @param owner siparis sahibi (ticaretin karsi tarafi)
     * @param items teslim edilen esya sayisi
     */
    public void awardDelivery(Player deliverer, UUID owner, int items) {
        if (!enabled || deliverer == null) return;
        double multiplier = antiAbuse.deliveryMultiplier(deliverer, owner);
        if (multiplier <= 0d) return;
        double granted = award(deliverer, "deliver", items, multiplier);
        antiAbuse.recordPartnerXp(deliverer.getUniqueId(), owner, granted);
    }

    /**
     * @param multiplier istismar korumasinin belirledigi katsayi (1.0 = tam odul)
     * @return gercekten verilen xp (gunluk tavana takilmissa daha az olabilir)
     */
    public double award(Player player, String source, double value, double multiplier) {
        if (!enabled || player == null || multiplier <= 0d) return 0d;

        double gained = switch (source) {
            case "create" -> xpOnCreate;
            case "create-money" -> value / 1000d * xpOnCreatePer1000;
            case "deliver" -> value * xpOnDeliverPerItem;
            case "complete" -> xpOnComplete;
            case "sell-money" -> value / 1000d * xpOnSellPer1000;
            default -> 0d;
        } * multiplier;
        if (gained <= 0d) return 0d;

        UUID id = player.getUniqueId();
        PlayerLevel current = get(id);

        long now = System.currentTimeMillis();
        double dailyXp = current.dailyXp();
        long dailyReset = current.dailyReset();
        // Gunluk pencere: son sifirlamadan 24 saat sonra sayac sifirlanir.
        if (now - dailyReset >= 86_400_000L) {
            dailyXp = 0d;
            dailyReset = now;
        }
        if (dailyCap >= 0d) {
            double room = dailyCap - dailyXp;
            if (room <= 0d) return 0d;
            if (gained > room) gained = room;
        }

        double newXp = current.xp() + gained;
        int newLevel = levelForXp(newXp);
        PlayerLevel updated = current.withXp(newXp, newLevel, dailyXp + gained, dailyReset);
        cache.put(id, updated);
        plugin.getLevelStorage().save(updated);

        if (newLevel > current.level()) {
            announceLevelUp(player, newLevel);
        }
        return gained;
    }

    private int levelForXp(double xp) {
        int level = tiers.isEmpty() ? 1 : tiers.get(0).level();
        for (LevelTier tier : tiers) {
            if (xp >= tier.xpRequired()) level = tier.level();
            else break;
        }
        return level;
    }

    private void announceLevelUp(Player player, int level) {
        LevelTier tier = tierForLevel(level);

        player.sendMessage(plugin.msg(player, "level.up", "%level%", String.valueOf(level)));
        plugin.playEventSound(player, "level-up");

        if (titleEnabled) {
            player.sendTitle(
                    plugin.msg(player, "level.up-title", "%level%", String.valueOf(level)),
                    plugin.msg(player, "level.up-subtitle", "%level%", String.valueOf(level)),
                    titleFadeIn, titleStay, titleFadeOut);
        }

        for (String command : tier.rewardCommands()) {
            if (command == null || command.isBlank()) continue;
            String resolved = command.replace("%player%", player.getName())
                    .replace("%level%", String.valueOf(level));
            // Odul komutlari ana is parcaciginda calismali (Folia dahil).
            plugin.getSchedulerAdapter().runGlobal(plugin, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved));
        }

        if (tier.broadcast()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.sendMessage(plugin.msg(online, "level.up-broadcast",
                        "%player%", player.getName(), "%level%", String.valueOf(level)));
            }
        }
    }

    // ------------------------------------------------------------------ gorunum

    /** Renklendirilmemis ilerleme cubugu; {@code &} kodlari icerir. */
    public String progressBar(PlayerLevel state) {
        if (!enabled) return "";
        double next = xpForNext(state.level());
        double previous = tierForLevel(state.level()).xpRequired();
        double span = next - previous;
        double ratio = span <= 0d ? 1d : (state.xp() - previous) / span;
        if (ratio < 0d) ratio = 0d;
        if (ratio > 1d) ratio = 1d;

        int filled = (int) Math.round(ratio * barLength);
        StringBuilder sb = new StringBuilder(barLength * 3);
        sb.append(barFilledColor);
        sb.append(String.valueOf(barFilledChar).repeat(filled));
        sb.append(barEmptyColor);
        sb.append(String.valueOf(barEmptyChar).repeat(Math.max(0, barLength - filled)));
        return sb.toString();
    }

    // ------------------------------------------------------------------ yardimcilar

    private YamlConfiguration loadBundled() {
        try (InputStream in = plugin.getResource("levels.yml")) {
            if (in == null) return null;
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("Paketli levels.yml okunamadi: " + e.getMessage());
            return null;
        }
    }

    /**
     * Jar'da olup diskte olmayan ayarlari ekler; var olanlara dokunmaz.
     *
     * <p>{@code levels} bolumunun altina <b>yeni seviye eklenmez</b>: sunucu
     * sahibi 8 seviyeden 5'ini silmisse guncelleme onlari geri getirmemeli.
     * Seviye tablosunda bir girdinin yoklugu "bu seviye olmasin" demektir.</p>
     *
     * <p>Diger ayarlarda ayni kural <b>ters teper</b>. Burada bir zamanlar
     * "ust bolumu diskte yoksa ekleme" kurali vardi; 3.1'de eklenen
     * {@code anti-abuse} bolumu eski hicbir dosyada bulunmadigi icin
     * <b>hicbir ayari</b> eklenmez, sunucu sahibi istismar korumasini ancak
     * levels.yml'yi silerek yapilandirabilirdi — tam da kacinilmasi gereken
     * durum. Ustelik bir ayarin yoklugu ile varsayilan degeri ayni anlama gelir,
     * yani eksik anahtari eklemek davranisi degistirmez; yalnizca gorunur ve
     * duzenlenebilir yapar.</p>
     */
    private static int mergeMissing(YamlConfiguration onDisk, YamlConfiguration bundled) {
        if (bundled == null) return 0;
        int added = 0;
        for (String path : bundled.getKeys(true)) {
            if (bundled.isConfigurationSection(path)) continue;
            if (onDisk.contains(path)) continue;
            if (path.startsWith("levels.")) continue;
            onDisk.set(path, bundled.get(path));
            added++;
        }
        return added;
    }

    /** Onbellekteki tum kayitlari diske yazar (kapanista). */
    public void flush() {
        if (!enabled) return;
        for (PlayerLevel value : Collections.unmodifiableCollection(cache.values())) {
            plugin.getLevelStorage().save(value);
        }
    }
}
