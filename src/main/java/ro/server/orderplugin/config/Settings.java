package ro.server.orderplugin.config;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import ro.server.orderplugin.OrderPlugin;
import ro.server.orderplugin.util.SoundSpec;

/**
 * The typed form of {@code config.yml}.
 *
 * <p>Settings are read once at startup and kept here; {@code getConfig().getString(...)}
 * is not called every time a GUI opens or on every click. With 54 slots per GUI and
 * several players a second, that difference is noticeable.</p>
 *
 * <p>Rank-based limits/tax are resolved here: among all permissions the player
 * holds, the <b>most favorable one</b> is applied, so granting a VIP a second
 * permission can never leave them worse off.</p>
 */
public final class Settings {

    /** How amount/price input is taken. */
    public enum InputMode { SIGN, CHAT }

    private record PermValue(String permission, double value) {}

    private final OrderPlugin plugin;

    // --- features
    private boolean featurePotions = true;
    private boolean featureEnchantments = true;
    private boolean featureEnchantedBooks = true;
    private boolean featureSellAll = true;
    private boolean featureQuickFill = true;
    private boolean featureSearch = true;
    private boolean featureFilter = true;
    private boolean featureSort = true;
    private boolean featureLanguageMenu = true;

    // --- order rules
    private InputMode inputMode = InputMode.SIGN;
    private int defaultMaxActive = 10;
    private List<PermValue> activeLimits = List.of();
    private double minPrice = 0.01;
    private double maxPrice = -1;
    private int minAmount = 1;
    private int maxAmount = -1;

    // --- tax (kept as a percentage: 5.0 = 5%)
    private boolean taxEnabled = true;
    private double taxCreationPercent = 0.0;
    private double taxDeliveryPercent = 0.0;
    private double taxSellPercent = 0.0;
    private List<PermValue> taxRankRates = List.of();
    private List<PermValue> taxRankDiscounts = List.of();
    private String taxExemptPermission = "orders.tax.exempt";
    private String taxDestination = "VOID";
    private String taxDestinationAccount = "";
    private double taxMinAmount = -1;
    private double taxMaxAmount = -1;
    private long expiryMillis = TimeUnit.DAYS.toMillis(7);
    private String orderExpireBypassPermission = "orders.bypass.expire";
    private long completedRetentionMillis = TimeUnit.DAYS.toMillis(7);
    private int maxEnchantments = 5;
    private Set<Material> blacklist = EnumSet.noneOf(Material.class);
    private long cleanupIntervalTicks = 6000L;
    private boolean orderBroadcastEnabled = false;
    private double orderBroadcastMinTotal = 0d;

    // --- sounds
    private boolean soundsEnabled = true;
    private Map<String, SoundSpec> eventSounds = Map.of();

    /**
     * The event sounds the code plays, and their defaults.
     *
     * <p>This list also serves as documentation: an event missing from
     * config.yml plays with the default defined here, so adding a new event
     * doesn't leave old installs silent.</p>
     */
    private static final Map<String, String> EVENT_DEFAULTS = Map.ofEntries(
            Map.entry("error", "block.note_block.bass:1.0:0.8"),
            Map.entry("success", "entity.experience_orb.pickup:1.0:1.0"),
            Map.entry("order-created", "entity.player.levelup:0.6:1.4"),
            Map.entry("order-delivered", "entity.experience_orb.pickup:1.0:1.2"),
            Map.entry("order-cancelled", "block.note_block.bass:1.0:0.6"),
            Map.entry("order-completed", "ui.toast.challenge_complete:1.0:1.0"),
            Map.entry("page-turn", "item.book.page_turn:1.0:1.0"),
            Map.entry("menu-open", "none"),
            Map.entry("menu-close", "none"),
            Map.entry("level-up", "ui.toast.challenge_complete:1.0:1.0"),
            Map.entry("tax-paid", "entity.villager.yes:0.7:1.0"),
            Map.entry("no-money", "entity.villager.no:1.0:1.0"));

    // --- text style (minifont)
    private boolean miniFontButtons = true;
    private boolean miniFontTitles = true;
    private boolean miniFontMessages = false;
    private boolean miniFontLore = true;
    private Map<String, String> miniFontMap = Map.of();
    private String currencySymbol = "$";
    private boolean currencySuffix = false;

    // --- number format (for the order lore template: text.number-format)
    private String numberFormatStyle = "full";
    private String numberFormatSeparator = ".";
    private int numberFormatDecimals = 0;
    private String percentFormat = "%%%s";

    // --- progress bar (text.progress-bar)
    private int progressBarLength = 10;
    private String progressBarFilledChar = "■";
    private String progressBarEmptyChar = "■";
    private String progressBarFilledColor = "&#49F267";
    private String progressBarEmptyColor = "&#555555";

    /** Features that can be toggled on/off in order in the admin panel; features.panel-toggles. */
    private List<String> featurePanelToggles = List.of(
            "potions", "enchantments", "enchanted-books", "sell-all",
            "quick-fill", "search", "filter", "sort");

    // --- spam protection (0 = corresponding check disabled)
    private long cooldownClickMs = 200L;
    private long cooldownMenuMs = 250L;
    private long cooldownCommandMs = 1000L;
    private long cooldownCreateMs = 3000L;
    private long cooldownDeliverMs = 500L;
    private long cooldownInputMs = 500L;
    private long cooldownWarnMs = 3000L;
    private boolean protectionWarn = true;
    private String protectionBypassPermission = "orders.bypass.cooldown";

    // --- performance
    private long saveDelayTicks = 40L;
    private long chatInputTimeoutTicks = 1200L;

    public Settings(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        FileConfiguration c = plugin.getConfig();

        featurePotions = c.getBoolean("features.potions", true);
        featureEnchantments = c.getBoolean("features.enchantments", true);
        featureEnchantedBooks = c.getBoolean("features.enchanted-books", true);
        featureSellAll = c.getBoolean("features.sell-all", true);
        featureQuickFill = c.getBoolean("features.quick-fill", true);
        featureSearch = c.getBoolean("features.search", true);
        featureFilter = c.getBoolean("features.filter", true);
        featureSort = c.getBoolean("features.sort", true);
        featureLanguageMenu = c.getBoolean("per-player-language", true);

        String mode = c.getString("orders.input-mode", "SIGN");
        try {
            inputMode = InputMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("orders.input-mode '" + mode + "' gecersiz (SIGN veya CHAT), SIGN kullaniliyor.");
            inputMode = InputMode.SIGN;
        }

        defaultMaxActive = c.getInt("orders.max-active-per-player", 10);
        activeLimits = readPermValues(c.getConfigurationSection("orders.rank-limits"), "max-active");

        minPrice = c.getDouble("orders.min-price-per-item", 0.01);
        maxPrice = c.getDouble("orders.max-price-per-item", -1);
        minAmount = c.getInt("orders.min-amount", 1);
        maxAmount = c.getInt("orders.max-amount", -1);

        taxEnabled = c.getBoolean("tax.enabled", true);
        taxCreationPercent = c.getDouble("tax.creation-percent", 0.0);
        taxDeliveryPercent = c.getDouble("tax.delivery-percent", 0.0);
        taxSellPercent = c.getDouble("tax.sell-percent", 0.0);
        taxRankRates = readPermValues(c.getConfigurationSection("tax.rank-rates"), "percent");
        taxRankDiscounts = readPermValues(c.getConfigurationSection("tax.rank-discounts"), "percent");
        taxExemptPermission = c.getString("tax.exempt-permission", "orders.tax.exempt");
        taxDestination = c.getString("tax.destination", "VOID").trim().toUpperCase(Locale.ROOT);
        taxDestinationAccount = c.getString("tax.destination-account", "");
        taxMinAmount = c.getDouble("tax.min-amount", -1);
        taxMaxAmount = c.getDouble("tax.max-amount", -1);

        expiryMillis = TimeUnit.HOURS.toMillis(Math.max(1, c.getLong("orders.expiry-hours", 168L)));
        orderExpireBypassPermission = c.getString("orders.expire-bypass-permission", "orders.bypass.expire");
        completedRetentionMillis = TimeUnit.HOURS.toMillis(Math.max(1, c.getLong("orders.completed-retention-hours", 168L)));
        maxEnchantments = c.getInt("orders.max-enchantments-per-order", 5);
        cleanupIntervalTicks = Math.max(200L, c.getLong("orders.cleanup-interval-ticks", 6000L));
        orderBroadcastEnabled = c.getBoolean("orders.broadcast.enabled", false);
        orderBroadcastMinTotal = c.getDouble("orders.broadcast.min-total", 0d);

        blacklist = EnumSet.noneOf(Material.class);
        for (String raw : c.getStringList("orders.blacklist")) {
            Material m = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
            if (m == null) {
                plugin.getLogger().warning("orders.blacklist icinde bilinmeyen materyal: " + raw);
                continue;
            }
            blacklist.add(m);
        }

        soundsEnabled = c.getBoolean("sounds.enabled", true);
        eventSounds = readEventSounds(c);

        miniFontButtons = c.getBoolean("text.minifont.buttons", true);
        miniFontTitles = c.getBoolean("text.minifont.titles", true);
        miniFontMessages = c.getBoolean("text.minifont.messages", false);
        miniFontLore = c.getBoolean("text.minifont.lore", true);
        miniFontMap = readMiniFontMap(c.getConfigurationSection("text.minifont.map"));
        currencySymbol = c.getString("text.currency.symbol", "$");
        if (currencySymbol == null) currencySymbol = "";
        currencySuffix = c.getBoolean("text.currency.after-amount", false);

        numberFormatStyle = c.getString("text.number-format.style", "full");
        numberFormatSeparator = c.getString("text.number-format.thousands-separator", ".");
        numberFormatDecimals = Math.max(0, c.getInt("text.number-format.decimals", 0));
        percentFormat = c.getString("text.number-format.percent-format", "%%%s");

        progressBarLength = Math.max(1, c.getInt("text.progress-bar.length", 10));
        progressBarFilledChar = c.getString("text.progress-bar.filled-char", "■");
        progressBarEmptyChar = c.getString("text.progress-bar.empty-char", "■");
        progressBarFilledColor = c.getString("text.progress-bar.filled-color", "&#49F267");
        progressBarEmptyColor = c.getString("text.progress-bar.empty-color", "&#555555");

        // If features.panel-toggles is absent (old/partial config), use the default 8;
        // if the server owner deliberately left it empty ([]), that choice is respected.
        featurePanelToggles = c.isSet("features.panel-toggles")
                ? List.copyOf(c.getStringList("features.panel-toggles"))
                : List.of("potions", "enchantments", "enchanted-books", "sell-all",
                        "quick-fill", "search", "filter", "sort");

        cooldownClickMs = readCooldown(c, "protection.click-cooldown-ms", 200L);
        cooldownMenuMs = readCooldown(c, "protection.menu-cooldown-ms", 250L);
        cooldownCommandMs = readCooldown(c, "protection.command-cooldown-ms", 1000L);
        cooldownCreateMs = readCooldown(c, "protection.create-cooldown-ms", 3000L);
        cooldownDeliverMs = readCooldown(c, "protection.deliver-cooldown-ms", 500L);
        cooldownInputMs = readCooldown(c, "protection.input-cooldown-ms", 500L);
        cooldownWarnMs = readCooldown(c, "protection.warn-cooldown-ms", 3000L);
        protectionWarn = c.getBoolean("protection.warn-player", true);
        protectionBypassPermission = c.getString("protection.bypass-permission", "orders.bypass.cooldown");

        // 0 = no delay (write immediately on every change). There's no upper
        // bound, but a very large value increases the risk of data loss on a crash.
        saveDelayTicks = Math.max(0L, c.getLong("performance.save-delay-ticks", 40L));
        chatInputTimeoutTicks = Math.max(20L, c.getLong("performance.chat-input-timeout-ticks", 1200L));
    }

    /** A negative value means "disabled"; 0 is already disabled. */
    private long readCooldown(FileConfiguration c, String path, long fallback) {
        return Math.max(0L, c.getLong(path, fallback));
    }

    /**
     * The minifont character table. Keys must be a single character; a key with
     * more than one character is warned about and skipped (otherwise a rule like
     * "ab -> X" would silently never fire and the server owner couldn't tell why).
     */
    private Map<String, String> readMiniFontMap(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, String> out = new java.util.HashMap<>();
        for (String key : section.getKeys(false)) {
            if (key.length() != 1) {
                plugin.getLogger().warning("text.minifont.map -> '" + key
                        + "' tek karakter degil, atlandi.");
                continue;
            }
            String value = section.getString(key);
            if (value == null || value.isEmpty()) continue;
            // Character.toLowerCase instead of String.toLowerCase: lowercasing 'I'
            // (U+0130) as a String splits it into two characters ("i" + a combining
            // dot), and the table key would then never match again.
            out.put(String.valueOf(Character.toLowerCase(key.charAt(0))), value);
        }
        return Map.copyOf(out);
    }

    /**
     * Reads event sounds.
     *
     * <p>The old 2.0 {@code sounds.error} / {@code sounds.success} keys are checked
     * first: if they're still present in the file (e.g. ConfigUpdater couldn't
     * migrate them because the file is read-only), their values are used so they
     * aren't lost. Then {@code sounds.events.*} is read and overrides them if present.</p>
     */
    private Map<String, SoundSpec> readEventSounds(FileConfiguration c) {
        Map<String, SoundSpec> out = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : EVENT_DEFAULTS.entrySet()) {
            out.put(entry.getKey(), SoundSpec.parse(entry.getValue(), SoundSpec.NONE));
        }

        // 2.0-era format (backward compatible)
        if (c.isString("sounds.error")) {
            out.put("error", SoundSpec.parse(c.getString("sounds.error"), out.get("error")));
        }
        if (c.isString("sounds.success")) {
            out.put("success", SoundSpec.parse(c.getString("sounds.success"), out.get("success")));
        }

        ConfigurationSection events = c.getConfigurationSection("sounds.events");
        if (events != null) {
            for (String key : events.getKeys(false)) {
                String raw = events.getString(key);
                if (raw == null) continue;
                if (!EVENT_DEFAULTS.containsKey(key)) {
                    plugin.getLogger().warning("sounds.events -> '" + key
                            + "' kodun tanimadigi bir olay, yok sayildi.");
                    continue;
                }
                out.put(key, SoundSpec.parse(raw, out.get(key)));
            }
        }
        return Map.copyOf(out);
    }

    /**
     * Reads {@code permission: value} pairs. Both forms are accepted:
     * <pre>
     * rank-limits:
     *   orders.limit.vip: 25          # short form
     *   orders.limit.mvp:             # long form
     *     max-active: 50
     * </pre>
     */
    private List<PermValue> readPermValues(ConfigurationSection section, String valueKey) {
        if (section == null) return List.of();
        List<PermValue> out = new ArrayList<>();
        for (String permission : section.getKeys(false)) {
            Object raw = section.get(permission);
            if (raw instanceof ConfigurationSection sub) {
                if (!sub.isSet(valueKey)) {
                    plugin.getLogger().warning("'" + permission + "' altinda '" + valueKey + "' eksik, atlandi.");
                    continue;
                }
                out.add(new PermValue(permission, sub.getDouble(valueKey)));
            } else if (raw instanceof Number n) {
                out.add(new PermValue(permission, n.doubleValue()));
            } else if (raw instanceof Map<?, ?> map && map.get(valueKey) instanceof Number n) {
                out.add(new PermValue(permission, n.doubleValue()));
            } else {
                plugin.getLogger().warning("'" + permission + "' icin gecersiz deger, atlandi.");
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ rank-based

    /**
     * The number of active orders a player can hold at once.
     * The {@code orders.unlimited} permission, or a value of -1, means unlimited.
     */
    public int maxActiveOrders(Player player) {
        if (player.hasPermission("orders.unlimited")) return -1;
        int best = defaultMaxActive;
        boolean matched = false;
        for (PermValue pv : activeLimits) {
            if (!player.hasPermission(pv.permission())) continue;
            int value = (int) pv.value();
            if (value < 0) return -1;                       // unlimited always wins
            if (!matched || value > best) { best = value; matched = true; }
        }
        return best;
    }

    /**
     * Base tax percentage by transaction type (5.0 = 5%).
     *
     * @param type {@code "creation"}, {@code "delivery"}, or {@code "sell"}
     */
    public double taxBasePercent(String type) {
        return switch (type) {
            case "delivery" -> taxDeliveryPercent;
            case "sell" -> taxSellPercent;
            default -> taxCreationPercent;
        };
    }

    /**
     * Rank-based tax RATE (percentage). {@link Double#NaN} if no permission matches.
     *
     * <p>The LOWEST rate wins: granting a second rank permission can never
     * penalize the player.</p>
     */
    public double taxRankRate(Player player) {
        if (player == null) return Double.NaN;
        double best = Double.NaN;
        for (PermValue pv : taxRankRates) {
            if (!player.hasPermission(pv.permission())) continue;
            double value = Math.max(0.0, pv.value());
            if (Double.isNaN(best) || value < best) best = value;
        }
        return best;
    }

    /**
     * Rank-based tax DISCOUNT (percentage, 0-100). 0 if no permission matches.
     *
     * <p>The HIGHEST discount wins — same reasoning.</p>
     */
    public double taxRankDiscount(Player player) {
        if (player == null) return 0.0;
        double best = 0.0;
        for (PermValue pv : taxRankDiscounts) {
            if (!player.hasPermission(pv.permission())) continue;
            best = Math.max(best, Math.max(0.0, pv.value()));
        }
        return Math.min(100.0, best);
    }

    // ------------------------------------------------------------------ access

    public boolean potions() { return featurePotions; }
    public boolean enchantments() { return featureEnchantments; }
    public boolean enchantedBooks() { return featureEnchantedBooks; }
    public boolean sellAll() { return featureSellAll; }
    public boolean quickFill() { return featureQuickFill; }
    public boolean search() { return featureSearch; }
    public boolean filter() { return featureFilter; }
    public boolean sort() { return featureSort; }
    public boolean languageMenu() { return featureLanguageMenu; }

    public boolean taxEnabled() { return taxEnabled; }
    public String taxExemptPermission() { return taxExemptPermission; }
    public String taxDestination() { return taxDestination; }
    public String taxDestinationAccount() { return taxDestinationAccount; }
    public double taxMinAmount() { return taxMinAmount; }
    public double taxMaxAmount() { return taxMaxAmount; }

    public InputMode inputMode() { return inputMode; }
    public double minPrice() { return minPrice; }
    public double maxPrice() { return maxPrice; }
    public int minAmount() { return minAmount; }
    public int maxAmount() { return maxAmount; }
    public long expiryMillis() { return expiryMillis; }
    public String orderExpireBypassPermission() { return orderExpireBypassPermission; }
    public long completedRetentionMillis() { return completedRetentionMillis; }
    public int maxEnchantments() { return maxEnchantments; }
    public long cleanupIntervalTicks() { return cleanupIntervalTicks; }
    public boolean orderBroadcastEnabled() { return orderBroadcastEnabled; }
    public double orderBroadcastMinTotal() { return orderBroadcastMinTotal; }
    public boolean soundsEnabled() { return soundsEnabled; }

    /** The event sound; returns {@link SoundSpec#NONE} for an undefined event (never null). */
    public SoundSpec eventSound(String id) {
        SoundSpec spec = eventSounds.get(id);
        return spec != null ? spec : SoundSpec.NONE;
    }
    public boolean miniFontButtons() { return miniFontButtons; }
    public boolean miniFontTitles() { return miniFontTitles; }
    public boolean miniFontMessages() { return miniFontMessages; }
    public boolean miniFontLore() { return miniFontLore; }
    public Map<String, String> miniFontMap() { return miniFontMap; }
    public String currencySymbol() { return currencySymbol; }
    public boolean currencySuffix() { return currencySuffix; }

    public String numberFormatStyle() { return numberFormatStyle; }
    public String numberFormatSeparator() { return numberFormatSeparator; }
    public int numberFormatDecimals() { return numberFormatDecimals; }
    public String percentFormat() { return percentFormat; }

    public int progressBarLength() { return progressBarLength; }
    public String progressBarFilledChar() { return progressBarFilledChar; }
    public String progressBarEmptyChar() { return progressBarEmptyChar; }
    public String progressBarFilledColor() { return progressBarFilledColor; }
    public String progressBarEmptyColor() { return progressBarEmptyColor; }

    public List<String> featurePanelToggles() { return featurePanelToggles; }

    public long cooldownClickMs() { return cooldownClickMs; }
    public long cooldownMenuMs() { return cooldownMenuMs; }
    public long cooldownCommandMs() { return cooldownCommandMs; }
    public long cooldownCreateMs() { return cooldownCreateMs; }
    public long cooldownDeliverMs() { return cooldownDeliverMs; }
    public long cooldownInputMs() { return cooldownInputMs; }
    public long cooldownWarnMs() { return cooldownWarnMs; }
    public boolean protectionWarn() { return protectionWarn; }
    public String protectionBypassPermission() { return protectionBypassPermission; }

    public long saveDelayTicks() { return saveDelayTicks; }
    public long chatInputTimeoutTicks() { return chatInputTimeoutTicks; }

    public boolean isBlacklisted(Material material) {
        return material != null && blacklist.contains(material);
    }
}
