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
 * Order level system.
 *
 * <p><b>Zero hard-coded values rule:</b> the level count, xp coefficients, bonus
 * types, progress bar characters — all of it comes from {@code levels.yml}.
 * There is no single level/xp count on the Java side.</p>
 *
 * <p>When {@code enabled: false} is set, the system isn't just hidden, it
 * <b>doesn't run at all</b>: the table isn't read, xp events aren't processed,
 * all bonuses are zero. A disabled system has no performance cost.</p>
 */
public final class LevelManager {

    private final OrderPlugin plugin;

    private boolean enabled = false;

    // xp gain entries
    private double xpOnCreate;
    private double xpOnCreatePer1000;
    private double xpOnDeliverPerItem;
    private double xpOnComplete;
    private double xpOnSellPer1000;
    private double dailyCap;

    // appearance
    private int barLength;
    private String barFilledChar;
    private String barEmptyChar;
    private String barFilledColor;
    private String barEmptyColor;
    private boolean titleEnabled;
    private int titleFadeIn;
    private int titleStay;
    private int titleFadeOut;

    /** Table sorted ascending by level number. Empty means the system is considered disabled. */
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

    // ------------------------------------------------------------------ loading

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

    // ------------------------------------------------------------------ access

    public boolean enabled() {
        return enabled;
    }

    /** Highest known level. 1 if the system is disabled. */
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

    /** Player's current level tier. Base tier (all bonuses 0) if the system is disabled. */
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

    /** Total xp needed for the next level; if already at the top, the current threshold. */
    public double xpForNext(int level) {
        for (LevelTier tier : tiers) {
            if (tier.level() > level) return tier.xpRequired();
        }
        return tiers.isEmpty() ? 0d : tiers.get(tiers.size() - 1).xpRequired();
    }

    // ------------------------------------------------------------------ xp

    /**
     * Grants xp for an event.
     *
     * @param source {@code create}, {@code create-money}, {@code deliver},
     *               {@code complete} or {@code sell-money}
     * @param value  depending on the source: a count or a money amount (the
     *               per-item coefficient comes from {@code levels.yml})
     */
    public void award(Player player, String source, double value) {
        award(player, source, value, 1d);
    }

    /**
     * Delivery XP — passed through abuse protection.
     *
     * <p>The reason this is a separate method is that the coefficient depends
     * on <b>who</b> the trade is with: {@link #award} has no way to know this,
     * and adding a partner parameter to every call would be meaningless for
     * entries other than delivery.</p>
     *
     * @param owner order owner (the other side of the trade)
     * @param items number of items delivered
     */
    public void awardDelivery(Player deliverer, UUID owner, int items) {
        if (!enabled || deliverer == null) return;
        double multiplier = antiAbuse.deliveryMultiplier(deliverer, owner);
        if (multiplier <= 0d) return;
        double granted = award(deliverer, "deliver", items, multiplier);
        antiAbuse.recordPartnerXp(deliverer.getUniqueId(), owner, granted);
    }

    /**
     * @param multiplier coefficient determined by abuse protection (1.0 = full reward)
     * @return xp actually granted (may be less if it hit the daily cap)
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
        // Daily window: the counter resets 24 hours after the last reset.
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
            // Reward commands must run on the main thread (Folia included).
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

    // ------------------------------------------------------------------ appearance

    /** Uncolored progress bar; contains {@code &} codes. */
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

    // ------------------------------------------------------------------ helpers

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
     * Adds settings that exist in the jar but not on disk; leaves existing ones untouched.
     *
     * <p><b>No new levels are added</b> under the {@code levels} section: if
     * the server owner deleted 5 of 8 levels, an update must not bring them
     * back. The absence of an entry in the level table means "this level
     * shouldn't exist."</p>
     *
     * <p>For other settings the same rule <b>backfires</b>. There used to be a
     * rule here of "don't add a top-level section if it's not on disk"; since
     * the {@code anti-abuse} section added in 3.1 doesn't exist in any old
     * file, <b>no setting from it at all</b> would get added, and the server
     * owner could only configure abuse protection by deleting levels.yml —
     * exactly the situation this is meant to avoid. Also, the absence of a
     * setting is equivalent to its default value, so adding the missing key
     * doesn't change behavior; it only makes it visible and editable.</p>
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

    /** Writes all cached entries to disk (on shutdown). */
    public void flush() {
        if (!enabled) return;
        for (PlayerLevel value : Collections.unmodifiableCollection(cache.values())) {
            plugin.getLevelStorage().save(value);
        }
    }
}
