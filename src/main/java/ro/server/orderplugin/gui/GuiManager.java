package ro.server.orderplugin.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import ro.server.orderplugin.OrderPlugin;
import ro.server.orderplugin.config.Settings;
import ro.server.orderplugin.menu.MenuConfig;
import ro.server.orderplugin.menu.MenuItem;
import ro.server.orderplugin.menu.MenuRegistry;
import ro.server.orderplugin.model.FilterType;
import ro.server.orderplugin.model.Order;
import ro.server.orderplugin.model.SortType;
import ro.server.orderplugin.economy.TaxService;
import ro.server.orderplugin.level.PlayerLevel;
import ro.server.orderplugin.util.ItemBuilder;
import ro.server.orderplugin.util.LoreTemplate;
import ro.server.orderplugin.util.MiniFont;
import ro.server.orderplugin.util.SoundSpec;
import ro.server.orderplugin.util.TextUtil;

/**
 * Tum menuleri kurar.
 *
 * <p>Yerlesim {@code menus/*.yml}'den, metin {@code lang/*.yml}'den gelir; bu
 * sinifta ne sabit slot ne de sabit metin vardir. Acilan her envanter bir
 * {@link OrderMenuHolder} tasir, boylece tiklama isleyicisi menuyu basliktan
 * tahmin etmek zorunda kalmaz.</p>
 */
public class GuiManager {

    private static final Logger LOGGER = Logger.getLogger("DonutOrders");

    private final OrderPlugin plugin;

    public final ConcurrentHashMap<UUID, Material> selectedMaterial = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID, Integer> selectedAmount = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID, Double> selectedPrice = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID, String> selectedPotionType = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID, String> selectedEnchantmentType = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID, Set<String>> selectedEnchantments = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID, SortType> playerSortType = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID, FilterType> playerFilterType = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<UUID, String> playerSearchQuery = new ConcurrentHashMap<>();

    public List<ItemStack> allItems = new ArrayList<>();

    /** dil kodu -> allItems ile ayni sirali, kucuk harfli arama dizisi. */
    private final ConcurrentHashMap<String, String[]> searchIndexCache = new ConcurrentHashMap<>();

    private final Cache<UUID, ItemStack> orderItemCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(500)
            .build();

    // Bukkit.getOfflinePlayer() her cagrida diskten NBT okuyabilir; menu her
    // acilisinda 45 siparis icin bunu yapmak gorunur bir takilma yaratir.
    private final Cache<UUID, String> playerNameCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(200)
            .build();

    public GuiManager(OrderPlugin plugin) {
        this.plugin = plugin;
        rebuildItemList();
    }

    // =========================================================== ortak yardimcilar

    private String lang(Player player) {
        return plugin.getLanguage().resolve(player);
    }

    /**
     * Menu basligi. Oncelik sirasi:
     * <ol>
     *   <li>eski kurulumlardan kalan {@code config.yml -> gui-titles.<id>} (geriye donuk uyum)</li>
     *   <li>{@code menus/<id>.yml -> title} ezmesi</li>
     *   <li>dil dosyasindaki {@code menus.<id>}</li>
     * </ol>
     */
    String menuTitle(Player player, MenuConfig menu, String langKey, String... replacements) {
        String legacy = plugin.getConfig().getString("gui-titles." + menu.id());
        String raw = legacy != null && !legacy.isBlank() ? legacy : menu.titleOverride();
        if (raw != null && !raw.isBlank()) {
            // Menu dosyasindaki baslik ezmesi dil dosyasindan gecmedigi icin
            // minifont donusumu burada ayrica uygulanir.
            if (plugin.settings().miniFontTitles()) {
                raw = MiniFont.apply(raw, plugin.settings().miniFontMap());
            }
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                raw = raw.replace(replacements[i], replacements[i + 1]);
            }
            return TextUtil.colorize(raw);
        }
        return plugin.msg(player, "menus." + langKey, replacements);
    }

    Inventory createInventory(MenuConfig menu, OrderMenuHolder holder, String title) {
        Inventory inventory = Bukkit.createInventory(holder, menu.size(), title);
        holder.setInventory(inventory);
        return inventory;
    }

    /**
     * Dolgu panellerini yerlestirir. Dolgunun slotu belirtilmisse yalnizca oraya,
     * belirtilmemisse bos kalan tum slotlara konur — sonuncusu, buton slotlarini
     * degistiren sunucu sahibinin ekranda delik birakmamasi icin.
     */
    void applyFiller(Player player, Inventory inventory, MenuConfig menu) {
        MenuItem filler = menu.filler();
        if (!filler.enabled() || filler.material() == null) return;
        ItemStack stack = buildButton(player, filler, null, null);
        if (filler.hasSlots()) {
            for (int slot : filler.slots()) {
                if (slot < 0 || slot >= menu.size()) continue;
                // Dolgu SUSLEMEDIR, icerik degildir: dolu bir slotu ezmez.
                // Eskiden kosulsuz ezerdi; "slots: 45-53" yazan her menude alt
                // siradaki sayfa/geri/arama butonlari gorunmez oluyordu.
                // Bilerek ezmek isteyen "overwrite: true" yazar.
                if (!filler.overwrite() && inventory.getItem(slot) != null) continue;
                inventory.setItem(slot, stack.clone());
            }
            return;
        }
        for (int i = 0; i < menu.size(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, stack.clone());
        }
    }

    /**
     * Bir butonu olusturur.
     *
     * @param nameKey isim icin dil anahtari; menu dosyasinda {@code name} yazilmissa o kazanir
     * @param lore    kodun urettigi aciklama; menu dosyasinda {@code lore} yazilmissa o kazanir
     */
    ItemStack buildButton(Player player, MenuItem item, String nameKey, List<String> lore,
                                  String... replacements) {
        ItemBuilder builder = new ItemBuilder(item.material() == null ? Material.STONE : item.material());
        applyText(builder, player, item, nameKey, lore, replacements);
        return finish(builder, item);
    }

    /** Materyali koddan gelen (siparis onizlemesi gibi) butonlar icin. */
    ItemStack buildButton(Player player, MenuItem item, ItemStack base, String nameKey, List<String> lore,
                                  String... replacements) {
        ItemBuilder builder = new ItemBuilder(base);
        applyText(builder, player, item, nameKey, lore, replacements);
        return finish(builder, item);
    }

    private void applyText(ItemBuilder builder, Player player, MenuItem item, String nameKey, List<String> lore,
                           String... replacements) {
        String name = item.nameOverride();
        if (name != null) {
            // Menu dosyasindaki isim ezmesi de dil dosyasindan gecmez.
            if (plugin.settings().miniFontButtons()) {
                name = MiniFont.apply(name, plugin.settings().miniFontMap());
            }
            builder.setName(TextUtil.colorize(replace(name, replacements)));
        } else if (nameKey != null) {
            builder.setName(plugin.msg(player, nameKey, replacements));
        }

        List<String> override = item.loreOverride();
        if (override != null) {
            List<String> resolved = new ArrayList<>(override.size());
            for (String line : override) {
                resolved.add(TextUtil.colorize(replace(line, replacements)));
            }
            builder.setLore(resolved);
        } else if (lore != null && !lore.isEmpty()) {
            builder.setLore(lore);
        }
    }

    private ItemStack finish(ItemBuilder builder, MenuItem item) {
        if (item.customModelData() > 0) builder.setCustomModelData(item.customModelData());
        if (item.glow()) builder.setGlowing();
        return builder.build();
    }

    private static String replace(String text, String... replacements) {
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return text;
    }

    /** Butonu tanimli tum slotlarina koyar. */
    void place(Inventory inventory, MenuItem item, ItemStack stack) {
        if (!item.enabled() || stack == null) return;
        for (int slot : item.slots()) {
            if (slot < 0 || slot >= inventory.getSize()) continue;
            inventory.setItem(slot, slot == item.slots()[0] ? stack : stack.clone());
        }
    }

    /**
     * Menu acilis sesi.
     *
     * <p>Menu dosyasinda {@code open-sound} yazilmissa o calar; yazilmamissa
     * genel {@code sounds.events.menu-open} devreye girer. Boylece tek bir yerden
     * tum menulere ses verilebilir, istenirse menu bazinda ezilir.</p>
     */
    void playOpen(Player player, MenuConfig menu) {
        SoundSpec sound = menu.openSound();
        if (sound.silent()) {
            plugin.playEventSound(player, "menu-open");
            return;
        }
        plugin.playSound(player, sound);
    }

    /** Menu kapanis sesi; {@code close-sound} yoksa {@code sounds.events.menu-close}. */
    public void playClose(Player player, MenuConfig menu) {
        SoundSpec sound = menu.closeSound();
        if (sound.silent()) {
            plugin.playEventSound(player, "menu-close");
            return;
        }
        plugin.playSound(player, sound);
    }

    /** Siparis sahibinin adi (onbellekli). Yonetici paneli de bunu kullanir. */
    public String ownerName(UUID uuid) {
        return getPlayerName(uuid);
    }

    private String getPlayerName(UUID uuid) {
        String cached = playerNameCache.getIfPresent(uuid);
        if (cached != null) return cached;
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            playerNameCache.put(uuid, online.getName());
            return online.getName();
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        String name = offlinePlayer.getName() != null ? offlinePlayer.getName() : "?";
        playerNameCache.put(uuid, name);
        return name;
    }

    /** Kalan sureyi alicinin dilinde biciminde ("2g 4s 10dk"). */
    private String timeRemaining(Player player, long expiryTimestamp) {
        // orders.bypass.expire ile acilmis siparislerde expiry Long.MAX_VALUE'dir;
        // gunlere cevirip anlamsiz devasa bir sayi gostermek yerine "hicbir zaman" denir.
        if (expiryTimestamp == Long.MAX_VALUE) return plugin.rawMsg(player, "time.never");
        long remaining = expiryTimestamp - System.currentTimeMillis();
        if (remaining <= 0L) return plugin.rawMsg(player, "time.expired");
        long days = TimeUnit.MILLISECONDS.toDays(remaining);
        remaining -= TimeUnit.DAYS.toMillis(days);
        long hours = TimeUnit.MILLISECONDS.toHours(remaining);
        remaining -= TimeUnit.HOURS.toMillis(hours);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(remaining);
        StringBuilder sb = new StringBuilder();
        if (days > 0L) sb.append(plugin.rawMsg(player, "time.day").replace("%value%", String.valueOf(days))).append(' ');
        if (hours > 0L) sb.append(plugin.rawMsg(player, "time.hour").replace("%value%", String.valueOf(hours))).append(' ');
        sb.append(plugin.rawMsg(player, "time.minute").replace("%value%", String.valueOf(minutes)));
        return sb.toString();
    }

    /**
     * Bir tutari para birimi simgesiyle bicimlendirir.
     *
     * <p>Simge eskiden yedi ayri yerde {@code "$"} olarak koda gomuluydu; dolar
     * kullanmayan bir sunucunun bunlari degistirmesi imkansizdi cunku metin dil
     * dosyasinda degil Java'daydi. Artik {@code config.yml -> text.currency}
     * belirler ve simge tutarin oncesine ya da sonrasina konabilir.</p>
     */
    String money(double amount) {
        return money(TextUtil.formatNumber(amount));
    }

    String money(String formatted) {
        return plugin.settings().currencySuffix()
                ? formatted + plugin.settings().currencySymbol()
                : plugin.settings().currencySymbol() + formatted;
    }

    private static int totalPages(int itemCount, int pageSize) {
        if (pageSize <= 0) return 1;
        return Math.max(1, (int) Math.ceil((double) itemCount / (double) pageSize));
    }

    // =========================================================== eshya listesi

    /** Eshya secme menusundeki liste; ozellik anahtarlari ve kara liste burada uygulanir. */
    public void rebuildItemList() {
        List<ItemStack> items = new ArrayList<>();
        Arrays.stream(Material.values())
                .filter(Material::isItem)
                .filter(m -> !m.name().startsWith("LEGACY_"))
                .filter(m -> m != Material.AIR)
                .filter(m -> m != Material.POTION && m != Material.SPLASH_POTION && m != Material.LINGERING_POTION)
                .filter(m -> m != Material.ENCHANTED_BOOK)
                .filter(this::isAllowedMaterial)
                .filter(m -> !plugin.settings().isBlacklisted(m))
                .sorted(Comparator.comparing(Enum::name))
                .forEach(m -> items.add(new ItemStack(m)));

        if (plugin.settings().potions()) addPotionItems(items);
        if (plugin.settings().enchantedBooks()) addEnchantedBookItems(items);
        this.allItems = items;
        // Liste degisti: arama indeksi artik eski siraya ait.
        this.searchIndexCache.clear();
    }

    private void addPotionItems(List<ItemStack> items) {
        for (Material material : new Material[]{Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION}) {
            if (plugin.settings().isBlacklisted(material)) continue;
            for (PotionType potionType : PotionType.values()) {
                try {
                    ItemStack potionItem = new ItemStack(material);
                    PotionMeta meta = (PotionMeta) potionItem.getItemMeta();
                    meta.setBasePotionType(potionType);
                    potionItem.setItemMeta(meta);
                    items.add(potionItem);
                } catch (Exception ignored) {
                    // Sunucu surumunun tanimadigi iksir turu: listeye alinmaz.
                }
            }
        }
    }

    private void addEnchantedBookItems(List<ItemStack> items) {
        if (plugin.settings().isBlacklisted(Material.ENCHANTED_BOOK)) return;
        for (Enchantment enchantment : Enchantment.values()) {
            for (int level = 1; level <= enchantment.getMaxLevel(); ++level) {
                try {
                    ItemStack bookItem = new ItemStack(Material.ENCHANTED_BOOK);
                    EnchantmentStorageMeta meta = (EnchantmentStorageMeta) bookItem.getItemMeta();
                    meta.addStoredEnchant(enchantment, level, true);
                    bookItem.setItemMeta(meta);
                    items.add(bookItem);
                } catch (Exception ignored) {
                    // Uyumsuz buyu/seviye: atlanir.
                }
            }
        }
    }

    private boolean isAllowedMaterial(Material material) {
        String name = material.name();
        if (name.contains("COMMAND_BLOCK")) return false;
        if (name.equals("STRUCTURE_BLOCK") || name.equals("STRUCTURE_VOID") || name.equals("JIGSAW")) return false;
        if (name.equals("BARRIER") || name.equals("LIGHT")) return false;
        if (name.equals("DEBUG_STICK") || name.equals("BEDROCK") || name.equals("KNOWLEDGE_BOOK")) return false;
        if (name.contains("SPAWN_EGG") || name.equals("SPAWNER")) return false;
        if (name.equals("PETRIFIED_OAK_SLAB") || name.equals("END_PORTAL_FRAME")) return false;
        if (name.startsWith("INFESTED_")) return false;
        if (name.equals("BUDDING_AMETHYST") || name.equals("REINFORCED_DEEPSLATE") || name.equals("FROGSPAWN")) return false;
        if (name.equals("TRIAL_SPAWNER") || name.equals("VAULT")) return false;
        return !name.contains("OMINOUS_TRIAL") && !name.contains("OMINOUS_BOTTLE");
    }

    public boolean isEnchantable(Material material) {
        if (material == null || !plugin.settings().enchantments()) return false;
        String name = material.name();
        if (material == Material.ENCHANTED_BOOK || name.contains("POTION")) return false;
        if (name.endsWith("_SWORD")) return true;
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")) return true;
        if (name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE")) return true;
        return material == Material.BOW || material == Material.CROSSBOW || material == Material.TRIDENT
                || material == Material.FISHING_ROD || material == Material.SHEARS || material == Material.FLINT_AND_STEEL
                || material == Material.SHIELD || material == Material.ELYTRA || material == Material.CARROT_ON_A_STICK
                || material == Material.WARPED_FUNGUS_ON_A_STICK || material == Material.BRUSH || material == Material.MACE;
    }

    public List<Enchantment> getApplicableEnchantments(Material material) {
        List<Enchantment> applicable = new ArrayList<>();
        ItemStack testItem = new ItemStack(material);
        for (Enchantment enchantment : Enchantment.values()) {
            if (enchantment.canEnchantItem(testItem)) applicable.add(enchantment);
        }
        applicable.sort(Comparator.comparing(e -> e.getKey().getKey()));
        return applicable;
    }

    // =========================================================== isim cozumleme

    /** Bir siparisin alicinin dilindeki adi (iksir/buyu ekleri dahil). */
    public String getOrderDisplayName(Player viewer, Order order) {
        return getOrderDisplayName(lang(viewer), order);
    }

    public String getOrderDisplayName(String code, Order order) {
        if (order.isPotion() && order.getPotionType() != null) {
            try {
                return plugin.names().potion(code, order.getMaterial(), PotionType.valueOf(order.getPotionType()));
            } catch (Exception ignored) {
                return plugin.names().material(code, order.getMaterial().name());
            }
        }
        if (order.isEnchantedBook() && order.getEnchantmentType() != null) {
            String[] parts = splitEnchant(order.getEnchantmentType());
            if (parts != null) {
                return plugin.names().enchantedBook(code, parts[0], Integer.parseInt(parts[1]));
            }
        }
        String base = plugin.names().material(code, order.getMaterial().name());
        if (order.getEnchantmentType() != null && !order.getEnchantmentType().isEmpty() && !order.isEnchantedBook()) {
            return base + " " + plugin.getLanguage().raw(code, "gui.lore.enchanted-suffix");
        }
        return base;
    }

    /** "SHARPNESS_5" -> ["sharpness", "5"]; bicim bozuksa null. */
    private static String[] splitEnchant(String raw) {
        int last = raw.lastIndexOf('_');
        if (last <= 0 || last == raw.length() - 1) return null;
        try {
            Integer.parseInt(raw.substring(last + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        return new String[]{raw.substring(0, last).toLowerCase(Locale.ROOT), raw.substring(last + 1)};
    }

    /** Bir siparisin buyulerini "Keskinlik V" satirlari olarak dondurur. */
    private List<String> enchantmentLore(Player viewer, Order order) {
        List<String> lines = new ArrayList<>();
        String enchantments = order.getEnchantmentType();
        if (enchantments == null || enchantments.isEmpty() || order.isEnchantedBook()) return lines;
        String code = lang(viewer);
        for (String entry : enchantments.split(";")) {
            String[] parts = splitEnchant(entry);
            if (parts == null) continue;
            lines.add("§7" + plugin.names().enchantmentWithLevel(code, parts[0], Integer.parseInt(parts[1])));
        }
        return lines;
    }

    // =========================================================== siparis eshyasi

    public ItemStack createOrderItemStack(Order order) {
        ItemStack cached = orderItemCache.getIfPresent(order.getId());
        if (cached != null) return cached.clone();

        ItemStack orderItem = new ItemStack(order.getMaterial());
        if (order.isPotion() && order.getPotionType() != null) {
            try {
                PotionMeta meta = (PotionMeta) orderItem.getItemMeta();
                meta.setBasePotionType(PotionType.valueOf(order.getPotionType()));
                orderItem.setItemMeta(meta);
            } catch (Exception ignored) {
                // Tanimsiz iksir turu: sade sise gosterilir.
            }
        }

        if (order.isEnchantedBook() && order.getEnchantmentType() != null) {
            try {
                EnchantmentStorageMeta meta = (EnchantmentStorageMeta) orderItem.getItemMeta();
                String[] parts = splitEnchant(order.getEnchantmentType());
                if (parts != null) {
                    Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0]));
                    if (enchantment != null) meta.addStoredEnchant(enchantment, Integer.parseInt(parts[1]), true);
                }
                orderItem.setItemMeta(meta);
            } catch (Exception ignored) {
                // Bilinmeyen buyu: kitap sade gosterilir.
            }
        } else if (order.getEnchantmentType() != null && !order.getEnchantmentType().isEmpty()) {
            try {
                ItemMeta meta = orderItem.getItemMeta();
                for (String entry : order.getEnchantmentType().split(";")) {
                    String[] parts = splitEnchant(entry);
                    if (parts == null) continue;
                    Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0]));
                    if (enchantment == null) continue;
                    meta.addEnchant(enchantment, Integer.parseInt(parts[1]), true);
                }
                orderItem.setItemMeta(meta);
            } catch (Exception ignored) {
                // Bilinmeyen buyu: eshya buyusuz gosterilir.
            }
        }

        orderItemCache.put(order.getId(), orderItem.clone());
        return orderItem;
    }

    public void invalidateOrderCache(UUID orderId) {
        orderItemCache.invalidate(orderId);
    }

    public void invalidateAllOrderCaches() {
        orderItemCache.invalidateAll();
        playerNameCache.invalidateAll();
        // Ceviriler degismis olabilir; arama cevrilmis ada da bakiyor.
        searchIndexCache.clear();
    }

    // =========================================================== ana menu

    public void openMainMenu(Player player) {
        openMainMenuPaged(player, 1, null);
    }

    public void openMainMenu(Player player, String searchQuery) {
        openMainMenuPaged(player, 1, searchQuery);
    }

    public void openMainMenuPaged(Player player, int page, String searchQuery) {
        try {
            openMainMenuInternal(player, page, searchQuery);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "[DonutOrders] Ana menu acilamadi (sayfa=" + page + ")", ex);
            player.sendMessage(plugin.msg(player, "errors.order-unavailable"));
        }
    }

    private void openMainMenuInternal(Player player, int page, String searchQuery) {
        UUID playerId = player.getUniqueId();
        MenuConfig menu = plugin.menus().get(MenuRegistry.MAIN_MENU);
        SortType sortType = playerSortType.getOrDefault(playerId, SortType.RECENTLY_LISTED);
        FilterType filterType = playerFilterType.getOrDefault(playerId, FilterType.ALL);

        List<Order> orders = sortedOrders(playerId, searchQuery);
        int pageSize = Math.max(1, menu.pageSize());
        int pages = totalPages(orders.size(), pageSize);
        if (page < 1) page = 1;
        if (page > pages) page = pages;

        String title = menuTitle(player, menu, "main-menu",
                "%page%", String.valueOf(page), "%pages%", String.valueOf(pages));
        OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.MAIN_MENU, page, searchQuery, null);
        Inventory inventory = createInventory(menu, holder, title);

        int startIndex = (page - 1) * pageSize;
        int[] contentSlots = menu.contentSlots();
        for (int i = 0; i < contentSlots.length && startIndex + i < orders.size(); i++) {
            inventory.setItem(contentSlots[i], buildOrderEntry(player, orders.get(startIndex + i)));
        }

        // --- gezinme
        MenuItem prev = menu.item("previous-page");
        if (prev.enabled() && prev.hasSlots()) {
            boolean canGoBack = page > 1;
            place(inventory, prev, buildButton(player, prev,
                    canGoBack ? "gui.buttons.previous-page" : "gui.buttons.no-previous-page", null));
        }
        MenuItem next = menu.item("next-page");
        if (next.enabled() && next.hasSlots()) {
            boolean canGoForward = page < pages;
            place(inventory, next, buildButton(player, next,
                    canGoForward ? "gui.buttons.next-page" : "gui.buttons.no-next-page", null));
        }

        // --- siralama
        MenuItem sortButton = menu.item("sort");
        if (plugin.settings().sort() && sortButton.enabled() && sortButton.hasSlots()) {
            List<String> lore = new ArrayList<>();
            for (SortType type : SortType.values()) {
                String label = plugin.msg(player, "gui.lore." + type.messageKey());
                lore.add((type == sortType ? "§a• " : "§7• ") + label);
            }
            place(inventory, sortButton, buildButton(player, sortButton, "gui.buttons.sort", lore));
        }

        // --- filtre
        MenuItem filterButton = menu.item("filter");
        if (plugin.settings().filter() && filterButton.enabled() && filterButton.hasSlots()) {
            List<String> lore = new ArrayList<>();
            for (FilterType type : FilterType.values()) {
                String label = plugin.msg(player, "gui.lore." + type.messageKey());
                lore.add((type == filterType ? "§a• " : "§7• ") + label);
            }
            place(inventory, filterButton, buildButton(player, filterButton, "gui.buttons.filter", lore));
        }

        // --- yenile
        MenuItem refresh = menu.item("refresh");
        if (refresh.enabled() && refresh.hasSlots()) {
            place(inventory, refresh, buildButton(player, refresh, "gui.buttons.orders",
                    List.of(plugin.msg(player, "gui.lore.click-to-refresh"))));
        }

        // --- arama
        MenuItem search = menu.item("search");
        if (plugin.settings().search() && search.enabled() && search.hasSlots()) {
            String current = searchQuery == null ? plugin.msg(player, "gui.lore.none") : searchQuery;
            List<String> lore = List.of(
                    "§f" + plugin.msg(player, "gui.lore.current") + ": §b" + current,
                    "",
                    plugin.msg(player, "gui.lore.click-to-search"),
                    plugin.msg(player, "gui.lore.type-in-chat"));
            place(inventory, search, buildButton(player, search, "gui.buttons.search", lore, "%search%", current));
        }

        // --- siparislerim
        MenuItem yourOrders = menu.item("your-orders");
        if (yourOrders.enabled() && yourOrders.hasSlots()) {
            place(inventory, yourOrders, buildButton(player, yourOrders, "gui.buttons.your-orders",
                    List.of(plugin.msg(player, "gui.lore.manage-listings"))));
        }

        // --- dil
        MenuItem languageButton = menu.item("language");
        if (plugin.settings().languageMenu() && languageButton.enabled() && languageButton.hasSlots()) {
            place(inventory, languageButton, buildButton(player, languageButton, "gui.buttons.language",
                    List.of("§f" + plugin.msg(player, "gui.lore.current") + ": §b"
                                    + plugin.getLanguage().displayName(lang(player)),
                            plugin.msg(player, "gui.lore.language-click")),
                    "%language%", plugin.getLanguage().displayName(lang(player))));
        }

        // --- siparis seviyesi
        // levels.yml -> enabled: false ise buton HIC konmaz; slot bos kalir ve
        // (dolgu aciksa) cam panelle dolar.
        MenuItem levelButton = menu.item("level");
        if (plugin.levels().enabled() && levelButton.enabled() && levelButton.hasSlots()) {
            place(inventory, levelButton, buildButton(player, levelButton, "gui.buttons.level",
                    levelLore(player), "%level%",
                    String.valueOf(plugin.levels().get(playerId).level())));
        }

        applyFiller(player, inventory, menu);
        player.openInventory(inventory);
        playOpen(player, menu);
    }

    /** Seviye butonunun aciklamasi; {@code /order level} ciktisi da bunu kullanir. */
    public List<String> levelLore(Player player) {
        PlayerLevel state = plugin.levels().get(player.getUniqueId());
        List<String> lore = new ArrayList<>();
        lore.add(plugin.msg(player, "gui.lore.level-current", "%level%", String.valueOf(state.level())));
        lore.add(plugin.msg(player, "gui.lore.level-xp",
                "%xp%", TextUtil.formatNumber(state.xp()),
                "%next%", TextUtil.formatNumber(plugin.levels().xpForNext(state.level()))));
        lore.add(plugin.msg(player, "gui.lore.level-progress",
                "%bar%", TextUtil.colorize(plugin.levels().progressBar(state))));
        lore.add("");
        lore.add(plugin.msg(player, "gui.lore.level-tax",
                "%discount%", formatPercent(plugin.levels().taxDiscountPercent(player))));
        lore.add(plugin.msg(player, "gui.lore.level-orders",
                "%extra%", String.valueOf(plugin.levels().maxOrdersBonus(player))));
        lore.add(plugin.msg(player, "gui.lore.level-items",
                "%extra%", String.valueOf(plugin.levels().maxItemsBonus(player))));
        if (state.level() >= plugin.levels().maxLevel()) {
            lore.add(plugin.msg(player, "gui.lore.level-max"));
        }
        return lore;
    }

    /** Ana menudeki siralama+filtre+arama sonucu — tiklama isleyicisi de ayni listeyi kullanir. */
    public List<Order> sortedOrders(UUID playerId, String searchQuery) {
        SortType sortType = playerSortType.getOrDefault(playerId, SortType.RECENTLY_LISTED);
        FilterType filterType = playerFilterType.getOrDefault(playerId, FilterType.ALL);
        String needle = searchQuery == null ? null : searchQuery.toLowerCase(Locale.ROOT);

        List<Order> orders = plugin.getOrderManager().getActiveOrders().stream()
                .filter(order -> !order.isComplete())
                .filter(order -> !plugin.settings().filter() || filterType.matches(order.getMaterial()))
                .filter(order -> needle == null || matchesSearch(order, needle))
                .collect(Collectors.toList());

        if (!plugin.settings().sort()) {
            orders.sort((a, b) -> Long.compare(b.getCreated(), a.getCreated()));
            return orders;
        }
        switch (sortType) {
            case MOST_PAID -> orders.sort((a, b) -> Double.compare(
                    b.getFilled() * b.getPricePerItem(), a.getFilled() * a.getPricePerItem()));
            case MOST_DELIVERED -> orders.sort((a, b) -> Integer.compare(b.getFilled(), a.getFilled()));
            case RECENTLY_LISTED -> orders.sort((a, b) -> Long.compare(b.getCreated(), a.getCreated()));
            case MOST_MONEY_PER_ITEM -> orders.sort((a, b) -> Double.compare(b.getPricePerItem(), a.getPricePerItem()));
        }
        return orders;
    }

    /**
     * Arama hem ham materyal adinda hem de sunucu dilindeki cevrilmis adda yapilir;
     * Turkce oynayan biri "mese" yazdiginda OAK_LOG'u bulabilsin diye.
     */
    private boolean matchesSearch(Order order, String needle) {
        if (order.getMaterial().name().toLowerCase(Locale.ROOT).contains(needle)) return true;
        String translated = plugin.names().material(plugin.getLanguage().serverDefault(), order.getMaterial().name());
        return translated.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * Sipariş panosundaki eshya aciklamasi (lore).
     *
     * <p>{@code gui.order-lore.template} (lang dosyasi) varsa {@link LoreTemplate}
     * ile bu sablondan uretilir — sunucu sahibi renkleri, sirayla, ilerleme
     * cubugunu kendi yazdigi metinle tamamen degistirebilir. Sablon anahtari
     * silinmis ya da bossa {@link #buildOrderEntryLegacy} eski, sabit-kodlu
     * gorunumu uretir; boylece anahtarini silen bir sunucu sahibi bos ya da
     * bozuk bir esya gormez.</p>
     */
    private ItemStack buildOrderEntry(Player player, Order order) {
        List<String> template = orderLoreTemplate(player);
        if (template == null || template.isEmpty()) {
            return buildOrderEntryLegacy(player, order);
        }

        String ownerName = getPlayerName(order.getOwner());
        String itemName = getOrderDisplayName(player, order);

        Map<String, String> values = orderLoreValues(player, order, ownerName, itemName);
        Map<String, List<String>> lists = Map.of("enchants", enchantmentLore(player, order));

        List<String> lore = LoreTemplate.render(template, values, lists);

        return new ItemBuilder(createOrderItemStack(order))
                .setName(plugin.msg(player, "gui.lore.owners-order", "%player%", ownerName))
                .setAmount(1)
                .setLore(lore)
                .hideFlags()
                .build();
    }

    /** {@code gui.order-lore.template}; oyuncunun dili -> en -> tr sirasiyla aranir. */
    private List<String> orderLoreTemplate(Player player) {
        String code = lang(player);
        List<String> list = plugin.getLanguage().rawList(code, "gui.order-lore.template");
        if (list == null || list.isEmpty()) list = plugin.getLanguage().rawList("en", "gui.order-lore.template");
        if (list == null || list.isEmpty()) list = plugin.getLanguage().rawList("tr", "gui.order-lore.template");
        return list;
    }

    /** Sipariş sablonundaki skaler yer tutucularin degerleri; hepsi ham (renklendirilmemis) metindir. */
    private Map<String, String> orderLoreValues(Player player, Order order, String ownerName, String itemName) {
        double filled = order.getFilled();
        double needed = order.getNeeded();
        double remaining = order.getRemaining();
        double pricePerItem = order.getPricePerItem();
        double paid = filled * pricePerItem;
        double total = needed * pricePerItem;
        double percent = needed > 0 ? (filled / needed) * 100.0 : 100.0;

        Map<String, String> values = new HashMap<>();
        values.put("item", itemName);
        values.put("owner", ownerName);
        values.put("price", formatQuantity(pricePerItem));
        values.put("total", formatQuantity(total));
        values.put("paid", formatQuantity(paid));
        values.put("filled", formatQuantity(filled));
        values.put("needed", formatQuantity(needed));
        values.put("remaining", formatQuantity(remaining));
        values.put("percent", formatConfiguredPercent(percent));
        values.put("bar", buildProgressBar(filled, needed));
        values.put("time", timeRemaining(player, order.getExpiry()));
        return values;
    }

    /** {@code text.number-format} ayarina gore ("full" ya da "compact") sayi bicimi. */
    private String formatQuantity(double value) {
        Settings settings = plugin.settings();
        if ("compact".equalsIgnoreCase(settings.numberFormatStyle())) {
            return TextUtil.formatNumber(value);
        }
        return TextUtil.formatFull(value, settings.numberFormatSeparator(), settings.numberFormatDecimals());
    }

    /**
     * {@link #formatQuantity} ile ayni bicim; siparis panosundaki lore hangi
     * sayi bicimini kullaniyorsa disaridan (orn. yeni siparis duyurusu) da
     * onunla ayni gorunsun diye public.
     */
    public String formatOrderNumber(double value) {
        return formatQuantity(value);
    }

    /**
     * {@code text.number-format.percent-format} ile bicimlendirilmis yuzde.
     *
     * <p>Sablon {@code %percent%} yaninda ayrica {@code %} yazmaz — dil dosyasindaki
     * bicim (orn. {@code "%%%s"} -> {@code "%0"}, {@code "%s%%"} -> {@code "0%"})
     * isareti zaten icerir. Boylece {@code %%percent%} gibi belirsiz bir yazim
     * hic gerekmez ve her dil kendi yuzde geleneginde yazabilir.</p>
     */
    private String formatConfiguredPercent(double percent) {
        long rounded = Math.round(percent);
        try {
            return String.format(Locale.ROOT, plugin.settings().percentFormat(), rounded);
        } catch (Exception e) {
            return rounded + "%";
        }
    }

    /** {@code text.progress-bar} ayarlarina gore ham (renklendirilmemis degil, &# iceren) ilerleme cubugu. */
    private String buildProgressBar(double filled, double needed) {
        Settings settings = plugin.settings();
        return TextUtil.progressBar(filled, needed, settings.progressBarLength(),
                settings.progressBarFilledChar(), settings.progressBarEmptyChar(),
                settings.progressBarFilledColor(), settings.progressBarEmptyColor());
    }

    /** 3.0 oncesi sabit-kodlu gorunum; {@code gui.order-lore.template} silinirse/bossa buraya duser. */
    private ItemStack buildOrderEntryLegacy(Player player, Order order) {
        String ownerName = getPlayerName(order.getOwner());
        String itemName = getOrderDisplayName(player, order);
        String filled = TextUtil.formatNumber(order.getFilled());
        String needed = TextUtil.formatNumber(order.getNeeded());
        String filledCost = money(order.getFilled() * order.getPricePerItem());
        String totalCost = money(order.getNeeded() * order.getPricePerItem());

        List<String> lore = new ArrayList<>();
        lore.add("§f" + itemName);
        lore.addAll(enchantmentLore(player, order));
        lore.add(TextUtil.colorize("&#00d271" + money(order.getPricePerItem())
                + " §f" + plugin.msg(player, "gui.lore.each")));
        lore.add("");
        lore.add(TextUtil.colorize("§e" + filled + "§8/&#00d271" + needed
                + " §8" + plugin.msg(player, "gui.lore.delivered")));
        lore.add(TextUtil.colorize("§e" + filledCost + "§8/&#00d271" + totalCost
                + " §8" + plugin.msg(player, "gui.lore.paid")));
        lore.add("");
        lore.add(plugin.msg(player, "gui.lore.click-to-deliver", "%player%", ownerName, "%item%", itemName));
        lore.add(plugin.msg(player, "gui.lore.until-order-expires", "%time%", timeRemaining(player, order.getExpiry())));

        return new ItemBuilder(createOrderItemStack(order))
                .setName(plugin.msg(player, "gui.lore.owners-order", "%player%", ownerName))
                .setAmount(1)
                .setLore(lore)
                .hideFlags()
                .build();
    }

    // =========================================================== siparislerim

    public void openYourOrders(Player player) {
        openYourOrders(player, 1);
    }

    public void openYourOrders(Player player, int page) {
        try {
            openYourOrdersInternal(player, page);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "[DonutOrders] Siparislerim menusu acilamadi: " + player.getName(), ex);
            player.sendMessage(plugin.msg(player, "errors.order-unavailable"));
        }
    }

    /** Menude gorunen siparisler — tiklama isleyicisi ayni siralamayi kullanir. */
    public List<Order> visibleOrders(UUID playerId) {
        return plugin.getOrderManager().getOrdersByPlayer(playerId).stream()
                .filter(order -> !order.isRemovedByAdmin() || order.hasItems())
                .collect(Collectors.toList());
    }

    private void openYourOrdersInternal(Player player, int page) {
        MenuConfig menu = plugin.menus().get(MenuRegistry.YOUR_ORDERS);
        List<Order> orders = visibleOrders(player.getUniqueId());
        int[] contentSlots = menu.contentSlots();
        int pageSize = Math.max(1, contentSlots.length);
        int pages = totalPages(Math.max(orders.size(), 1), pageSize);
        if (page < 1) page = 1;
        if (page > pages) page = pages;

        String title = menuTitle(player, menu, "your-orders",
                "%page%", String.valueOf(page), "%pages%", String.valueOf(pages));
        OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.YOUR_ORDERS, page);
        Inventory inventory = createInventory(menu, holder, title);

        int startIndex = (page - 1) * pageSize;
        int used = 0;
        for (int i = 0; i < contentSlots.length && startIndex + i < orders.size(); i++) {
            inventory.setItem(contentSlots[i], buildOwnOrderEntry(player, orders.get(startIndex + i)));
            used = i + 1;
        }

        MenuItem newOrder = menu.item("new-order");
        if (newOrder.enabled()) {
            ItemStack stack = buildButton(player, newOrder, "gui.buttons.new-order",
                    List.of(plugin.msg(player, "gui.lore.click-to-create")));
            if (newOrder.slot() >= 0) {
                place(inventory, newOrder, stack);
            } else if (used < contentSlots.length) {
                // slot: -1 -> son siparisin hemen ardina
                inventory.setItem(contentSlots[used], stack);
            }
        }

        MenuItem back = menu.item("back");
        if (back.enabled() && back.hasSlots()) {
            place(inventory, back, buildButton(player, back, "gui.buttons.back-to-menu", null));
        }

        applyFiller(player, inventory, menu);
        player.openInventory(inventory);
        playOpen(player, menu);
    }

    private ItemStack buildOwnOrderEntry(Player player, Order order) {
        List<String> lore = new ArrayList<>();
        String itemName = getOrderDisplayName(player, order);
        int inventoryCount = order.getInventoryCount();

        if (order.isRemovedByAdmin()) {
            lore.add(plugin.msg(player, "gui.lore.removed-by-admin"));
            lore.add("");
            lore.add(plugin.msg(player, "gui.lore.items-to-collect", "%amount%", String.valueOf(inventoryCount)));
            lore.add(plugin.msg(player, "gui.lore.click-to-collect"));
            return new ItemBuilder(createOrderItemStack(order))
                    .setName("§c" + itemName + " " + plugin.msg(player, "gui.lore.removed-suffix"))
                    .setAmount(1).setLore(lore).hideFlags().build();
        }

        String filled = TextUtil.formatNumber(order.getFilled());
        String needed = TextUtil.formatNumber(order.getNeeded());
        String filledCost = money(order.getFilled() * order.getPricePerItem());
        String totalCost = money(order.getNeeded() * order.getPricePerItem());

        lore.add(TextUtil.colorize("&#00d271" + needed + " §f" + itemName));
        lore.addAll(enchantmentLore(player, order));
        lore.add(TextUtil.colorize("&#00d271" + money(order.getPricePerItem())
                + " §f" + plugin.msg(player, "gui.lore.each")));
        lore.add("");
        lore.add(TextUtil.colorize("§e" + filled + "§8/&#00d271" + needed
                + " §8" + plugin.msg(player, "gui.lore.delivered")));
        lore.add(TextUtil.colorize("§e" + filledCost + "§8/&#00d271" + totalCost
                + " §8" + plugin.msg(player, "gui.lore.paid")));
        if (order.isComplete()) {
            lore.add("");
            lore.add(plugin.msg(player, "gui.lore.order-completed"));
            lore.add(plugin.msg(player, "gui.lore.until-order-deletes", "%time%", timeRemaining(player, order.getExpiry())));
        }

        return new ItemBuilder(createOrderItemStack(order))
                .setName(plugin.msg(player, "gui.lore.owners-order", "%player%", player.getName()))
                .setAmount(1).setLore(lore).hideFlags().build();
    }

    // =========================================================== yeni siparis

    public void openNewOrder(Player player) {
        UUID playerId = player.getUniqueId();
        MenuConfig menu = plugin.menus().get(MenuRegistry.NEW_ORDER);

        Material material = selectedMaterial.getOrDefault(playerId, menu.item("item").material());
        int amount = selectedAmount.getOrDefault(playerId, 1);
        double price = selectedPrice.getOrDefault(playerId, 1.0);
        double total = amount * price;
        TaxService.TaxResult tax = plugin.tax().calculate(player, total, "creation");

        String title = menuTitle(player, menu, "new-order");
        OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.NEW_ORDER);
        Inventory inventory = createInventory(menu, holder, title);

        MenuItem cancel = menu.item("cancel");
        if (cancel.enabled() && cancel.hasSlots()) {
            place(inventory, cancel, buildButton(player, cancel, "gui.buttons.cancel",
                    List.of(plugin.msg(player, "gui.lore.return-to-orders"))));
        }

        MenuItem itemButton = menu.item("item");
        if (itemButton.enabled() && itemButton.hasSlots()) {
            ItemStack preview = buildPreviewItem(playerId, material);
            String displayName = previewDisplayName(player, playerId, material);
            place(inventory, itemButton, buildButton(player, itemButton, preview, "gui.buttons.item",
                    List.of(plugin.msg(player, "gui.lore.selected", "%item%", displayName),
                            plugin.msg(player, "gui.lore.click-to-change")),
                    "%item%", displayName));
        }

        MenuItem amountButton = menu.item("amount");
        if (amountButton.enabled() && amountButton.hasSlots()) {
            place(inventory, amountButton, buildButton(player, amountButton, "gui.buttons.amount",
                    List.of(plugin.msg(player, "gui.lore.amount-value", "%amount%", TextUtil.formatNumber(amount)),
                            plugin.msg(player, "gui.lore.click-to-set-amount")),
                    "%amount%", TextUtil.formatNumber(amount)));
        }

        MenuItem priceButton = menu.item("price");
        if (priceButton.enabled() && priceButton.hasSlots()) {
            place(inventory, priceButton, buildButton(player, priceButton, "gui.buttons.price",
                    List.of(plugin.msg(player, "gui.lore.price-per-item", "%price%", TextUtil.formatNumber(price)),
                            plugin.msg(player, "gui.lore.click-to-set-price")),
                    "%price%", TextUtil.formatNumber(price)));
        }

        MenuItem confirm = menu.item("confirm");
        if (confirm.enabled() && confirm.hasSlots()) {
            List<String> lore = new ArrayList<>();
            // Vergi acikken toplam, vergi DAHIL gosterilir: oyuncu onaylamadan
            // once cebinden ne cikacagini gormeli.
            lore.add(plugin.msg(player, "gui.lore.total-cost",
                    "%total%", TextUtil.formatNumber(tax.total())));
            if (tax.charged()) {
                lore.add(plugin.msg(player, "gui.lore.tax-line",
                        "%tax%", formatPercent(tax.rate()), "%amount%", TextUtil.formatNumber(tax.amount())));
                lore.add(plugin.msg(player, "gui.lore.tax-reason-" + tax.reason()));
            }
            lore.add(plugin.msg(player, "gui.lore.click-to-publish"));
            place(inventory, confirm, buildButton(player, confirm, "gui.buttons.confirm", lore,
                    "%total%", TextUtil.formatNumber(tax.total())));
        }

        applyFiller(player, inventory, menu);
        player.openInventory(inventory);
        playOpen(player, menu);
    }

    /**
     * 5.0 -&gt; "%5" (bicim dile bagli degil, sayidir).
     *
     * <p>Girdi 3.0'dan beri <b>yuzdenin kendisidir</b>, 0-1 arasi oran degil.
     * Vergi ayarlari da yuzde yazildigi icin ikisi ayni birimde kalir ve
     * "0.05 mi 5 mi" karisikligi olusmaz.</p>
     */
    public static String formatPercent(double percent) {
        return percent == Math.floor(percent)
                ? "%" + (long) percent
                : "%" + String.format(Locale.ROOT, "%.2f", percent);
    }

    private ItemStack buildPreviewItem(UUID playerId, Material material) {
        ItemStack preview = new ItemStack(material == null ? Material.STONE : material);
        String potionType = selectedPotionType.get(playerId);
        String enchantmentType = selectedEnchantmentType.get(playerId);
        boolean isPotion = material == Material.POTION || material == Material.SPLASH_POTION
                || material == Material.LINGERING_POTION;

        if (isPotion && potionType != null) {
            try {
                PotionMeta meta = (PotionMeta) preview.getItemMeta();
                meta.setBasePotionType(PotionType.valueOf(potionType));
                preview.setItemMeta(meta);
            } catch (Exception ignored) {
                // Tanimsiz tur: sade sise.
            }
        } else if (material == Material.ENCHANTED_BOOK && enchantmentType != null) {
            try {
                EnchantmentStorageMeta meta = (EnchantmentStorageMeta) preview.getItemMeta();
                String[] parts = splitEnchant(enchantmentType);
                if (parts != null) {
                    Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0]));
                    if (enchantment != null) meta.addStoredEnchant(enchantment, Integer.parseInt(parts[1]), true);
                }
                preview.setItemMeta(meta);
            } catch (Exception ignored) {
                // Bilinmeyen buyu: sade kitap.
            }
        } else if (enchantmentType != null && !enchantmentType.isEmpty()) {
            try {
                ItemMeta meta = preview.getItemMeta();
                for (String entry : enchantmentType.split(";")) {
                    String[] parts = splitEnchant(entry);
                    if (parts == null) continue;
                    Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0]));
                    if (enchantment == null) continue;
                    meta.addEnchant(enchantment, Integer.parseInt(parts[1]), true);
                }
                preview.setItemMeta(meta);
            } catch (Exception ignored) {
                // Bilinmeyen buyu: eshya buyusuz.
            }
        }
        return preview;
    }

    private String previewDisplayName(Player player, UUID playerId, Material material) {
        String code = lang(player);
        String potionType = selectedPotionType.get(playerId);
        String enchantmentType = selectedEnchantmentType.get(playerId);
        boolean isPotion = material == Material.POTION || material == Material.SPLASH_POTION
                || material == Material.LINGERING_POTION;

        if (isPotion && potionType != null) {
            try {
                return plugin.names().potion(code, material, PotionType.valueOf(potionType));
            } catch (Exception ignored) {
                return plugin.names().material(code, material.name());
            }
        }
        if (material == Material.ENCHANTED_BOOK && enchantmentType != null) {
            String[] parts = splitEnchant(enchantmentType);
            if (parts != null) return plugin.names().enchantedBook(code, parts[0], Integer.parseInt(parts[1]));
        }
        return plugin.names().material(code, material == null ? "STONE" : material.name());
    }

    // =========================================================== eshya secme

    public void openItemSelector(Player player, int page) {
        openItemSelector(player, page, null);
    }

    public void openItemSelector(Player player, int page, String filter) {
        MenuConfig menu = plugin.menus().get(MenuRegistry.ITEM_SELECTOR);
        List<ItemStack> items = filterItems(player, filter);

        int[] contentSlots = menu.contentSlots();
        int pageSize = Math.max(1, contentSlots.length);
        int pages = totalPages(items.size(), pageSize);
        if (page < 1) page = 1;
        if (page > pages) page = pages;

        // %page% eskiden hic degistirilmiyordu; baslikta ham "%page%" yaziyordu ve
        // sayfa bilgisi ayrica sona ekleniyordu. Artik tek kaynak dil dosyasi.
        String title = filter == null || filter.isBlank()
                ? menuTitle(player, menu, "item-selector",
                        "%page%", String.valueOf(page), "%pages%", String.valueOf(pages))
                : menuTitle(player, menu, "item-selector-filtered",
                        "%page%", String.valueOf(page), "%pages%", String.valueOf(pages), "%search%", filter);

        OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.ITEM_SELECTOR, page, filter, null);
        Inventory inventory = createInventory(menu, holder, title);

        String code = lang(player);
        int startIndex = (page - 1) * pageSize;
        for (int i = 0; i < contentSlots.length && startIndex + i < items.size(); i++) {
            inventory.setItem(contentSlots[i], nameForSelector(code, items.get(startIndex + i)));
        }

        MenuItem prev = menu.item("previous-page");
        if (prev.enabled() && prev.hasSlots()) {
            place(inventory, prev, buildButton(player, prev,
                    page > 1 ? "gui.buttons.previous-page" : "gui.buttons.no-previous-page", null));
        }
        MenuItem next = menu.item("next-page");
        if (next.enabled() && next.hasSlots()) {
            place(inventory, next, buildButton(player, next,
                    page < pages ? "gui.buttons.next-page" : "gui.buttons.no-next-page", null));
        }
        MenuItem back = menu.item("back");
        if (back.enabled() && back.hasSlots()) {
            place(inventory, back, buildButton(player, back, "gui.buttons.back-to-editor", null));
        }
        MenuItem search = menu.item("search");
        if (search.enabled() && search.hasSlots()) {
            String current = filter == null ? plugin.msg(player, "gui.lore.none") : filter;
            place(inventory, search, buildButton(player, search, "gui.buttons.search",
                    List.of(plugin.msg(player, "gui.lore.current-filter", "%filter%", current),
                            plugin.msg(player, "gui.lore.click-type-name"),
                            plugin.msg(player, "gui.lore.filter-description")),
                    "%filter%", current));
        }

        applyFiller(player, inventory, menu);
        player.openInventory(inventory);
        playOpen(player, menu);
    }

    /**
     * Eshya secme listesi — filtre hem ingilizce enum adinda hem cevrilmis adda calisir.
     *
     * <p>Aranan metin, onceden hesaplanmis kucuk harfli ad dizisi uzerinde
     * taranir. Eskiden her arama icin ~1300 esyanin {@code getItemMeta()}'si
     * okunuyordu; bu cagri her seferinde yeni bir meta nesnesi uretir ve oyuncu
     * arama kutusuna her harf yazdiginda bastan calisiyordu.</p>
     */
    public List<ItemStack> filterItems(Player player, String filter) {
        if (filter == null || filter.trim().isEmpty()) return allItems;
        String needle = filter.trim().toLowerCase(Locale.ROOT);
        String underscored = needle.replace(' ', '_');
        String code = lang(player);

        String[] names = searchIndex(code);
        List<ItemStack> items = allItems;
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            // Indeks liste yeniden kuruldugu anda eskiyebilir; boyut uymuyorsa
            // yavas ama dogru yola dusulur.
            String haystack = names != null && i < names.length
                    ? names[i]
                    : lowerNameOf(code, items.get(i));
            if (haystack.contains(needle) || haystack.contains(underscored)) {
                out.add(items.get(i));
            }
        }
        return out;
    }

    /**
     * Bir dil icin "enum adi + cevrilmis ad" birlesik arama dizisi.
     *
     * <p>{@link #rebuildItemList()} ve dil yeniden yuklemesi bu onbellegi
     * gecersiz kilar, boylece ceviri degistiginde arama eski adlarla calismaz.</p>
     */
    private String[] searchIndex(String code) {
        return searchIndexCache.computeIfAbsent(code, c -> {
            List<ItemStack> items = allItems;
            String[] out = new String[items.size()];
            for (int i = 0; i < items.size(); i++) {
                out[i] = lowerNameOf(c, items.get(i));
            }
            return out;
        });
    }

    private String lowerNameOf(String code, ItemStack stack) {
        return (stack.getType().name() + '\u0000' + displayNameOf(code, stack)).toLowerCase(Locale.ROOT);
    }

    /** Listedeki bir eshyanin alicinin dilindeki adi. */
    private String displayNameOf(String code, ItemStack stack) {
        if (stack.getItemMeta() instanceof PotionMeta potionMeta) {
            PotionType type = potionMeta.getBasePotionType();
            if (type != null) return plugin.names().potion(code, stack.getType(), type);
        }
        if (stack.getItemMeta() instanceof EnchantmentStorageMeta storage && !storage.getStoredEnchants().isEmpty()) {
            var entry = storage.getStoredEnchants().entrySet().iterator().next();
            return plugin.names().enchantedBook(code, entry.getKey().getKey().getKey(), entry.getValue());
        }
        return plugin.names().material(code, stack.getType().name());
    }

    /**
     * Listedeki eshyaya cevrilmis ad yazar.
     *
     * <p>Isim yazmasaydik istemci vanilya (Ingilizce ya da oyuncunun istemci dili)
     * adini gosterirdi; sunucu dilinde bir menude bu tutarsiz durur.</p>
     */
    private ItemStack nameForSelector(String code, ItemStack source) {
        ItemStack stack = source.clone();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§f" + displayNameOf(code, stack));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // =========================================================== iksir turu

    public void openPotionTypeSelector(Player player) {
        MenuConfig menu = plugin.menus().get(MenuRegistry.POTION_SELECTOR);
        UUID playerId = player.getUniqueId();
        Material material = selectedMaterial.getOrDefault(playerId, Material.POTION);

        String title = menuTitle(player, menu, "potion-selector");
        OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.POTION_SELECTOR);
        Inventory inventory = createInventory(menu, holder, title);

        String code = lang(player);
        int[] contentSlots = menu.contentSlots();
        int index = 0;
        for (PotionType potionType : PotionType.values()) {
            if (index >= contentSlots.length) break;
            try {
                ItemStack potionItem = new ItemStack(material);
                PotionMeta meta = (PotionMeta) potionItem.getItemMeta();
                meta.setBasePotionType(potionType);
                meta.setDisplayName("§e" + plugin.names().potion(code, material, potionType));
                potionItem.setItemMeta(meta);
                inventory.setItem(contentSlots[index++], potionItem);
            } catch (Exception e) {
                plugin.getLogger().warning("Iksir eklenemedi: " + potionType.name() + " - " + e.getMessage());
            }
        }

        MenuItem back = menu.item("back");
        if (back.enabled() && back.hasSlots()) {
            place(inventory, back, buildButton(player, back, "gui.buttons.back-to-order", null));
        }

        applyFiller(player, inventory, menu);
        player.openInventory(inventory);
        playOpen(player, menu);
    }

    // =========================================================== teslim etme

    public void openDeliverItems(Player player, Order order) {
        MenuConfig menu = plugin.menus().get(MenuRegistry.DELIVER_ITEMS);
        String title = menuTitle(player, menu, "deliver-items");
        OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.DELIVER_ITEMS, 1, null, order);
        Inventory inventory = createInventory(menu, holder, title);

        MenuItem quickFill = menu.item("quick-fill");
        if (plugin.settings().quickFill() && quickFill.enabled() && quickFill.hasSlots()) {
            int remaining = order.getNeeded() - order.getFilled();
            String itemName = getOrderDisplayName(player, order);
            place(inventory, quickFill, buildButton(player, quickFill, "gui.buttons.quick-fill",
                    List.of(plugin.msg(player, "gui.lore.quick-fill-desc"),
                            "",
                            plugin.msg(player, "gui.lore.quick-fill-item", "%item%", itemName),
                            plugin.msg(player, "gui.lore.quick-fill-remaining", "%amount%", String.valueOf(remaining)),
                            "",
                            plugin.msg(player, "gui.lore.quick-fill-click")),
                    "%item%", itemName, "%amount%", String.valueOf(remaining)));
        }

        applyFiller(player, inventory, menu);
        player.openInventory(inventory);
        playOpen(player, menu);
    }

    public void openConfirmDelivery(Player player, Order order, int deliverAmount, double payment) {
        MenuConfig menu = plugin.menus().get(MenuRegistry.CONFIRM_DELIVERY);
        String title = menuTitle(player, menu, "confirm-delivery");
        OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.CONFIRM_DELIVERY, 1, null, order);
        Inventory inventory = createInventory(menu, holder, title);

        String itemName = getOrderDisplayName(player, order);
        String ownerName = getPlayerName(order.getOwner());
        String paymentText = TextUtil.formatNumber(payment);

        MenuItem cancel = menu.item("cancel");
        if (cancel.enabled() && cancel.hasSlots()) {
            place(inventory, cancel, buildButton(player, cancel, "gui.buttons.cancel",
                    List.of(plugin.msg(player, "gui.lore.cancel-return-items"))));
        }

        MenuItem preview = menu.item("preview");
        if (preview.enabled() && preview.hasSlots()) {
            List<String> lore = new ArrayList<>();
            lore.add("§f" + TextUtil.formatNumber(order.getNeeded()) + " " + itemName);
            lore.add("§a" + money(String.format(Locale.ROOT, "%.2f", order.getPricePerItem()))
                    + " §f" + plugin.msg(player, "gui.lore.each"));
            lore.addAll(enchantmentLore(player, order));
            lore.add("");
            lore.add(plugin.msg(player, "gui.lore.youre-delivering",
                    "%amount%", String.valueOf(deliverAmount), "%item%", itemName));
            ItemStack base = createOrderItemStack(order);
            base.setAmount(Math.max(1, Math.min(deliverAmount, 64)));
            place(inventory, preview, new ItemBuilder(base)
                    .setName(plugin.msg(player, "gui.lore.owners-order", "%player%", ownerName))
                    .setLore(lore).hideFlags().build());
        }

        MenuItem confirm = menu.item("confirm");
        if (confirm.enabled() && confirm.hasSlots()) {
            place(inventory, confirm, buildButton(player, confirm, "gui.buttons.confirm",
                    List.of(plugin.msg(player, "gui.lore.click-to-deliver-items"), "§a(" + money(paymentText) + ")"),
                    "%price%", paymentText));
        }

        applyFiller(player, inventory, menu);
        player.openInventory(inventory);
        playOpen(player, menu);
    }

    public void openConfirmSell(Player player, Order order, List<ItemStack> itemsToSell, double payment) {
        MenuConfig menu = plugin.menus().get(MenuRegistry.CONFIRM_SELL);
        String title = menuTitle(player, menu, "confirm-sell");
        OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.CONFIRM_SELL, 1, null, order);
        Inventory inventory = createInventory(menu, holder, title);

        int totalAmount = itemsToSell.stream().mapToInt(ItemStack::getAmount).sum();
        String itemName = getOrderDisplayName(player, order);
        String priceText = TextUtil.formatNumber(payment);

        MenuItem cancel = menu.item("cancel");
        if (cancel.enabled() && cancel.hasSlots()) {
            place(inventory, cancel, buildButton(player, cancel, "gui.buttons.cancel",
                    List.of(plugin.msg(player, "gui.lore.cancel-sell"))));
        }

        MenuItem preview = menu.item("preview");
        if (preview.enabled() && preview.hasSlots()) {
            ItemStack base = createOrderItemStack(order);
            base.setAmount(Math.max(1, Math.min(totalAmount, 64)));
            place(inventory, preview, new ItemBuilder(base)
                    .setName(TextUtil.colorize("&#00f986" + totalAmount + "x " + itemName))
                    .setLore(List.of(plugin.msg(player, "gui.lore.sell-price", "%price%", priceText)))
                    .hideFlags().build());
        }

        MenuItem confirm = menu.item("confirm");
        if (confirm.enabled() && confirm.hasSlots()) {
            place(inventory, confirm, buildButton(player, confirm, "gui.buttons.confirm",
                    List.of(plugin.msg(player, "gui.lore.confirm-sell"), "§a(" + money(priceText) + ")"),
                    "%price%", priceText));
        }

        applyFiller(player, inventory, menu);
        player.openInventory(inventory);
        playOpen(player, menu);
    }

    // =========================================================== siparis duzenleme

    public void openEditOrder(Player player, Order order) {
        try {
            MenuConfig menu = plugin.menus().get(MenuRegistry.EDIT_ORDER);
            String title = menuTitle(player, menu, "edit-order");
            OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.EDIT_ORDER, 1, null, order);
            Inventory inventory = createInventory(menu, holder, title);

            int inventoryCount = order.getInventoryCount();

            MenuItem orderButton = menu.item("order");
            if (orderButton.enabled() && orderButton.hasSlots()) {
                place(inventory, orderButton, new ItemBuilder(createOrderItemStack(order))
                        .setName(plugin.msg(player, "gui.lore.order-label", "%item%", getOrderDisplayName(player, order)))
                        .setLore(List.of(
                                plugin.msg(player, "gui.lore.progress",
                                        "%filled%", String.valueOf(order.getFilled()),
                                        "%total%", String.valueOf(order.getNeeded())),
                                plugin.msg(player, "gui.lore.price-label",
                                        "%price%", TextUtil.formatNumber(order.getPricePerItem()))))
                        .hideFlags().build());
            }

            MenuItem collect = menu.item("collect-items");
            List<String> collectLore = new ArrayList<>();
            collectLore.add(plugin.msg(player, "gui.lore.items-waiting", "%amount%", String.valueOf(inventoryCount)));
            collectLore.add(plugin.msg(player, "gui.lore.click-to-open-storage"));

            if (!order.isComplete()) {
                MenuItem cancelOrder = menu.item("cancel-order");
                if (cancelOrder.enabled() && cancelOrder.hasSlots()) {
                    place(inventory, cancelOrder, buildButton(player, cancelOrder, "gui.buttons.cancel-order",
                            List.of(plugin.msg(player, "gui.lore.click-to-cancel"),
                                    plugin.msg(player, "gui.lore.refunds-remaining"),
                                    plugin.msg(player, "gui.lore.collect-first"))));
                }
                if (collect.enabled() && collect.hasSlots()) {
                    place(inventory, collect, buildButton(player, collect, "gui.buttons.collect-items", collectLore,
                            "%amount%", String.valueOf(inventoryCount)));
                }
            } else {
                // Tamamlanan sipariste iptal yoktur; toplama butonu onun yerini alir.
                collectLore.add("");
                collectLore.add(plugin.msg(player, "gui.lore.order-completed"));
                MenuItem cancelOrder = menu.item("cancel-order");
                MenuItem target = cancelOrder.enabled() && cancelOrder.hasSlots() ? cancelOrder : collect;
                if (target.enabled() && target.hasSlots()) {
                    ItemStack stack = buildButton(player, collect.material() == null ? target : collect,
                            "gui.buttons.collect-items", collectLore, "%amount%", String.valueOf(inventoryCount));
                    for (int slot : target.slots()) {
                        if (slot >= 0 && slot < inventory.getSize()) inventory.setItem(slot, stack.clone());
                    }
                }
            }

            MenuItem back = menu.item("back");
            if (back.enabled() && back.hasSlots()) {
                place(inventory, back, buildButton(player, back, "gui.buttons.go-back", null));
            }

            applyFiller(player, inventory, menu);
            player.openInventory(inventory);
            playOpen(player, menu);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, "[DonutOrders] Siparis duzenleme menusu acilamadi: " + order.getId(), ex);
            player.sendMessage(plugin.msg(player, "errors.order-unavailable"));
        }
    }

    // =========================================================== eshya toplama

    public void openCollectItems(Player player, Order order) {
        openCollectItems(player, order, 1);
    }

    public void openCollectItems(Player player, Order order, int page) {
        MenuConfig menu = plugin.menus().get(MenuRegistry.COLLECT_ITEMS);
        List<ItemStack> items = order.getInventory();
        int[] contentSlots = menu.contentSlots();
        int pageSize = Math.max(1, contentSlots.length);
        int pages = totalPages(items.size(), pageSize);
        if (page < 1) page = 1;
        if (page > pages) page = pages;

        String title = menuTitle(player, menu, "collect-items",
                "%page%", String.valueOf(page), "%pages%", String.valueOf(pages));
        OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.COLLECT_ITEMS, page, null, order);
        Inventory inventory = createInventory(menu, holder, title);

        int startIndex = (page - 1) * pageSize;
        for (int i = 0; i < contentSlots.length && startIndex + i < items.size(); i++) {
            inventory.setItem(contentSlots[i], items.get(startIndex + i));
        }

        MenuItem prev = menu.item("previous-page");
        if (prev.enabled() && prev.hasSlots() && page > 1) {
            place(inventory, prev, buildButton(player, prev, "gui.buttons.collection-back", null));
        }
        MenuItem next = menu.item("next-page");
        if (next.enabled() && next.hasSlots() && page < pages) {
            place(inventory, next, buildButton(player, next, "gui.buttons.collection-next", null));
        }
        MenuItem sellAll = menu.item("sell-all");
        if (plugin.settings().sellAll() && sellAll.enabled() && sellAll.hasSlots()) {
            place(inventory, sellAll, buildButton(player, sellAll, "gui.buttons.sell-all",
                    List.of(plugin.msg(player, "gui.lore.sell-all"))));
        }
        MenuItem dropAll = menu.item("drop-all");
        if (dropAll.enabled() && dropAll.hasSlots()) {
            place(inventory, dropAll, buildButton(player, dropAll, "gui.buttons.drop-all", null));
        }

        applyFiller(player, inventory, menu);
        player.openInventory(inventory);
        playOpen(player, menu);
    }

    // =========================================================== buyu secme

    public void openEnchantmentPicker(Player player) {
        openEnchantmentPicker(player, 1);
    }

    public void openEnchantmentPicker(Player player, int page) {
        UUID playerId = player.getUniqueId();
        Material material = selectedMaterial.get(playerId);
        if (material == null || !isEnchantable(material)) {
            openNewOrder(player);
            return;
        }

        MenuConfig menu = plugin.menus().get(MenuRegistry.ENCHANTMENT_PICKER);
        Set<String> selected = selectedEnchantments.getOrDefault(playerId, new HashSet<>());
        List<String> options = enchantmentOptions(material);

        int[] contentSlots = menu.contentSlots();
        int pageSize = Math.max(1, contentSlots.length);
        int pages = totalPages(options.size(), pageSize);
        if (page < 1) page = 1;
        if (page > pages) page = pages;

        String title = menuTitle(player, menu, "enchantment-picker",
                "%page%", String.valueOf(page), "%pages%", String.valueOf(pages));
        OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.ENCHANTMENT_PICKER, page);
        Inventory inventory = createInventory(menu, holder, title);

        String code = lang(player);

        MenuItem previewButton = menu.item("preview");
        if (previewButton.enabled() && previewButton.hasSlots()) {
            ItemStack preview = new ItemStack(material);
            ItemMeta meta = preview.getItemMeta();
            if (meta != null) {
                for (String entry : selected) {
                    String[] parts = splitEnchant(entry);
                    if (parts == null) continue;
                    Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0]));
                    if (enchantment == null) continue;
                    meta.addEnchant(enchantment, Integer.parseInt(parts[1]), true);
                }
                preview.setItemMeta(meta);
            }
            place(inventory, previewButton, new ItemBuilder(preview)
                    .setName("§b" + plugin.names().material(code, material.name()))
                    .build());
        }

        int startIndex = (page - 1) * pageSize;
        for (int i = 0; i < contentSlots.length && startIndex + i < options.size(); i++) {
            String option = options.get(startIndex + i);
            String[] parts = splitEnchant(option);
            if (parts == null) continue;
            boolean isSelected = selected.contains(option);
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            ItemMeta meta = book.getItemMeta();
            String label = plugin.names().enchantmentWithLevel(code, parts[0], Integer.parseInt(parts[1]));
            meta.setDisplayName((isSelected ? "§a" : "§e") + label);
            meta.setLore(List.of(plugin.msg(player,
                    isSelected ? "gui.enchantment-picker.selected" : "gui.enchantment-picker.click-to-select")));
            book.setItemMeta(meta);
            inventory.setItem(contentSlots[i], book);
        }

        MenuItem prev = menu.item("previous-page");
        if (prev.enabled() && prev.hasSlots() && page > 1) {
            place(inventory, prev, buildButton(player, prev, "gui.enchantment-picker.back", null));
        }
        MenuItem next = menu.item("next-page");
        if (next.enabled() && next.hasSlots() && page < pages) {
            place(inventory, next, buildButton(player, next, "gui.enchantment-picker.next", null));
        }
        MenuItem cancel = menu.item("cancel");
        if (cancel.enabled() && cancel.hasSlots()) {
            place(inventory, cancel, buildButton(player, cancel, "gui.enchantment-picker.cancel", null));
        }
        MenuItem confirm = menu.item("confirm");
        if (confirm.enabled() && confirm.hasSlots()) {
            place(inventory, confirm, buildButton(player, confirm, "gui.enchantment-picker.confirm", null));
        }

        applyFiller(player, inventory, menu);
        player.openInventory(inventory);
        playOpen(player, menu);
    }

    /** Secilebilir buyu/seviye ciftleri — tiklama isleyicisi ayni sirayi kullanir. */
    public List<String> enchantmentOptions(Material material) {
        List<String> options = new ArrayList<>();
        for (Enchantment enchantment : getApplicableEnchantments(material)) {
            for (int level = 1; level <= enchantment.getMaxLevel(); level++) {
                options.add(enchantment.getKey().getKey().toUpperCase(Locale.ROOT) + "_" + level);
            }
        }
        return options;
    }

    // =========================================================== dil menusu

    public void openLanguageMenu(Player player) {
        openLanguageMenu(player, 1);
    }

    /**
     * Dil secme ekrani.
     *
     * <p>Sayfalanir: jar 13 dille geliyor ama sunucu sahibi {@code lang/} klasorune
     * daha fazlasini ekleyebilir. Sayfalama olmasaydi {@code content-slots}'a
     * sigmayan diller <b>sessizce kaybolurdu</b> ve oyuncu onlari hic secemezdi.</p>
     */
    public void openLanguageMenu(Player player, int page) {
        MenuConfig menu = plugin.menus().get(MenuRegistry.LANGUAGE);

        List<String> codes = availableLanguages();
        int[] contentSlots = menu.contentSlots();
        int pageSize = Math.max(1, contentSlots.length);
        int pages = totalPages(codes.size(), pageSize);
        if (page < 1) page = 1;
        if (page > pages) page = pages;

        String title = menuTitle(player, menu, "language",
                "%page%", String.valueOf(page), "%pages%", String.valueOf(pages));
        OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.LANGUAGE, page, null, null);
        Inventory inventory = createInventory(menu, holder, title);

        String current = lang(player);
        int startIndex = (page - 1) * pageSize;
        for (int i = 0; i < contentSlots.length && startIndex + i < codes.size(); i++) {
            String code = codes.get(startIndex + i);
            Material icon = languageIcon(menu, code);
            boolean isCurrent = code.equals(current);
            ItemBuilder builder = new ItemBuilder(icon)
                    .setName((isCurrent ? "§a" : "§f") + plugin.getLanguage().displayName(code))
                    .setLore(List.of(isCurrent
                            ? plugin.msg(player, "gui.lore.language-current")
                            : plugin.msg(player, "gui.lore.language-click")));
            if (isCurrent) builder.setGlowing();
            inventory.setItem(contentSlots[i], builder.build());
        }

        MenuItem prev = menu.item("previous-page");
        if (prev.enabled() && prev.hasSlots()) {
            place(inventory, prev, buildButton(player, prev,
                    page > 1 ? "gui.buttons.previous-page" : "gui.buttons.no-previous-page", null));
        }
        MenuItem next = menu.item("next-page");
        if (next.enabled() && next.hasSlots()) {
            place(inventory, next, buildButton(player, next,
                    page < pages ? "gui.buttons.next-page" : "gui.buttons.no-next-page", null));
        }
        MenuItem back = menu.item("back");
        if (back.enabled() && back.hasSlots()) {
            place(inventory, back, buildButton(player, back, "gui.buttons.back-to-menu", null));
        }

        applyFiller(player, inventory, menu);
        player.openInventory(inventory);
        playOpen(player, menu);
    }

    /** Menude gosterilecek dil kodlari — tiklama isleyicisi ayni sirayi kullanir. */
    public List<String> availableLanguages() {
        List<String> codes = new ArrayList<>(plugin.getLanguage().available());
        codes.sort(Comparator.naturalOrder());
        return codes;
    }

    private Material languageIcon(MenuConfig menu, String code) {
        String raw = menu.raw().getString("icons." + code, menu.raw().getString("icons.default", "PAPER"));
        Material material = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
        return material == null ? Material.PAPER : material;
    }
}
