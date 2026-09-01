package ro.server.orderplugin.gui;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.EconomyResponse;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import ro.server.orderplugin.OrderPlugin;
import ro.server.orderplugin.menu.MenuConfig;
import ro.server.orderplugin.menu.MenuItem;
import ro.server.orderplugin.menu.MenuRegistry;
import ro.server.orderplugin.model.FilterType;
import ro.server.orderplugin.model.Order;
import ro.server.orderplugin.model.SortType;
import ro.server.orderplugin.scheduler.SchedulerAdapter;
import ro.server.orderplugin.util.CooldownManager;
import ro.server.orderplugin.util.InputManager;
import ro.server.orderplugin.util.PriceUtil;
import ro.server.orderplugin.util.TextUtil;

/**
 * Tum menu tiklamalarinin tek isleyicisi.
 *
 * <p>Menuler artik <b>basliklarindan degil</b> {@link OrderMenuHolder} isaretcisinden
 * taninir. Baslik hem yapilandirilabilir hem de oyuncunun diline gore degistigi icin
 * metin karsilastirmasi calismazdi; ustelik ayni basligi tasiyan yabanci bir menuye
 * mudahale etme riski vardi.</p>
 *
 * <p>Slot numaralari da kodda sabit degildir: hangi butonun nerede oldugunu
 * {@code menus/*.yml} soyler, burada yalnizca <i>hangi butona</i> basildigi
 * ({@link MenuConfig#buttonAt(int)}) ve icerik alanindaki kacinci sira oldugu
 * ({@link MenuConfig#contentIndexOf(int)}) sorulur. Sunucu sahibi butonlari
 * istedigi yere tasiyabilir.</p>
 */
public class GuiListener implements Listener {

    private static final Logger LOGGER = Logger.getLogger("DonutOrders");

    private final OrderPlugin plugin;
    private final GuiManager gui;
    private final SchedulerAdapter scheduler;

    /** Teslim ekraninda bekleyen esyalar (onay ekranina tasinir). */
    private final ConcurrentHashMap<UUID, List<ItemStack>> deliveryItems = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> deliveryAmount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> deliveryPayment = new ConcurrentHashMap<>();
    /** Teslim animasyonu suresince tutulan esyalar; oyuncu duserse iade edilir. */
    private final ConcurrentHashMap<UUID, List<ItemStack>> animationItems = new ConcurrentHashMap<>();
    /** "Hepsini sat" onayi bekleyen esyalar. */
    private final ConcurrentHashMap<UUID, List<ItemStack>> sellItems = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> sellPayment = new ConcurrentHashMap<>();
    /** Yonetici panelinde "ozellikler" butonunun kacinci ozellige geldigi. */
    private final ConcurrentHashMap<UUID, Integer> featureCursor = new ConcurrentHashMap<>();

    public GuiListener(OrderPlugin plugin) {
        this.plugin = plugin;
        this.gui = plugin.getGuiManager();
        this.scheduler = plugin.getSchedulerAdapter();
    }

    // ================================================================== yardimcilar

    private static OrderMenuHolder holderOf(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof OrderMenuHolder holder ? holder : null;
    }

    private MenuConfig menu(OrderMenuHolder holder) {
        return plugin.menus().get(holder.menuId());
    }

    private static boolean isShulkerBox(Material material) {
        return material != null && material.name().contains("SHULKER_BOX");
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR;
    }

    /** Bir slotun icerik alaninda olup olmadigi. */
    private static boolean isContentSlot(MenuConfig menu, int slot) {
        return menu.contentIndexOf(slot) >= 0;
    }

    /**
     * "64", "1k", "2.5m" gibi girdileri sayiya cevirir.
     * Bicim dilden bagimsizdir; k/m/b evrensel olarak anlasilir.
     */
    private int parseAmount(String input) throws NumberFormatException {
        if (input == null || input.isEmpty()) throw new NumberFormatException("Bos girdi");
        String value = input.trim().toLowerCase(Locale.ROOT).replace(",", "").replace(" ", "");
        double multiplier = 1.0;
        if (value.endsWith("k")) { multiplier = 1_000.0; value = value.substring(0, value.length() - 1); }
        else if (value.endsWith("m")) { multiplier = 1_000_000.0; value = value.substring(0, value.length() - 1); }
        else if (value.endsWith("b")) { multiplier = 1_000_000_000.0; value = value.substring(0, value.length() - 1); }
        double result = Double.parseDouble(value) * multiplier;
        if (result > Integer.MAX_VALUE || result < 0.0) throw new NumberFormatException("Sayi cok buyuk");
        return (int) result;
    }

    private double parsePrice(String input) throws NumberFormatException {
        if (input == null || input.isEmpty()) throw new NumberFormatException("Bos girdi");
        String value = input.trim().toLowerCase(Locale.ROOT).replace(",", ".").replace(" ", "");
        double multiplier = 1.0;
        if (value.endsWith("k")) { multiplier = 1_000.0; value = value.substring(0, value.length() - 1); }
        else if (value.endsWith("m")) { multiplier = 1_000_000.0; value = value.substring(0, value.length() - 1); }
        else if (value.endsWith("b")) { multiplier = 1_000_000_000.0; value = value.substring(0, value.length() - 1); }
        double result = Double.parseDouble(value) * multiplier;
        if (result < 0.0) throw new NumberFormatException("Fiyat negatif olamaz");
        return result;
    }

    /** Verilen esya siparisin istedigi seyle (iksir turu/buyuler/ozel kimlik dahil) birebir esliyor mu. */
    private boolean matchesOrder(ItemStack stack, Order order) {
        if (isEmpty(stack) || order == null) return false;
        if (stack.getType() != order.getMaterial()) return false;

        // Ozel esya kontrolu materyal kontrolunden HEMEN SONRA, cunku her iki yon de
        // onemli:
        //   * Ozel siparise yalnizca ayni ozel esya gider.
        //   * Ozel esya, ayni materyalden NORMAL bir siparise gitmez — aksi halde
        //     oyuncu 500 taslik siparise degerli "Ruby"sini teslim edip kaybederdi.
        if (plugin.customItems().enabled()) {
            if (!plugin.customItems().matches(order.getCustomId(), stack)) return false;
        } else if (order.isCustom()) {
            // Ozel esya destegi kapatildi ama eski ozel siparis duruyor: kimse
            // yanlis esya teslim edemesin.
            return false;
        }

        if (order.isPotion() && order.getPotionType() != null) {
            if (stack.getItemMeta() instanceof PotionMeta potionMeta) {
                PotionType type = potionMeta.getBasePotionType();
                return type != null && type.name().equals(order.getPotionType());
            }
            return false;
        }

        if (order.isEnchantedBook() && order.getEnchantmentType() != null) {
            if (!(stack.getItemMeta() instanceof EnchantmentStorageMeta storage)) return false;
            String[] parts = splitEnchant(order.getEnchantmentType());
            if (parts == null) return false;
            Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0]));
            if (enchantment == null) return false;
            return storage.getStoredEnchantLevel(enchantment) == Integer.parseInt(parts[1]);
        }

        String enchantSpec = order.getEnchantmentType();
        if (enchantSpec != null && !enchantSpec.isEmpty()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return false;
            Map<Enchantment, Integer> present = meta.getEnchants();
            for (String entry : enchantSpec.split(";")) {
                String[] parts = splitEnchant(entry);
                if (parts == null) return false;
                Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0]));
                if (enchantment == null) return false;
                Integer level = present.get(enchantment);
                if (level == null || level != Integer.parseInt(parts[1])) return false;
            }
            return true;
        }
        return true;
    }

    /** "SHARPNESS_5" -> {"sharpness", "5"}; cozulemezse null. */
    private static String[] splitEnchant(String raw) {
        if (raw == null) return null;
        int split = raw.lastIndexOf('_');
        if (split <= 0 || split == raw.length() - 1) return null;
        String name = raw.substring(0, split).toLowerCase(Locale.ROOT);
        String level = raw.substring(split + 1);
        try {
            Integer.parseInt(level);
        } catch (NumberFormatException e) {
            return null;
        }
        return new String[]{name, level};
    }

    /** Envantere sigmayani yere birakir; hicbir esya kaybolmaz. */
    private void giveOrDrop(Player player, ItemStack stack) {
        if (isEmpty(stack)) return;
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (ItemStack overflow : leftover.values()) {
            player.getWorld().dropItem(player.getLocation(), overflow);
        }
    }

    // ================================================================== surukleme

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        OrderMenuHolder holder = holderOf(event.getInventory());
        if (holder == null) return;

        int topSize = event.getView().getTopInventory().getSize();
        MenuConfig menu = menu(holder);
        boolean deliver = holder.is(MenuRegistry.DELIVER_ITEMS);

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= topSize) continue;                       // oyuncunun kendi envanteri
            if (deliver && isContentSlot(menu, rawSlot)) continue;  // teslim alani serbest
            event.setCancelled(true);
            return;
        }
    }

    // ================================================================== tiklama

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        OrderMenuHolder holder = holderOf(event.getInventory());
        if (holder == null || !(event.getWhoClicked() instanceof Player player)) return;

        // Varsayilan: menu kilitli. Yalnizca teslim ekraninin icerik alani serbest
        // birakilir; boylece yeni bir menu eklendiginde esya kacirma acigi olusmaz.
        event.setCancelled(true);

        // Spam korumasi. Teslim ekrani DISARIDA birakilir: orada oyuncu kendi
        // envanterinden esya tasir, her tasimayi beklemeye tabi tutmak menuyu
        // kullanilamaz hale getirirdi (ve orasi zaten siparis listesi kurmuyor).
        if (!holder.is(MenuRegistry.DELIVER_ITEMS)
                && !plugin.cooldowns().checkAndWarn(player, CooldownManager.Type.CLICK)) {
            return;
        }

        try {
            handleClick(event, holder, player);
        } catch (Exception ex) {
            event.setCancelled(true);
            LOGGER.log(Level.SEVERE, "[DonutOrders] Menu tiklamasi islenemedi (menu=" + holder.menuId()
                    + ", slot=" + event.getSlot() + ")", ex);
            plugin.playError(player);
        }
    }

    private void handleClick(InventoryClickEvent event, OrderMenuHolder holder, Player player) {
        MenuConfig menu = menu(holder);
        boolean topClicked = event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getTopInventory());
        int slot = event.getSlot();

        // Teslim ekrani tek istisnadir: oyuncu oraya esya birakabilmeli.
        if (holder.is(MenuRegistry.DELIVER_ITEMS)) {
            handleDeliverItems(event, holder, menu, player, topClicked, slot);
            return;
        }
        if (!topClicked) return;

        String button = menu.buttonAt(slot);
        int contentIndex = menu.contentIndexOf(slot);

        switch (holder.menuId()) {
            case MenuRegistry.MAIN_MENU -> handleMainMenu(holder, menu, player, button, contentIndex);
            case MenuRegistry.YOUR_ORDERS -> handleYourOrders(holder, menu, player, button, contentIndex);
            case MenuRegistry.NEW_ORDER -> handleNewOrder(player, button);
            case MenuRegistry.ITEM_SELECTOR -> handleItemSelector(holder, menu, player, button, contentIndex,
                    event.getCurrentItem());
            case MenuRegistry.POTION_SELECTOR -> handlePotionSelector(player, button, contentIndex,
                    event.getCurrentItem());
            case MenuRegistry.ENCHANTMENT_PICKER -> handleEnchantmentPicker(holder, menu, player, button, contentIndex);
            case MenuRegistry.CONFIRM_DELIVERY -> handleConfirmDelivery(holder, player, button);
            case MenuRegistry.CONFIRM_SELL -> handleConfirmSell(holder, player, button);
            case MenuRegistry.EDIT_ORDER -> handleEditOrder(holder, player, button);
            case MenuRegistry.COLLECT_ITEMS -> handleCollectItems(holder, menu, player, button, contentIndex);
            case MenuRegistry.LANGUAGE -> handleLanguage(holder, player, button, contentIndex);
            case MenuRegistry.ADMIN_MENU -> handleAdminMenu(player, menu, button);
            case MenuRegistry.ADMIN_ORDERS -> handleAdminOrders(holder, menu, player, button, contentIndex, event);
            default -> { }
        }
    }

    // ================================================================== yonetici paneli

    /**
     * Yonetici panelinin izin kontrolu.
     *
     * <p>Menu acildiktan sonra izni alinan bir oyuncu panelde kalabilir; bu yuzden
     * izin her tiklamada yeniden sorulur, yalnizca acilista degil.</p>
     */
    private boolean adminAllowed(Player player) {
        if (player.hasPermission("orders.admin")) return true;
        player.closeInventory();
        player.sendMessage(plugin.msg(player, "general.no-permission"));
        plugin.playError(player);
        return false;
    }

    private void handleAdminMenu(Player player, MenuConfig menu, String button) {
        if (!adminAllowed(player) || button == null) return;
        playButton(player, menu, button);

        switch (button) {
            case "reload" -> {
                long start = System.currentTimeMillis();
                plugin.reloadAllConfigs();
                player.sendMessage(plugin.msg(player, "admin.reloaded",
                        "%ms%", String.valueOf(System.currentTimeMillis() - start)));
                plugin.playSuccess(player);
                plugin.adminGui().openAdminMenu(player);
            }
            case "orders" -> plugin.adminGui().openAdminOrders(player, 1, null);
            case "stats" -> plugin.adminGui().openAdminMenu(player);   // istatistikler yeniden hesaplanir
            case "cleanup" -> {
                int before = plugin.getOrderManager().getActiveOrders().size();
                plugin.getOrderManager().cleanupExpiredOrders();
                int removed = before - plugin.getOrderManager().getActiveOrders().size();
                player.sendMessage(plugin.msg(player, "admin.cleanup-done",
                        "%amount%", String.valueOf(Math.max(0, removed))));
                plugin.playSuccess(player);
                plugin.adminGui().openAdminMenu(player);
            }
            case "features" -> {
                // Her tiklama bir sonraki ozelligi cevirir; hangisinin degistigi
                // mesajda yazar ki yonetici yanlislikla baskasini kapatmasin.
                String next = nextFeature(player);
                boolean value = !plugin.getConfig().getBoolean("features." + next, true);
                writeConfig(player, "features." + next, value);
                player.sendMessage(plugin.msg(player, value ? "admin.feature-enabled" : "admin.feature-disabled",
                        "%feature%", next));
                plugin.adminGui().openAdminMenu(player);
            }
            case "tax" -> {
                boolean value = !plugin.settings().taxEnabled();
                writeConfig(player, "tax.enabled", value);
                player.sendMessage(plugin.msg(player, value ? "admin.tax-enabled" : "admin.tax-disabled"));
                plugin.adminGui().openAdminMenu(player);
            }
            case "levels" -> {
                toggleLevels(player);
                plugin.adminGui().openAdminMenu(player);
            }
            case "close" -> player.closeInventory();
            default -> { }
        }
    }

    /** Panelde sirayla cevrilecek bir sonraki ozellik. */
    private String nextFeature(Player player) {
        int index = featureCursor.merge(player.getUniqueId(), 1, Integer::sum);
        List<String> features = AdminGuiManager.features(plugin);
        return features.get(Math.floorMod(index - 1, features.size()));
    }

    /**
     * Ayari config.yml'ye yazar ve aninda uygular.
     *
     * <p>Diske yazilamazsa degisiklik yalnizca bu oturum icin gecerli olur;
     * yonetici bunu bilmeli, bu yuzden uyari hem konsola hem oyuncuya gider.</p>
     */
    private void writeConfig(Player player, String path, Object value) {
        plugin.getConfig().set(path, value);
        try {
            plugin.saveConfig();
        } catch (Exception e) {
            plugin.getLogger().warning("config.yml kaydedilemedi (" + e.getMessage() + ").");
            player.sendMessage(plugin.msg(player, "admin.save-failed"));
        }
        plugin.settings().load();
    }

    /** Seviye sistemi levels.yml'de tutuldugu icin ayri yazilir. */
    private void toggleLevels(Player player) {
        File file = new File(plugin.getDataFolder(), "levels.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        boolean value = !config.getBoolean("enabled", true);
        config.set("enabled", value);
        try {
            config.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("levels.yml kaydedilemedi (" + e.getMessage() + ").");
            player.sendMessage(plugin.msg(player, "admin.save-failed"));
            return;
        }
        plugin.levels().load();
        player.sendMessage(plugin.msg(player, value ? "admin.levels-enabled" : "admin.levels-disabled"));
        plugin.playSuccess(player);
    }

    private void handleAdminOrders(OrderMenuHolder holder, MenuConfig menu, Player player,
                                   String button, int contentIndex, InventoryClickEvent event) {
        if (!adminAllowed(player)) return;
        playButton(player, menu, button);

        int page = Math.max(1, holder.page());
        String query = holder.query();
        List<Order> orders = plugin.adminGui().adminOrders(query);

        if (button != null) {
            switch (button) {
                case "back" -> plugin.adminGui().openAdminMenu(player);
                case "previous-page" -> {
                    if (page > 1) plugin.adminGui().openAdminOrders(player, page - 1, query);
                }
                case "next-page" -> {
                    if (page < pageCount(orders.size(), menu.pageSize())) {
                        plugin.adminGui().openAdminOrders(player, page + 1, query);
                    }
                }
                case "search" -> {
                    player.closeInventory();
                    plugin.input().request(player, InputManager.Type.SEARCH, input -> {
                        String value = input.equalsIgnoreCase("clear") || input.equalsIgnoreCase("reset")
                                ? null : input;
                        plugin.adminGui().openAdminOrders(player, 1, value);
                    }, () -> plugin.adminGui().openAdminOrders(player, page, query));
                }
                default -> { }
            }
            return;
        }
        if (contentIndex < 0) return;

        int index = (page - 1) * Math.max(1, menu.pageSize()) + contentIndex;
        if (index >= orders.size()) return;
        Order order = orders.get(index);

        // Kaldirma bilerek shift+sag tika baglidir: tek tikla baskasinin
        // siparisi silinmemeli.
        if (event.isShiftClick() && event.isRightClick()) {
            plugin.getOrderManager().adminRemoveOrder(player, order);
            plugin.adminGui().openAdminOrders(player, page, query);
            return;
        }
        for (String line : plugin.adminGui().orderDetails(player, order)) {
            player.sendMessage(line);
        }
    }

    /**
     * Butona basildiginda ses calar.
     *
     * <p>Once menu dosyasindaki {@code sound} denenir. Buton kendi sesini
     * tanimlamamissa buton turune gore genel olay sesine dusulur; boylece
     * "tum sayfa cevirme sesleri" tek yerden degistirilebilir. Ikisi birden
     * calmaz — buton bazli tanim her zaman kazanir.</p>
     */
    private void playButton(Player player, MenuConfig menu, String button) {
        if (button == null) return;
        MenuItem item = menu.item(button);
        if (!item.sound().silent()) {
            plugin.playSound(player, item.sound());
            return;
        }
        String event = switch (button) {
            case "previous-page", "next-page" -> "page-turn";
            default -> null;
        };
        if (event != null) plugin.playEventSound(player, event);
    }

    // ------------------------------------------------------------------ ana menu

    private void handleMainMenu(OrderMenuHolder holder, MenuConfig menu, Player player,
                                String button, int contentIndex) {
        UUID playerId = player.getUniqueId();
        int page = holder.page();
        String search = holder.query();
        playButton(player, menu, button);

        if (button != null) {
            switch (button) {
                case "previous-page" -> {
                    if (page > 1) gui.openMainMenuPaged(player, page - 1, search);
                }
                case "next-page" -> {
                    int pages = pageCount(gui.sortedOrders(playerId, search).size(), menu.pageSize());
                    if (page < pages) gui.openMainMenuPaged(player, page + 1, search);
                }
                case "sort" -> {
                    if (!plugin.settings().sort()) return;
                    SortType current = gui.playerSortType.getOrDefault(playerId, SortType.RECENTLY_LISTED);
                    gui.playerSortType.put(playerId, current.next());
                    gui.openMainMenuPaged(player, page, search);
                }
                case "filter" -> {
                    if (!plugin.settings().filter()) return;
                    FilterType current = gui.playerFilterType.getOrDefault(playerId, FilterType.ALL);
                    gui.playerFilterType.put(playerId, current.next());
                    gui.openMainMenuPaged(player, 1, search);
                }
                case "refresh" -> gui.openMainMenuPaged(player, page, search);
                case "level" -> {
                    // Seviye butonu bilgi amaclidir; tiklayinca ozeti sohbete yazar
                    // ki oyuncu menuden cikmadan da kaydini gorebilsin.
                    if (!plugin.levels().enabled()) return;
                    for (String line : gui.levelLore(player)) {
                        if (!line.isEmpty()) player.sendMessage(line);
                    }
                }
                case "search" -> {
                    if (!plugin.settings().search()) return;
                    player.closeInventory();
                    plugin.input().request(player, InputManager.Type.SEARCH, input -> {
                        if (input.equalsIgnoreCase("clear") || input.equalsIgnoreCase("reset")) {
                            gui.playerSearchQuery.remove(playerId);
                            player.sendMessage(plugin.msg(player, "success.search-cleared"));
                        } else {
                            gui.playerSearchQuery.put(playerId, input);
                            player.sendMessage(plugin.msg(player, "success.search-applied", "%search%", input));
                        }
                        gui.openMainMenuPaged(player, 1, gui.playerSearchQuery.get(playerId));
                    }, () -> gui.openMainMenuPaged(player, page, search));
                }
                case "your-orders" -> gui.openYourOrders(player);
                case "language" -> {
                    if (plugin.settings().languageMenu()) gui.openLanguageMenu(player);
                }
                default -> { }
            }
            return;
        }

        if (contentIndex < 0) return;
        List<Order> orders = gui.sortedOrders(playerId, search);
        int index = (page - 1) * Math.max(1, menu.pageSize()) + contentIndex;
        if (index >= orders.size()) return;

        Order target = orders.get(index);
        if (target.getOwner().equals(playerId)) {
            player.sendMessage(plugin.msg(player, "errors.cant-fulfill-own"));
            plugin.playError(player);
            return;
        }
        gui.openDeliverItems(player, target);
    }

    private static int pageCount(int itemCount, int pageSize) {
        if (pageSize <= 0) return 1;
        return Math.max(1, (int) Math.ceil(itemCount / (double) pageSize));
    }

    // ------------------------------------------------------------------ siparislerim

    private void handleYourOrders(OrderMenuHolder holder, MenuConfig menu, Player player,
                                  String button, int contentIndex) {
        UUID playerId = player.getUniqueId();
        playButton(player, menu, button);

        if ("back".equals(button)) {
            gui.openMainMenu(player);
            return;
        }
        if ("new-order".equals(button)) {
            startNewOrder(player);
            return;
        }
        if (contentIndex < 0) return;

        List<Order> orders = gui.visibleOrders(playerId);
        int index = (holder.page() - 1) * Math.max(1, menu.pageSize()) + contentIndex;

        // slot: -1 ise "yeni siparis" butonu son siparisin hemen ardina konur;
        // o slota basmak yeni siparis akisini baslatir.
        MenuItem newOrder = menu.item("new-order");
        if (index == orders.size() && newOrder.enabled() && newOrder.slot() < 0) {
            plugin.playSound(player, newOrder.sound());
            startNewOrder(player);
            return;
        }
        if (index >= orders.size()) return;

        Order selected = orders.get(index);
        player.closeInventory();
        scheduler.runOnEntityLater(plugin, player, () -> gui.openEditOrder(player, selected), 1L);
    }

    private void startNewOrder(Player player) {
        UUID playerId = player.getUniqueId();
        gui.selectedMaterial.remove(playerId);
        gui.selectedPotionType.remove(playerId);
        gui.selectedEnchantmentType.remove(playerId);
        gui.selectedEnchantments.remove(playerId);
        gui.selectedAmount.put(playerId, Math.max(1, plugin.settings().minAmount()));
        gui.selectedPrice.put(playerId, Math.max(0.01, plugin.settings().minPrice()));
        gui.openNewOrder(player);
    }

    // ------------------------------------------------------------------ yeni siparis

    private void handleNewOrder(Player player, String button) {
        if (button == null) return;
        UUID playerId = player.getUniqueId();
        MenuConfig menu = plugin.menus().get(MenuRegistry.NEW_ORDER);
        playButton(player, menu, button);

        switch (button) {
            case "cancel" -> gui.openYourOrders(player);
            case "item" -> gui.openItemSelector(player, 1, null);
            case "amount" -> {
                player.closeInventory();
                plugin.input().request(player, InputManager.Type.AMOUNT, input -> {
                    try {
                        int amount = parseAmount(input);
                        int min = Math.max(1, plugin.settings().minAmount());
                        int max = plugin.settings().maxAmount();
                        if (amount < min) {
                            player.sendMessage(plugin.msg(player, "errors.min-amount-not-met",
                                    "%amount%", String.valueOf(min)));
                            plugin.playError(player);
                        } else if (max > 0 && amount > max) {
                            player.sendMessage(plugin.msg(player, "errors.max-amount-exceeded",
                                    "%amount%", String.valueOf(max)));
                            plugin.playError(player);
                        } else {
                            gui.selectedAmount.put(playerId, amount);
                            player.sendMessage(plugin.msg(player, "success.amount-set",
                                    "%amount%", TextUtil.formatNumber(amount)));
                            plugin.playSuccess(player);
                        }
                    } catch (NumberFormatException e) {
                        player.sendMessage(plugin.msg(player, "errors.invalid-number-format"));
                        plugin.playError(player);
                    }
                    gui.openNewOrder(player);
                }, () -> gui.openNewOrder(player));
            }
            case "price" -> {
                player.closeInventory();
                plugin.input().request(player, InputManager.Type.PRICE, input -> {
                    try {
                        double price = parsePrice(input);
                        double min = plugin.settings().minPrice();
                        double max = plugin.settings().maxPrice();
                        if (price < min) {
                            player.sendMessage(plugin.msg(player, "errors.min-price-not-met",
                                    "%price%", TextUtil.formatNumber(min)));
                            plugin.playError(player);
                        } else if (max > 0 && price > max) {
                            player.sendMessage(plugin.msg(player, "errors.max-price-exceeded",
                                    "%price%", TextUtil.formatNumber(max)));
                            plugin.playError(player);
                        } else {
                            gui.selectedPrice.put(playerId, price);
                            player.sendMessage(plugin.msg(player, "success.price-set",
                                    "%price%", TextUtil.formatNumber(price)));
                            plugin.playSuccess(player);
                        }
                    } catch (NumberFormatException e) {
                        player.sendMessage(plugin.msg(player, "errors.invalid-number"));
                        plugin.playError(player);
                    }
                    gui.openNewOrder(player);
                }, () -> gui.openNewOrder(player));
            }
            case "confirm" -> confirmNewOrder(player);
            default -> { }
        }
    }

    private void confirmNewOrder(Player player) {
        UUID playerId = player.getUniqueId();
        Material material = gui.selectedMaterial.get(playerId);
        int amount = gui.selectedAmount.getOrDefault(playerId, 0);
        double price = gui.selectedPrice.getOrDefault(playerId, 0.0);

        if (material == null) {
            player.sendMessage(plugin.msg(player, "gui.new-order.item-not-selected"));
            plugin.playError(player);
            return;
        }
        if (amount <= 0) {
            player.sendMessage(plugin.msg(player, "gui.new-order.amount-not-set"));
            plugin.playError(player);
            return;
        }
        if (price <= 0.0) {
            player.sendMessage(plugin.msg(player, "gui.new-order.price-not-set"));
            plugin.playError(player);
            return;
        }
        // Siparis olusturmanin kendi (daha uzun) beklemesi vardir: her siparis
        // veritabanina yazilir ve tum oyunculara acilan listeyi tazeler.
        if (!plugin.cooldowns().checkAndWarn(player, CooldownManager.Type.CREATE)) return;

        String potionType = gui.selectedPotionType.get(playerId);
        String enchantType = gui.selectedEnchantmentType.get(playerId);
        if (plugin.getOrderManager().createOrder(player, material, amount, price, potionType, enchantType)) {
            gui.selectedPotionType.remove(playerId);
            gui.selectedEnchantmentType.remove(playerId);
            gui.selectedEnchantments.remove(playerId);
            plugin.playSuccess(player);
            player.closeInventory();
            gui.openMainMenu(player);
        }
    }

    // ------------------------------------------------------------------ esya secme

    private void handleItemSelector(OrderMenuHolder holder, MenuConfig menu, Player player,
                                    String button, int contentIndex, ItemStack clicked) {
        UUID playerId = player.getUniqueId();
        int page = holder.page();
        String filter = holder.query();
        playButton(player, menu, button);

        if (button != null) {
            switch (button) {
                case "previous-page" -> {
                    if (page > 1) gui.openItemSelector(player, page - 1, filter);
                }
                case "next-page" -> {
                    int pages = pageCount(gui.filterItems(player, filter).size(), menu.pageSize());
                    if (page < pages) gui.openItemSelector(player, page + 1, filter);
                }
                case "back" -> gui.openNewOrder(player);
                case "search" -> {
                    player.closeInventory();
                    plugin.input().request(player, InputManager.Type.FILTER, input -> {
                        String query = input.equalsIgnoreCase("clear") || input.equalsIgnoreCase("reset")
                                ? null : input;
                        if (query != null) {
                            player.sendMessage(plugin.msg(player, "success.filter-applied", "%search%", query));
                        }
                        gui.openItemSelector(player, 1, query);
                    }, () -> gui.openItemSelector(player, page, filter));
                }
                default -> { }
            }
            return;
        }
        if (contentIndex < 0 || isEmpty(clicked)) return;

        Material material = clicked.getType();
        if (plugin.settings().isBlacklisted(material)) {
            player.sendMessage(plugin.msg(player, "errors.item-blacklisted"));
            plugin.playError(player);
            return;
        }
        gui.selectedMaterial.put(playerId, material);

        boolean isPotion = material == Material.POTION || material == Material.SPLASH_POTION
                || material == Material.LINGERING_POTION;

        if (isPotion) {
            gui.selectedEnchantmentType.remove(playerId);
            gui.selectedEnchantments.remove(playerId);
            PotionType type = clicked.getItemMeta() instanceof PotionMeta potionMeta
                    ? potionMeta.getBasePotionType() : null;
            if (type == null) {
                // Turu belirlenemeyen sise (surum farki, ozel eshya): oyuncu turu
                // ayri ekrandan sececek. Ozellik kapaliysa siparis turusuz gecer.
                gui.selectedPotionType.remove(playerId);
                if (plugin.settings().potions()) {
                    gui.openPotionTypeSelector(player);
                    return;
                }
            } else {
                gui.selectedPotionType.put(playerId, type.name());
            }
            gui.openNewOrder(player);
            return;
        }
        if (material == Material.ENCHANTED_BOOK && clicked.getItemMeta() instanceof EnchantmentStorageMeta storage) {
            if (!storage.getStoredEnchants().isEmpty()) {
                Map.Entry<Enchantment, Integer> entry = storage.getStoredEnchants().entrySet().iterator().next();
                gui.selectedEnchantmentType.put(playerId,
                        entry.getKey().getKey().getKey().toUpperCase(Locale.ROOT) + "_" + entry.getValue());
            }
            gui.selectedPotionType.remove(playerId);
            gui.selectedEnchantments.remove(playerId);
            gui.openNewOrder(player);
            return;
        }
        if (plugin.settings().enchantments() && gui.isEnchantable(material)) {
            gui.selectedPotionType.remove(playerId);
            gui.selectedEnchantmentType.remove(playerId);
            gui.selectedEnchantments.put(playerId, new HashSet<>());
            gui.openEnchantmentPicker(player, 1);
            return;
        }
        gui.selectedPotionType.remove(playerId);
        gui.selectedEnchantmentType.remove(playerId);
        gui.selectedEnchantments.remove(playerId);
        gui.openNewOrder(player);
    }

    // ------------------------------------------------------------------ iksir turu

    private void handlePotionSelector(Player player, String button, int contentIndex, ItemStack clicked) {
        MenuConfig menu = plugin.menus().get(MenuRegistry.POTION_SELECTOR);
        playButton(player, menu, button);

        if ("back".equals(button)) {
            gui.openNewOrder(player);
            return;
        }
        if (contentIndex < 0 || isEmpty(clicked)) return;
        if (clicked.getItemMeta() instanceof PotionMeta potionMeta) {
            PotionType type = potionMeta.getBasePotionType();
            if (type == null) return;
            gui.selectedPotionType.put(player.getUniqueId(), type.name());
            player.sendMessage(plugin.msg(player, "success.potion-selected"));
            plugin.playSuccess(player);
            gui.openNewOrder(player);
        }
    }

    // ------------------------------------------------------------------ buyu secme

    private void handleEnchantmentPicker(OrderMenuHolder holder, MenuConfig menu, Player player,
                                         String button, int contentIndex) {
        UUID playerId = player.getUniqueId();
        Material material = gui.selectedMaterial.get(playerId);
        if (material == null) {
            player.closeInventory();
            return;
        }
        int page = holder.page();
        List<String> options = gui.enchantmentOptions(material);
        Set<String> selected = gui.selectedEnchantments.computeIfAbsent(playerId, id -> new HashSet<>());
        playButton(player, menu, button);

        if (button != null) {
            switch (button) {
                case "previous-page" -> {
                    if (page > 1) gui.openEnchantmentPicker(player, page - 1);
                }
                case "next-page" -> {
                    if (page < pageCount(options.size(), menu.pageSize())) {
                        gui.openEnchantmentPicker(player, page + 1);
                    }
                }
                case "cancel" -> {
                    gui.selectedEnchantments.remove(playerId);
                    gui.openItemSelector(player, 1, null);
                }
                case "confirm" -> {
                    if (selected.isEmpty()) {
                        gui.selectedEnchantmentType.remove(playerId);
                    } else {
                        gui.selectedEnchantmentType.put(playerId, String.join(";", selected));
                    }
                    gui.openNewOrder(player);
                }
                default -> { }
            }
            return;
        }
        if (contentIndex < 0) return;

        int index = (page - 1) * Math.max(1, menu.pageSize()) + contentIndex;
        if (index >= options.size()) return;
        String option = options.get(index);

        if (selected.contains(option)) {
            selected.remove(option);
        } else {
            int max = plugin.settings().maxEnchantments();
            if (max > 0 && selected.size() >= max) {
                player.sendMessage(plugin.msg(player, "errors.max-enchantments-reached",
                        "%limit%", String.valueOf(max)));
                plugin.playError(player);
                return;
            }
            String[] parts = splitEnchant(option);
            if (parts == null) return;
            Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(parts[0]));
            if (enchantment != null) {
                // Cakisan buyuler (orn. Keskinlik/Patlama) ayni anda secilemez;
                // yenisi seciline eskisi sessizce birakilir.
                Set<String> conflicting = new HashSet<>();
                for (String existing : selected) {
                    String[] existingParts = splitEnchant(existing);
                    if (existingParts == null) continue;
                    Enchantment other = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(existingParts[0]));
                    if (other != null && enchantment.conflictsWith(other)) conflicting.add(existing);
                }
                selected.removeAll(conflicting);
            }
            // Ayni buyunun baska seviyesi seciliyse onun yerine gecer.
            String prefix = parts[0].toUpperCase(Locale.ROOT) + "_";
            selected.removeIf(existing -> existing.startsWith(prefix));
            selected.add(option);
        }
        gui.selectedEnchantments.put(playerId, selected);
        gui.openEnchantmentPicker(player, page);
    }

    // ------------------------------------------------------------------ teslim etme

    private void handleDeliverItems(InventoryClickEvent event, OrderMenuHolder holder, MenuConfig menu,
                                    Player player, boolean topClicked, int slot) {
        Order order = holder.order();
        if (order == null) {
            player.closeInventory();
            return;
        }
        if (!topClicked) {
            // Shift-tikta Bukkit esyayi ustteki ILK bos slota koyar; bu, icerik alani
            // disindaki (buton/dolgu icin ayrilmis) bir slot olabilir ve menu kapaninca
            // oradaki esya okunmaz. Bu yuzden yerlesimi kendimiz yapiyoruz.
            if (event.getClick().isShiftClick() && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                ItemStack moving = event.getCurrentItem();
                if (isEmpty(moving)) return;
                int leftover = placeInContent(event.getView().getTopInventory(), menu, moving);
                if (leftover <= 0) {
                    event.setCurrentItem(null);
                } else if (leftover < moving.getAmount()) {
                    moving.setAmount(leftover);
                }
                return;   // iptal edilmis kalir; tasimayi biz yaptik
            }
            event.setCancelled(false);   // oyuncunun kendi envanteri serbest
            return;
        }
        if (isContentSlot(menu, slot)) {
            event.setCancelled(false);   // teslim alani serbest
            return;
        }

        String button = menu.buttonAt(slot);
        if (!"quick-fill".equals(button) || !plugin.settings().quickFill()) return;
        playButton(player, menu, button);
        quickFill(event.getView().getTopInventory(), menu, player, order);
    }

    /**
     * Yigini icerik slotlarina yerlestirir; yerlestirilemeyen adedi dondurur.
     * Once ayni turden yarim yiginlar doldurulur, sonra bos slotlar kullanilir.
     */
    private int placeInContent(Inventory top, MenuConfig menu, ItemStack stack) {
        int left = stack.getAmount();
        for (int slot : menu.contentSlots()) {
            if (left <= 0) break;
            ItemStack existing = top.getItem(slot);
            if (isEmpty(existing) || !existing.isSimilar(stack)) continue;
            int space = existing.getMaxStackSize() - existing.getAmount();
            if (space <= 0) continue;
            int transfer = Math.min(space, left);
            existing.setAmount(existing.getAmount() + transfer);
            left -= transfer;
        }
        for (int slot : menu.contentSlots()) {
            if (left <= 0) break;
            if (!isEmpty(top.getItem(slot))) continue;
            ItemStack copy = stack.clone();
            int transfer = Math.min(stack.getMaxStackSize(), left);
            copy.setAmount(transfer);
            top.setItem(slot, copy);
            left -= transfer;
        }
        return left;
    }

    /** Oyuncunun envanterindeki uygun esyalari teslim alanina otomatik tasir. */
    private void quickFill(Inventory top, MenuConfig menu, Player player, Order order) {
        int[] contentSlots = menu.contentSlots();
        int remaining = order.getNeeded() - order.getFilled();

        int alreadyPlaced = 0;
        for (int contentSlot : contentSlots) {
            ItemStack existing = top.getItem(contentSlot);
            if (matchesOrder(existing, order)) alreadyPlaced += existing.getAmount();
        }
        int canAdd = remaining - alreadyPlaced;
        if (canAdd <= 0) {
            player.sendMessage(plugin.msg(player, "gui.quick-fill-full"));
            plugin.playError(player);
            return;
        }

        int totalAdded = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && totalAdded < canAdd; i++) {
            ItemStack item = contents[i];
            if (!matchesOrder(item, order)) continue;

            int wanted = Math.min(item.getAmount(), canAdd - totalAdded);
            int placed = 0;
            for (int contentSlot : contentSlots) {
                if (placed >= wanted) break;
                ItemStack existing = top.getItem(contentSlot);
                if (isEmpty(existing)) {
                    ItemStack copy = item.clone();
                    copy.setAmount(wanted - placed);
                    top.setItem(contentSlot, copy);
                    placed = wanted;
                } else if (existing.isSimilar(item) && existing.getAmount() < existing.getMaxStackSize()) {
                    int transfer = Math.min(existing.getMaxStackSize() - existing.getAmount(), wanted - placed);
                    existing.setAmount(existing.getAmount() + transfer);
                    placed += transfer;
                }
            }
            if (placed <= 0) continue;
            if (placed >= item.getAmount()) {
                player.getInventory().setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - placed);
            }
            totalAdded += placed;
        }

        if (totalAdded > 0) {
            player.sendMessage(plugin.msg(player, "gui.quick-fill-added", "%amount%", String.valueOf(totalAdded)));
            plugin.playSuccess(player);
        } else {
            player.sendMessage(plugin.msg(player, "gui.quick-fill-none"));
            plugin.playError(player);
        }
    }

    // ------------------------------------------------------------------ teslimat onayi

    private void handleConfirmDelivery(OrderMenuHolder holder, Player player, String button) {
        if (button == null) return;
        UUID playerId = player.getUniqueId();
        MenuConfig menu = plugin.menus().get(MenuRegistry.CONFIRM_DELIVERY);
        playButton(player, menu, button);

        if ("cancel".equals(button)) {
            List<ItemStack> items = deliveryItems.remove(playerId);
            deliveryAmount.remove(playerId);
            deliveryPayment.remove(playerId);
            if (items != null) items.forEach(item -> giveOrDrop(player, item));
            player.sendMessage(plugin.msg(player, "success.delivery-cancelled"));
            gui.openMainMenu(player);
            return;
        }
        if (!"confirm".equals(button)) return;

        Order order = holder.order();
        List<ItemStack> items = deliveryItems.remove(playerId);
        Integer amount = deliveryAmount.remove(playerId);
        Double payment = deliveryPayment.remove(playerId);
        if (order == null || items == null || amount == null || payment == null) {
            player.closeInventory();
            return;
        }
        // Beklemeye takilirsa esyalar iade edilir; sessizce yutulmalari
        // oyuncunun envanterinden esya kaybetmesi demek olurdu.
        if (!plugin.cooldowns().checkAndWarn(player, CooldownManager.Type.DELIVER)) {
            items.forEach(item -> giveOrDrop(player, item));
            return;
        }
        animationItems.put(playerId, items);
        player.closeInventory();
        runDeliveryAnimation(player, order, items, amount, payment);
    }

    /**
     * Kisa bir eylem cubugu animasyonundan sonra teslimati tamamlar.
     *
     * <p>Esyalar animasyon suresince {@code animationItems} icinde tutulur; oyuncu
     * bu arada cikarsa {@link #onPlayerQuit} onlari geri verir, hicbir sey kaybolmaz.</p>
     */
    private void runDeliveryAnimation(Player player, Order order, List<ItemStack> items,
                                      int amount, double payment) {
        UUID playerId = player.getUniqueId();
        List<String> frames = plugin.msgList(player, "delivery.delivering");
        int[] tick = {0};
        Object[] handle = new Object[1];

        handle[0] = scheduler.runOnEntityTimer(plugin, player, () -> {
            if (tick[0] < 6) {
                if (!frames.isEmpty()) {
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                            (BaseComponent) new TextComponent(frames.get(tick[0] % frames.size())));
                }
                tick[0]++;
                return;
            }
            scheduler.cancelTask(handle[0]);
            animationItems.remove(playerId);

            if (!player.isOnline()) {
                // Baglanti koptu: esyalar oyuncunun son konumuna birakilir.
                for (ItemStack item : items) {
                    if (isEmpty(item)) continue;
                    try {
                        player.getWorld().dropItem(player.getLocation(), item);
                    } catch (Exception ignored) {
                        // Dunya yuklu degilse yapacak bir sey yok; kayit zaten dusmez.
                    }
                }
                return;
            }
            completeDelivery(player, order, items, amount, payment);
        }, 0L, 10L);
    }

    private void completeDelivery(Player player, Order order, List<ItemStack> items, int amount, double payment) {
        List<ItemStack> matched = new ArrayList<>();
        List<ItemStack> returned = new ArrayList<>();
        int[] left = {amount};

        for (ItemStack stack : items) {
            if (stack == null) continue;
            if (left[0] <= 0) {
                returned.add(stack);
                continue;
            }
            if (isShulkerBox(stack.getType())) {
                takeFromShulker(stack, order, left, matched);
                returned.add(stack);
                continue;
            }
            if (!matchesOrder(stack, order)) {
                returned.add(stack);
                continue;
            }
            int take = Math.min(stack.getAmount(), left[0]);
            ItemStack taken = stack.clone();
            taken.setAmount(take);
            matched.add(taken);
            left[0] -= take;
            if (stack.getAmount() > take) {
                stack.setAmount(stack.getAmount() - take);
                returned.add(stack);
            }
        }

        returned.forEach(item -> giveOrDrop(player, item));

        if (payment > 0 && amount > 0 && !matched.isEmpty()) {
            int delivered = matched.stream().mapToInt(ItemStack::getAmount).sum();
            plugin.getOrderManager().fulfillOrder(player, order, delivered, payment, matched);
            plugin.playSuccess(player);
        }
    }

    /** Shulker kutusunun icinden siparise uyan esyalari alir; kutu oyuncuya geri doner. */
    private void takeFromShulker(ItemStack box, Order order, int[] left, List<ItemStack> matched) {
        if (!(box.getItemMeta() instanceof BlockStateMeta blockMeta)) return;
        if (!(blockMeta.getBlockState() instanceof ShulkerBox shulker)) return;

        boolean modified = false;
        Inventory inside = shulker.getInventory();
        for (int i = 0; i < inside.getSize() && left[0] > 0; i++) {
            ItemStack item = inside.getItem(i);
            if (!matchesOrder(item, order)) continue;
            int take = Math.min(item.getAmount(), left[0]);
            ItemStack taken = item.clone();
            taken.setAmount(take);
            matched.add(taken);
            left[0] -= take;
            if (item.getAmount() > take) {
                item.setAmount(item.getAmount() - take);
            } else {
                inside.setItem(i, null);
            }
            modified = true;
        }
        if (modified) {
            blockMeta.setBlockState((BlockState) shulker);
            box.setItemMeta((ItemMeta) blockMeta);
        }
    }

    // ------------------------------------------------------------------ satis onayi

    private void handleConfirmSell(OrderMenuHolder holder, Player player, String button) {
        if (button == null) return;
        UUID playerId = player.getUniqueId();
        MenuConfig menu = plugin.menus().get(MenuRegistry.CONFIRM_SELL);
        playButton(player, menu, button);

        Order order = holder.order();
        if ("cancel".equals(button)) {
            sellItems.remove(playerId);
            sellPayment.remove(playerId);
            if (order != null) {
                gui.openCollectItems(player, order, 1);
            } else {
                gui.openYourOrders(player);
            }
            return;
        }
        if (!"confirm".equals(button)) return;

        List<ItemStack> items = sellItems.remove(playerId);
        Double payment = sellPayment.remove(playerId);
        if (order == null || items == null || payment == null) {
            player.closeInventory();
            return;
        }

        EconomyResponse response = OrderPlugin.getEconomy().depositPlayer((OfflinePlayer) player, payment);
        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("Para yatirma basarisiz (" + player.getName() + "): " + response.errorMessage);
            player.sendMessage(plugin.msg(player, "errors.order-unavailable"));
            plugin.playError(player);
            gui.openCollectItems(player, order, 1);
            return;
        }

        // Yalnizca satilan esyalar cikarilir. clearInventory()/setInventory() burada
        // yanlis olurdu: kopyala-degistir-yaz arada gelen bir teslimatin eklediği
        // eşyayı silerdi. removeItemsByIdentity() referans esitligiyle atomik siler.
        order.removeItemsByIdentity(items);
        plugin.getOrderManager().saveOrders();
        gui.invalidateOrderCache(order.getId());

        String formatted = TextUtil.formatNumber(payment);
        player.sendMessage(plugin.msg(player, "success.items-sold", "%price%", formatted));
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, (BaseComponent) new TextComponent(
                plugin.msg(player, "success.items-sold", "%price%", formatted)));
        plugin.playSuccess(player);
        // levels.yml -> xp.on-sell-per-1000 buraya baglidir. Satis kendi
        // esyasini paraya cevirmektir, karsi taraf yoktur: partner suzgeci
        // uygulanmaz ama gunluk XP tavani yine gecerlidir.
        plugin.levels().award(player, "sell-money", payment);

        if (order.isComplete() && !order.hasItems()) {
            plugin.getOrderManager().removeOrder(order);
        }
        gui.openYourOrders(player);
    }

    // ------------------------------------------------------------------ siparis duzenleme

    private void handleEditOrder(OrderMenuHolder holder, Player player, String button) {
        if (button == null) return;
        Order order = holder.order();
        if (order == null) {
            player.closeInventory();
            return;
        }
        MenuConfig menu = plugin.menus().get(MenuRegistry.EDIT_ORDER);
        playButton(player, menu, button);

        switch (button) {
            case "back" -> gui.openYourOrders(player);
            case "collect-items" -> openCollectionLater(player, order);
            case "cancel-order" -> {
                // Tamamlanmis sipariste iptal butonunun yerini toplama butonu alir.
                if (order.isComplete()) {
                    openCollectionLater(player, order);
                    return;
                }
                plugin.getOrderManager().cancelOrder(player, order);
                player.closeInventory();
            }
            default -> { }
        }
    }

    private void openCollectionLater(Player player, Order order) {
        player.closeInventory();
        scheduler.runOnEntityLater(plugin, player, () -> gui.openCollectItems(player, order, 1), 2L);
    }

    // ------------------------------------------------------------------ esya toplama

    private void handleCollectItems(OrderMenuHolder holder, MenuConfig menu, Player player,
                                    String button, int contentIndex) {
        Order order = holder.order();
        if (order == null) {
            player.closeInventory();
            return;
        }
        int page = holder.page();
        int pageSize = Math.max(1, menu.pageSize());
        playButton(player, menu, button);

        if (button != null) {
            switch (button) {
                case "previous-page" -> {
                    if (page > 1) gui.openCollectItems(player, order, page - 1);
                }
                case "next-page" -> {
                    if (page < pageCount(order.getInventory().size(), pageSize)) {
                        gui.openCollectItems(player, order, page + 1);
                    }
                }
                case "sell-all" -> sellAll(player, order);
                case "drop-all" -> dropPage(player, order, page, pageSize);
                default -> { }
            }
            return;
        }
        if (contentIndex < 0) return;

        // Kopyala-degistir-yaz yerine atomik cikarma: arada gelen bir teslimatin
        // eklediği eşya (order.addItem) artik silinmez. Oyuncuya sigmayan kisim
        // varsa order.addItem ile geri konur (ayni index'e degil, birlestirme/en
        // sona ekleme kurallarina gore — bu sadece kozmetik bir sira farkidir).
        int index = (page - 1) * pageSize + contentIndex;
        ItemStack target = order.removeItemAt(index);
        if (target == null) return;

        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(target);
        if (!leftover.isEmpty()) {
            for (ItemStack extra : leftover.values()) {
                order.addItem(extra);
            }
        }
        plugin.getOrderManager().saveOrders();

        if (order.isComplete() && !order.hasItems()) {
            plugin.getOrderManager().removeOrder(order);
            player.closeInventory();
            player.sendMessage(plugin.msg(player, "success.order-archived"));
            gui.openYourOrders(player);
            return;
        }
        gui.openCollectItems(player, order, page);
    }

    /** Sayfadaki tum esyalari oyuncuya verir, sigmayani yere birakir. */
    private void dropPage(Player player, Order order, int page, int pageSize) {
        // Kopyala-degistir-yaz yerine atomik araligi cikarma: arada gelen bir
        // teslimatin eklediği eşya (order.addItem) artik silinmez.
        int from = (page - 1) * pageSize;
        int to = from + pageSize;
        List<ItemStack> taken = order.removeItemsInRange(from, to);
        if (taken.isEmpty()) return;
        plugin.getOrderManager().saveOrders();

        for (ItemStack stack : taken) {
            if (isEmpty(stack)) continue;
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            for (ItemStack overflow : leftover.values()) {
                Item dropped = player.getWorld().dropItem(player.getEyeLocation(), overflow);
                dropped.setVelocity(player.getLocation().getDirection().multiply(0.3));
                dropped.setPickupDelay(40);
            }
        }
        player.sendMessage(plugin.msg(player, "success.items-dropped"));
        plugin.playSuccess(player);

        if (order.isComplete() && !order.hasItems()) {
            plugin.getOrderManager().removeOrder(order);
            player.closeInventory();
            gui.openYourOrders(player);
            return;
        }
        int pages = pageCount(order.getInventory().size(), pageSize);
        gui.openCollectItems(player, order, Math.min(page, pages));
    }

    /**
     * Toplanan esyalarin tamamini shop eklentisi fiyatiyla satar.
     *
     * <p>Fiyat CMI/ShopGUIPlus/EconomyShopGUI/Essentials'tan okunur; hicbiri yoksa
     * ya da esyanin degeri tanimli degilse satis yapilmaz — uydurma bir fiyat
     * ekonomiyi bozardi.</p>
     */
    private void sellAll(Player player, Order order) {
        if (!plugin.settings().sellAll()) {
            player.sendMessage(plugin.msg(player, "errors.feature-disabled"));
            plugin.playError(player);
            return;
        }
        List<ItemStack> items = order.getInventory();
        if (items.isEmpty()) {
            player.sendMessage(plugin.msg(player, "errors.no-items-in-inventory"));
            plugin.playError(player);
            return;
        }

        double total = 0.0;
        List<ItemStack> sellable = new ArrayList<>();
        for (ItemStack stack : items) {
            if (isEmpty(stack)) continue;
            double unit = PriceUtil.getItemSellPrice(player, stack);
            if (unit <= 0.0) continue;
            total += unit * stack.getAmount();
            sellable.add(stack);
        }
        if (sellable.isEmpty() || total <= 0.0) {
            player.sendMessage(plugin.msg(player, "gui.sell-all-coming-soon"));
            plugin.playError(player);
            return;
        }

        UUID playerId = player.getUniqueId();
        sellItems.put(playerId, sellable);
        sellPayment.put(playerId, total);
        gui.openConfirmSell(player, order, sellable, total);
    }

    // ------------------------------------------------------------------ dil menusu

    private void handleLanguage(OrderMenuHolder holder, Player player, String button, int contentIndex) {
        MenuConfig menu = plugin.menus().get(MenuRegistry.LANGUAGE);
        playButton(player, menu, button);

        List<String> codes = gui.availableLanguages();
        int page = Math.max(1, holder.page());
        int pageSize = Math.max(1, menu.pageSize());

        if (button != null) {
            switch (button) {
                case "back" -> gui.openMainMenu(player);
                case "previous-page" -> {
                    if (page > 1) gui.openLanguageMenu(player, page - 1);
                }
                case "next-page" -> {
                    if (page < pageCount(codes.size(), pageSize)) gui.openLanguageMenu(player, page + 1);
                }
                default -> { }
            }
            return;
        }
        if (contentIndex < 0) return;

        // Tiklanan slotun sayfa icindeki sirasi + sayfa kaymasi = gercek dil sirasi.
        int index = (page - 1) * pageSize + contentIndex;
        if (index >= codes.size()) return;
        String code = codes.get(index);

        plugin.getLanguage().setOverride(player.getUniqueId(), code);
        plugin.getLangStorage().set(player.getUniqueId(), code);
        player.sendMessage(plugin.msg(player, "success.language-changed",
                "%language%", plugin.getLanguage().displayName(code)));
        plugin.playSuccess(player);
        gui.openLanguageMenu(player, page);
    }

    // ================================================================== kapanma

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClose(InventoryCloseEvent event) {
        OrderMenuHolder holder = holderOf(event.getInventory());
        if (holder == null || !(event.getPlayer() instanceof Player player)) return;

        // Kapanis sesi varsayilan olarak "none": bir menuden digerine gecerken de
        // kapanma olayi tetiklendigi icin ses acik gelseydi surekli calardi.
        try {
            gui.playClose(player, plugin.menus().get(holder.menuId()));
        } catch (IllegalStateException ignored) {
            // Menu yeniden yukleme sirasinda kapandi; ses onemsiz.
        }

        if (holder.is(MenuRegistry.DELIVER_ITEMS)) {
            onDeliverClosed(event, holder, player);
            return;
        }
        if (holder.is(MenuRegistry.CONFIRM_DELIVERY)) {
            onConfirmDeliveryClosed(holder, player);
            return;
        }
        if (holder.is(MenuRegistry.CONFIRM_SELL)) {
            // Onaylanmadan kapatildi: bekleyen satis dusurulur, esyalar siparisde kalir.
            sellItems.remove(player.getUniqueId());
            sellPayment.remove(player.getUniqueId());
        }
    }

    /**
     * Teslim ekrani kapaninca icerideki esyalar okunur ve onay ekrani acilir.
     *
     * <p>Siparise uymayan ya da limiti asan her sey aninda iade edilir; oyuncunun
     * esyasi menude asili kalmaz.</p>
     */
    private void onDeliverClosed(InventoryCloseEvent event, OrderMenuHolder holder, Player player) {
        Order order = holder.order();
        if (order == null) return;

        MenuConfig menu = menu(holder);
        UUID playerId = player.getUniqueId();
        List<ItemStack> contents = new ArrayList<>();
        int matching = 0;

        for (int slot : menu.contentSlots()) {
            ItemStack stack = event.getInventory().getItem(slot);
            if (isEmpty(stack)) continue;
            contents.add(stack);
            if (isShulkerBox(stack.getType())) {
                matching += countInShulker(stack, order);
            } else if (matchesOrder(stack, order)) {
                matching += stack.getAmount();
            }
        }
        if (contents.isEmpty()) return;

        int remaining = order.getNeeded() - order.getFilled();
        int deliverable = Math.min(matching, remaining);
        if (deliverable <= 0) {
            contents.forEach(item -> giveOrDrop(player, item));
            if (matching == 0) player.sendMessage(plugin.msg(player, "errors.wrong-item"));
            else player.sendMessage(plugin.msg(player, "errors.order-already-filled"));
            return;
        }
        if (matching > deliverable) {
            player.sendMessage(plugin.msg(player, "delivery.items-returned",
                    "%amount%", String.valueOf(matching - deliverable)));
        }

        deliveryItems.put(playerId, contents);
        deliveryAmount.put(playerId, deliverable);
        deliveryPayment.put(playerId, deliverable * order.getPricePerItem());

        double payment = deliverable * order.getPricePerItem();
        int finalDeliverable = deliverable;
        scheduler.runOnEntityLater(plugin, player,
                () -> gui.openConfirmDelivery(player, order, finalDeliverable, payment), 1L);
    }

    private int countInShulker(ItemStack box, Order order) {
        if (!(box.getItemMeta() instanceof BlockStateMeta blockMeta)) return 0;
        if (!(blockMeta.getBlockState() instanceof ShulkerBox shulker)) return 0;
        int count = 0;
        for (ItemStack item : shulker.getInventory().getContents()) {
            if (matchesOrder(item, order)) count += item.getAmount();
        }
        return count;
    }

    /**
     * Onay ekrani secim yapilmadan kapatildiysa esyalar teslim ekranina geri konur.
     *
     * <p>Onayla/iptal butonlari kendi verilerini once sildigi icin burada yalnizca
     * "kacis" durumu kalir.</p>
     */
    private void onConfirmDeliveryClosed(OrderMenuHolder holder, Player player) {
        UUID playerId = player.getUniqueId();
        List<ItemStack> items = deliveryItems.get(playerId);
        Order order = holder.order();
        if (items == null || order == null) return;

        deliveryItems.remove(playerId);
        deliveryAmount.remove(playerId);
        deliveryPayment.remove(playerId);

        scheduler.runOnEntityLater(plugin, player, () -> {
            gui.openDeliverItems(player, order);
            Inventory top = player.getOpenInventory().getTopInventory();
            MenuConfig menu = plugin.menus().get(MenuRegistry.DELIVER_ITEMS);
            int index = 0;
            int[] contentSlots = menu.contentSlots();
            for (ItemStack stack : items) {
                if (index >= contentSlots.length) {
                    giveOrDrop(player, stack);   // yerlesim kucuduyse esya kaybolmasin
                    continue;
                }
                top.setItem(contentSlots[index++], stack);
            }
        }, 1L);
    }

    // ================================================================== cikis

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // Onay bekleyen ve animasyondaki esyalar sahibine geri verilir.
        returnPending(player, deliveryItems.remove(playerId));
        returnPending(player, animationItems.remove(playerId));

        deliveryAmount.remove(playerId);
        deliveryPayment.remove(playerId);
        sellItems.remove(playerId);
        sellPayment.remove(playerId);

        gui.selectedMaterial.remove(playerId);
        gui.selectedAmount.remove(playerId);
        gui.selectedPrice.remove(playerId);
        gui.selectedPotionType.remove(playerId);
        gui.selectedEnchantmentType.remove(playerId);
        gui.selectedEnchantments.remove(playerId);
        gui.playerSortType.remove(playerId);
        gui.playerFilterType.remove(playerId);
        gui.playerSearchQuery.remove(playerId);

        // Cikan oyuncunun bekleme sayaclari ve partner gecmisi bellekte kalmasin.
        plugin.cooldowns().forget(playerId);
        featureCursor.remove(playerId);
    }

    private void returnPending(Player player, List<ItemStack> items) {
        if (items == null) return;
        for (ItemStack item : items) {
            if (isEmpty(item)) continue;
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack overflow : leftover.values()) {
                try {
                    player.getWorld().dropItem(player.getLocation(), overflow);
                } catch (Exception ignored) {
                    // Dunya erisilemiyorsa yapacak bir sey yok.
                }
            }
        }
    }
}
