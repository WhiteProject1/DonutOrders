package ro.server.orderplugin.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import ro.server.orderplugin.OrderPlugin;
import ro.server.orderplugin.menu.MenuConfig;
import ro.server.orderplugin.menu.MenuItem;
import ro.server.orderplugin.menu.MenuRegistry;
import ro.server.orderplugin.model.Order;
import ro.server.orderplugin.util.ItemBuilder;
import ro.server.orderplugin.util.LoreTemplate;
import ro.server.orderplugin.util.TextUtil;

/**
 * Yonetici panelinin cizimi.
 *
 * <p>{@link GuiManager} ile ayni pakettedir; buton/dolgu/baslik yardimcilarini
 * oradan dogrudan kullanir. Amac kod tekrarini onlemek: bir menu ayari
 * ({@code custom-model-data}, {@code glow}, ses bicimi...) eklendiginde
 * yonetici paneli de kendiliginden destekler.</p>
 */
public final class AdminGuiManager {

    private final OrderPlugin plugin;
    private final GuiManager gui;

    public AdminGuiManager(OrderPlugin plugin, GuiManager gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    // ================================================================== ana panel

    public void openAdminMenu(Player player) {
        MenuConfig menu = plugin.menus().get(MenuRegistry.ADMIN_MENU);
        String title = gui.menuTitle(player, menu, "admin-menu");
        OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.ADMIN_MENU);
        Inventory inventory = gui.createInventory(menu, holder, title);

        place(inventory, menu, player, "reload", "admin.buttons.reload",
                List.of(plugin.msg(player, "admin.lore.reload")));

        place(inventory, menu, player, "orders", "admin.buttons.orders",
                List.of(plugin.msg(player, "admin.lore.orders",
                        "%amount%", String.valueOf(activeOrderCount()))));

        place(inventory, menu, player, "stats", "admin.buttons.stats", statsLore(player));

        place(inventory, menu, player, "cleanup", "admin.buttons.cleanup",
                List.of(plugin.msg(player, "admin.lore.cleanup")));

        place(inventory, menu, player, "features", "admin.buttons.features", featuresLore(player));

        place(inventory, menu, player, "tax", "admin.buttons.tax", taxLore(player));

        place(inventory, menu, player, "levels", "admin.buttons.levels", levelsLore(player));

        place(inventory, menu, player, "close", "admin.buttons.close", List.of());

        gui.applyFiller(player, inventory, menu);
        player.openInventory(inventory);
        gui.playOpen(player, menu);
    }

    private void place(Inventory inventory, MenuConfig menu, Player player,
                       String key, String nameKey, List<String> lore) {
        MenuItem item = menu.item(key);
        if (!item.enabled() || !item.hasSlots()) return;
        gui.place(inventory, item, gui.buildButton(player, item, nameKey, lore));
    }

    // ------------------------------------------------------------------ lore

    private List<String> statsLore(Player player) {
        List<Order> orders = plugin.getOrderManager().getActiveOrders();
        long now = System.currentTimeMillis();

        int active = 0;
        int completed = 0;
        double volume = 0d;
        for (Order order : orders) {
            if (order.isComplete()) {
                completed++;
            } else if (order.getExpiry() > now) {
                active++;
                volume += (double) order.getRemaining() * order.getPricePerItem();
            }
        }

        List<String> template = adminTemplate(player, "stats");
        if (template == null || template.isEmpty()) {
            return statsLoreLegacy(player, active, completed, volume);
        }
        Map<String, String> values = new HashMap<>();
        values.put("active", String.valueOf(active));
        values.put("completed", String.valueOf(completed));
        values.put("volume", TextUtil.formatNumber(volume));
        values.put("players", String.valueOf(Bukkit.getOnlinePlayers().size()));
        return LoreTemplate.render(template, values, Map.of());
    }

    private List<String> statsLoreLegacy(Player player, int active, int completed, double volume) {
        List<String> lore = new ArrayList<>();
        lore.add(plugin.msg(player, "admin.stats.active", "%amount%", String.valueOf(active)));
        lore.add(plugin.msg(player, "admin.stats.completed", "%amount%", String.valueOf(completed)));
        lore.add(plugin.msg(player, "admin.stats.volume", "%amount%", TextUtil.formatNumber(volume)));
        lore.add(plugin.msg(player, "admin.stats.players",
                "%amount%", String.valueOf(Bukkit.getOnlinePlayers().size())));
        return lore;
    }

    private List<String> featuresLore(Player player) {
        List<String> toggleLines = new ArrayList<>();
        for (String feature : features(plugin)) {
            toggleLines.add(toggleLine(player, feature,
                    plugin.getConfig().getBoolean("features." + feature, true)));
        }

        List<String> template = adminTemplate(player, "features");
        if (template == null || template.isEmpty()) {
            List<String> lore = new ArrayList<>(toggleLines);
            lore.add("");
            lore.add(plugin.msg(player, "admin.lore.click-cycle"));
            return lore;
        }
        Map<String, String> values = Map.of("hint", plugin.msg(player, "admin.lore.click-cycle"));
        Map<String, List<String>> lists = Map.of("features", toggleLines);
        return LoreTemplate.render(template, values, lists);
    }

    /** Panelden acilip kapatilabilen ozellikler; config.yml -> features.panel-toggles. */
    public static List<String> features(OrderPlugin plugin) {
        return plugin.settings().featurePanelToggles();
    }

    /**
     * Bir ozellik satirinin gorunumu.
     *
     * <p>Eskiden {@code label} dogrudan config anahtariydi ({@code enchanted-books}
     * gibi); yonetici cevrilmemis, ham bir metin goruyordu. Artik
     * {@code admin.features.<anahtar>} altindan cevrilmis adi alinir; renk,
     * madde isareti ve ayrac ise {@code admin.lore.toggle-line-on/off}
     * sablonundan gelir, boylece kod hicbirini sabit yazmaz.</p>
     */
    private String toggleLine(Player player, String feature, boolean on) {
        String label = plugin.msg(player, "admin.features." + feature);
        String state = plugin.msg(player, on ? "admin.lore.enabled" : "admin.lore.disabled");
        String key = on ? "admin.lore.toggle-line-on" : "admin.lore.toggle-line-off";
        return plugin.msg(player, key, "%label%", label, "%state%", state);
    }

    private List<String> taxLore(Player player) {
        boolean on = plugin.settings().taxEnabled();
        List<String> template = adminTemplate(player, "tax");
        if (template == null || template.isEmpty()) {
            return taxLoreLegacy(player, on);
        }
        Map<String, String> values = new HashMap<>();
        values.put("state", plugin.msg(player, on ? "admin.lore.enabled" : "admin.lore.disabled"));
        values.put("creation", GuiManager.formatPercent(plugin.settings().taxBasePercent("creation")));
        values.put("delivery", GuiManager.formatPercent(plugin.settings().taxBasePercent("delivery")));
        values.put("sell", GuiManager.formatPercent(plugin.settings().taxBasePercent("sell")));
        return LoreTemplate.render(template, values, Map.of());
    }

    private List<String> taxLoreLegacy(Player player, boolean on) {
        List<String> lore = new ArrayList<>();
        lore.add(plugin.msg(player, on ? "admin.lore.enabled" : "admin.lore.disabled"));
        lore.add(plugin.msg(player, "admin.tax.creation",
                "%percent%", GuiManager.formatPercent(plugin.settings().taxBasePercent("creation"))));
        lore.add(plugin.msg(player, "admin.tax.delivery",
                "%percent%", GuiManager.formatPercent(plugin.settings().taxBasePercent("delivery"))));
        lore.add(plugin.msg(player, "admin.tax.sell",
                "%percent%", GuiManager.formatPercent(plugin.settings().taxBasePercent("sell"))));
        lore.add("");
        lore.add(plugin.msg(player, "admin.lore.click-toggle"));
        return lore;
    }

    private List<String> levelsLore(Player player) {
        boolean on = plugin.levels().enabled();
        List<String> template = adminTemplate(player, "levels");
        if (template == null || template.isEmpty()) {
            return levelsLoreLegacy(player, on);
        }

        // Istismar korumasinin durumu burada gorunur: kapali oldugunu fark
        // etmeyen bir sunucu sahibi arkadas ciftliginin farkina varmazdi.
        // Seviye sistemi kapaliyken bu iki satir hic yok (liste bos -> LoreTemplate
        // satiri tamamen siler), boylece geride bosluk kalmaz.
        List<String> details = new ArrayList<>();
        if (on) {
            details.add(plugin.msg(player, "admin.levels.count",
                    "%amount%", String.valueOf(plugin.levels().maxLevel())));
            details.add(plugin.msg(player, "admin.lore.anti-abuse", "%state%",
                    plugin.msg(player, plugin.levels().antiAbuse().enabled()
                            ? "admin.lore.enabled" : "admin.lore.disabled")));
        }

        Map<String, String> values = Map.of("state", plugin.msg(player, on ? "admin.lore.enabled" : "admin.lore.disabled"));
        Map<String, List<String>> lists = Map.of("level-details", details);
        return LoreTemplate.render(template, values, lists);
    }

    private List<String> levelsLoreLegacy(Player player, boolean on) {
        List<String> lore = new ArrayList<>();
        lore.add(plugin.msg(player, on ? "admin.lore.enabled" : "admin.lore.disabled"));
        if (on) {
            lore.add(plugin.msg(player, "admin.levels.count",
                    "%amount%", String.valueOf(plugin.levels().maxLevel())));
            lore.add(plugin.msg(player, "admin.lore.anti-abuse", "%state%",
                    plugin.msg(player, plugin.levels().antiAbuse().enabled()
                            ? "admin.lore.enabled" : "admin.lore.disabled")));
        }
        lore.add("");
        lore.add(plugin.msg(player, "admin.lore.click-toggle"));
        return lore;
    }

    /**
     * {@code admin.templates.<isim>} — oyuncunun dili -> en -> tr sirasiyla aranir.
     * Anahtar yoksa ya da bossa {@code null} doner; cagiran taraf eski sabit-kodlu
     * gorunume duser.
     */
    private List<String> adminTemplate(Player player, String name) {
        String key = "admin.templates." + name;
        String code = plugin.getLanguage().resolve(player);
        List<String> list = plugin.getLanguage().rawList(code, key);
        if (list == null || list.isEmpty()) list = plugin.getLanguage().rawList("en", key);
        if (list == null || list.isEmpty()) list = plugin.getLanguage().rawList("tr", key);
        return list;
    }

    private int activeOrderCount() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (Order order : plugin.getOrderManager().getActiveOrders()) {
            if (!order.isComplete() && order.getExpiry() > now) count++;
        }
        return count;
    }

    // ================================================================== siparis listesi

    /** Panelde listelenecek siparisler — tiklama isleyicisi ayni siralamayi kullanir. */
    public List<Order> adminOrders(String query) {
        long now = System.currentTimeMillis();
        String needle = query == null || query.isBlank() ? null : query.toLowerCase(java.util.Locale.ROOT);

        List<Order> out = new ArrayList<>();
        for (Order order : plugin.getOrderManager().getActiveOrders()) {
            if (order.isComplete() || order.getExpiry() <= now) continue;
            if (needle != null) {
                String owner = gui.ownerName(order.getOwner()).toLowerCase(java.util.Locale.ROOT);
                String material = order.getMaterial().name().toLowerCase(java.util.Locale.ROOT);
                if (!owner.contains(needle) && !material.contains(needle)) continue;
            }
            out.add(order);
        }
        // En pahali siparis en ustte: yonetici once buyuk islemleri gormeli.
        out.sort((a, b) -> Double.compare(
                (double) b.getRemaining() * b.getPricePerItem(),
                (double) a.getRemaining() * a.getPricePerItem()));
        return out;
    }

    public void openAdminOrders(Player player, int page, String query) {
        MenuConfig menu = plugin.menus().get(MenuRegistry.ADMIN_ORDERS);
        List<Order> orders = adminOrders(query);

        int[] contentSlots = menu.contentSlots();
        int pageSize = Math.max(1, contentSlots.length);
        int pages = Math.max(1, (orders.size() + pageSize - 1) / pageSize);
        if (page < 1) page = 1;
        if (page > pages) page = pages;

        String title = gui.menuTitle(player, menu, "admin-orders",
                "%page%", String.valueOf(page), "%pages%", String.valueOf(pages),
                "%search%", query == null ? "" : query);
        OrderMenuHolder holder = new OrderMenuHolder(MenuRegistry.ADMIN_ORDERS, page, query, null);
        Inventory inventory = gui.createInventory(menu, holder, title);

        int start = (page - 1) * pageSize;
        for (int i = 0; i < contentSlots.length && start + i < orders.size(); i++) {
            inventory.setItem(contentSlots[i], adminOrderIcon(player, orders.get(start + i)));
        }

        MenuItem prev = menu.item("previous-page");
        if (prev.enabled() && prev.hasSlots()) {
            gui.place(inventory, prev, gui.buildButton(player, prev,
                    page > 1 ? "gui.buttons.previous-page" : "gui.buttons.no-previous-page", null));
        }
        MenuItem next = menu.item("next-page");
        if (next.enabled() && next.hasSlots()) {
            gui.place(inventory, next, gui.buildButton(player, next,
                    page < pages ? "gui.buttons.next-page" : "gui.buttons.no-next-page", null));
        }
        MenuItem back = menu.item("back");
        if (back.enabled() && back.hasSlots()) {
            gui.place(inventory, back, gui.buildButton(player, back, "gui.buttons.back-to-menu", null));
        }
        MenuItem search = menu.item("search");
        if (search.enabled() && search.hasSlots()) {
            String current = query == null || query.isBlank()
                    ? plugin.msg(player, "gui.lore.none") : query;
            gui.place(inventory, search, gui.buildButton(player, search, "gui.buttons.search",
                    List.of(plugin.msg(player, "gui.lore.current-filter", "%filter%", current),
                            plugin.msg(player, "admin.lore.search-hint")),
                    "%filter%", current));
        }

        gui.applyFiller(player, inventory, menu);
        player.openInventory(inventory);
        gui.playOpen(player, menu);
    }

    private ItemStack adminOrderIcon(Player viewer, Order order) {
        Material material = order.getMaterial() == null ? Material.STONE : order.getMaterial();
        double refund = (double) order.getRemaining() * order.getPricePerItem();

        List<String> template = adminTemplate(viewer, "order");
        List<String> lore = (template == null || template.isEmpty())
                ? adminOrderIconLoreLegacy(viewer, order, refund)
                : LoreTemplate.render(template, adminOrderValues(viewer, order, refund), Map.of());

        return new ItemBuilder(material)
                .setName("§f" + gui.getOrderDisplayName(viewer, order))
                .setLore(lore)
                .build();
    }

    private List<String> adminOrderIconLoreLegacy(Player viewer, Order order, double refund) {
        List<String> lore = new ArrayList<>();
        lore.add(plugin.msg(viewer, "admin.lore.order-owner", "%player%", gui.ownerName(order.getOwner())));
        lore.add(plugin.msg(viewer, "gui.lore.progress",
                "%filled%", String.valueOf(order.getFilled()),
                "%total%", String.valueOf(order.getNeeded())));
        lore.add(plugin.msg(viewer, "gui.lore.price-label",
                "%price%", TextUtil.formatNumber(order.getPricePerItem())));
        lore.add(plugin.msg(viewer, "admin.lore.order-refund", "%price%", TextUtil.formatNumber(refund)));
        lore.add(plugin.msg(viewer, "admin.lore.order-id",
                "%id%", order.getId().toString().substring(0, 8)));
        lore.add("");
        lore.add(plugin.msg(viewer, "admin.lore.order-remove"));
        return lore;
    }

    /** {@code admin.templates.order} ve {@code admin.templates.detail} icin ortak skaler degerler. */
    private Map<String, String> adminOrderValues(Player viewer, Order order, double refund) {
        Map<String, String> values = new HashMap<>();
        values.put("owner", gui.ownerName(order.getOwner()));
        values.put("filled", String.valueOf(order.getFilled()));
        values.put("total", String.valueOf(order.getNeeded()));
        values.put("price", TextUtil.formatNumber(order.getPricePerItem()));
        values.put("refund", TextUtil.formatNumber(refund));
        // Panel ikonunda kisa kimlik yeterli; sohbet dokumunde tam kimlik gerekir
        // (bkz. orderDetails), orada ustune yazilir.
        values.put("id", order.getId().toString().substring(0, 8));
        return values;
    }

    /** Sohbete yazilan ayrinti dokumu (sol tik). */
    public List<String> orderDetails(Player viewer, Order order) {
        double refund = (double) order.getRemaining() * order.getPricePerItem();

        List<String> template = adminTemplate(viewer, "detail");
        if (template == null || template.isEmpty()) {
            return orderDetailsLegacy(viewer, order, refund);
        }
        Map<String, String> values = adminOrderValues(viewer, order, refund);
        values.put("item", gui.getOrderDisplayName(viewer, order));
        values.put("waiting", String.valueOf(order.getInventoryCount()));
        // Tam kimlik: /donutordersadmin removeorder ile kullanilabilsin diye.
        values.put("id", order.getId().toString());
        return LoreTemplate.render(template, values, Map.of());
    }

    private List<String> orderDetailsLegacy(Player viewer, Order order, double refund) {
        List<String> lines = new ArrayList<>();
        lines.add(plugin.msg(viewer, "admin.detail-header",
                "%item%", gui.getOrderDisplayName(viewer, order)));
        lines.add(plugin.msg(viewer, "admin.lore.order-owner",
                "%player%", gui.ownerName(order.getOwner())));
        lines.add(plugin.msg(viewer, "gui.lore.progress",
                "%filled%", String.valueOf(order.getFilled()),
                "%total%", String.valueOf(order.getNeeded())));
        lines.add(plugin.msg(viewer, "gui.lore.price-label",
                "%price%", TextUtil.formatNumber(order.getPricePerItem())));
        lines.add(plugin.msg(viewer, "admin.lore.order-refund",
                "%price%", TextUtil.formatNumber(refund)));
        lines.add(plugin.msg(viewer, "gui.lore.items-waiting",
                "%amount%", String.valueOf(order.getInventoryCount())));
        // Tam kimlik: /donutordersadmin removeorder ile kullanilabilsin diye.
        lines.add(plugin.msg(viewer, "admin.lore.order-id", "%id%", order.getId().toString()));
        return lines;
    }
}
