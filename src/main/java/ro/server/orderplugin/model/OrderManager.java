package ro.server.orderplugin.model;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import ro.server.orderplugin.OrderPlugin;
import ro.server.orderplugin.config.Settings;
import ro.server.orderplugin.economy.TaxService;
import ro.server.orderplugin.gui.GuiManager;
import ro.server.orderplugin.scheduler.SchedulerAdapter;
import ro.server.orderplugin.storage.MySQLStorage;
import ro.server.orderplugin.storage.SQLiteStorage;
import ro.server.orderplugin.sync.SyncMessage;
import ro.server.orderplugin.sync.SyncService;
import ro.server.orderplugin.util.TextUtil;

public class OrderManager {
    private final OrderPlugin plugin;
    private final ConcurrentHashMap<UUID, Order> ordersById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>> ordersByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Material, Set<UUID>> ordersByMaterial = new ConcurrentHashMap<>();
    private final Cache<String, List<Order>> activeOrdersCache = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.SECONDS)
        .maximumSize(1)
        .build();
    private final File dataFile;
    private final SchedulerAdapter scheduler;
    private FileConfiguration yamlConfig;

    private MySQLStorage mysqlStorage;
    private SQLiteStorage sqliteStorage;
    private boolean useSQL = false;

    /** Yazilmayi bekleyen degisiklik var mi (YAML modu). */
    private volatile boolean saveRequested = false;
    /** Gecikmeli yazma gorevi zaten kuyruklandi mi — ayni anda birden fazla olmasin. */
    private volatile boolean saveScheduled = false;

    public OrderManager(OrderPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.scheduler = plugin.getSchedulerAdapter();

        String storageType = plugin.getConfig().getString("storage.type", "MEMORY").toUpperCase();
        if (storageType.equals("MYSQL") || storageType.equals("MARIADB")) {
            this.mysqlStorage = new MySQLStorage(plugin, storageType);
            if (this.mysqlStorage.connect()) {
                this.useSQL = true;
                plugin.getLogger().info("[Storage] " + storageType + " depolama aktif.");
            } else {
                plugin.getLogger().severe("[Storage] MySQL baglantisi basarisiz! YAML'a geri donuluyor.");
                this.useSQL = false;
            }
        } else if (storageType.equals("SQLITE")) {
            this.sqliteStorage = new SQLiteStorage(plugin);
            if (this.sqliteStorage.connect()) {
                this.useSQL = true;
                plugin.getLogger().info("[Storage] SQLite depolama aktif.");
            } else {
                plugin.getLogger().severe("[Storage] SQLite baglantisi basarisiz! YAML'a geri donuluyor.");
                this.useSQL = false;
            }
        } else {
            plugin.getLogger().info("[Storage] YAML dosya depolama kullaniliyor (MEMORY modu).");
        }
    }

    // Storage delegation helpers
    private List<Order> dbLoadAll() {
        if (mysqlStorage != null) return mysqlStorage.loadAllOrders();
        if (sqliteStorage != null) return sqliteStorage.loadAllOrders();
        return new ArrayList<>();
    }

    private void dbSaveOrder(Order order) {
        if (mysqlStorage != null) mysqlStorage.saveOrder(order);
        else if (sqliteStorage != null) sqliteStorage.saveOrder(order);
    }

    private void dbSaveAll(List<Order> orders) {
        if (mysqlStorage != null) mysqlStorage.saveAllOrders(orders);
        else if (sqliteStorage != null) sqliteStorage.saveAllOrders(orders);
    }

    private void dbDeleteOrder(UUID id) {
        if (mysqlStorage != null) mysqlStorage.deleteOrder(id);
        else if (sqliteStorage != null) sqliteStorage.deleteOrder(id);
    }

    private void dbDeleteAll() {
        if (mysqlStorage != null) mysqlStorage.deleteAllOrders();
        else if (sqliteStorage != null) sqliteStorage.deleteAllOrders();
    }

    private void dbDisconnect() {
        if (mysqlStorage != null) mysqlStorage.disconnect();
        else if (sqliteStorage != null) sqliteStorage.disconnect();
    }

    private void addToIndex(Order order) {
        ordersById.put(order.getId(), order);
        ordersByPlayer.computeIfAbsent(order.getOwner(), k -> ConcurrentHashMap.newKeySet()).add(order.getId());
        ordersByMaterial.computeIfAbsent(order.getMaterial(), k -> ConcurrentHashMap.newKeySet()).add(order.getId());
        activeOrdersCache.invalidateAll();
    }

    private void removeFromIndex(Order order) {
        ordersById.remove(order.getId());
        removeFromSecondaryIndexes(order);
    }

    private void removeFromSecondaryIndexes(Order order) {
        ordersByPlayer.compute(order.getOwner(), (key, set) -> {
            if (set != null) {
                set.remove(order.getId());
                if (set.isEmpty()) return null;
            }
            return set;
        });
        ordersByMaterial.compute(order.getMaterial(), (key, set) -> {
            if (set != null) {
                set.remove(order.getId());
                if (set.isEmpty()) return null;
            }
            return set;
        });
        activeOrdersCache.invalidateAll();
    }

    /**
     * Siparisleri depolamadan yeniden okur (cross-server tazeleme).
     *
     * <p>Yalnizca SQL modunda anlamlidir: baska bir sunucu veritabanini
     * degistirdiginde bu sunucunun bellegi eskir. MEMORY/SQLITE modunda ag
     * zaten kapali oldugu icin hicbir sey yapilmaz.</p>
     */
    /** Cross-server yalnizca MySQL/MariaDB ile calisir; degilse null. */
    public MySQLStorage mysqlStorage() {
        return this.mysqlStorage;
    }

    public void reloadFromStorage() {
        if (!this.useSQL) return;
        loadOrders();
        plugin.getGuiManager().invalidateAllOrderCaches();
    }

    public void loadOrders() {
        if (this.useSQL) {
            this.ordersById.clear();
            this.ordersByPlayer.clear();
            this.ordersByMaterial.clear();
            this.activeOrdersCache.invalidateAll();
            for (Order order : this.dbLoadAll()) {
                addToIndex(order);
            }
            return;
        }

        if (!this.dataFile.exists()) {
            return;
        }
        this.yamlConfig = YamlConfiguration.loadConfiguration(this.dataFile);
        this.ordersById.clear();
        this.ordersByPlayer.clear();
        this.ordersByMaterial.clear();
        this.activeOrdersCache.invalidateAll();
        if (!this.yamlConfig.contains("orders")) {
            return;
        }
        ConfigurationSection ordersSection = this.yamlConfig.getConfigurationSection("orders");
        if (ordersSection == null) {
            return;
        }
        int count = 0;
        for (String key : ordersSection.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                ConfigurationSection orderSection = ordersSection.getConfigurationSection(key);
                if (orderSection == null) continue;
                UUID owner = UUID.fromString(orderSection.getString("owner"));
                Material material = Material.valueOf(orderSection.getString("item"));
                int needed = orderSection.getInt("needed");
                int filled = orderSection.getInt("filled");
                double price = orderSection.getDouble("price");
                long created = orderSection.getLong("created");
                long expiry = orderSection.getLong("expiry");
                Order order = new Order(id, owner, material, needed, filled, price, created, expiry);
                if (orderSection.contains("removedByAdmin")) {
                    order.setRemovedByAdmin(orderSection.getBoolean("removedByAdmin"));
                }
                if (orderSection.contains("potionType")) {
                    order.setPotionType(orderSection.getString("potionType"));
                }
                if (orderSection.contains("enchantmentType")) {
                    order.setEnchantmentType(orderSection.getString("enchantmentType"));
                }
                // 2.0'da bu alan yoktu; eksikse null kalir ve siparis eskisi gibi
                // materyal esitligine gore calisir.
                if (orderSection.contains("customId")) {
                    order.setCustomId(orderSection.getString("customId"));
                }
                ConfigurationSection invSection;
                if (orderSection.contains("inventory") && (invSection = orderSection.getConfigurationSection("inventory")) != null) {
                    for (String slot : invSection.getKeys(false)) {
                        ItemStack item = invSection.getItemStack(slot);
                        if (item == null) continue;
                        order.addItem(item);
                    }
                }
                addToIndex(order);
                ++count;
            } catch (Exception e) {
                this.plugin.getLogger().log(Level.WARNING, "Siparis yuklenirken hata, ID: " + key, e);
            }
        }
        this.plugin.getLogger().info(count + " siparis data.yml'den yuklendi.");
    }

    /**
     * Kaydi <b>talep eder</b>; hemen diske yazmaz.
     *
     * <p>Eskiden her siparis olusturma/iptal/teslim isleminde tum siparisler
     * (icindeki her esya dahil) ana is parcaciginda bastan YAML'a serilestirilip
     * dosyaya yaziliyordu. 500 siparislik bir sunucuda tek bir teslim tiklamasi
     * yuzlerce milisaniye surebiliyordu; ayni saniyede 10 oyuncu teslim ederse bu
     * 10 kez tekrarlaniyordu.</p>
     *
     * <p>Artik degisiklik isaretlenir ve kisa bir gecikmeyle <b>tek</b> yazma
     * yapilir. Yogun anlarda arka arkaya gelen 10 istek tek dosya yazmasina
     * doner.</p>
     */
    public void saveOrders() {
        if (this.useSQL) {
            this.dbSaveAll(new ArrayList<>(this.ordersById.values()));
            return;
        }
        requestYamlSave();
    }

    private void requestYamlSave() {
        saveRequested = true;
        if (saveScheduled) return;
        saveScheduled = true;

        long delay = plugin.settings() == null ? 40L : plugin.settings().saveDelayTicks();
        if (delay <= 0L) {                       // gecikme kapali: eski davranis
            saveScheduled = false;
            saveRequested = false;
            writeYamlNow();
            return;
        }
        scheduler.runGlobalLater(plugin, () -> {
            saveScheduled = false;
            if (!saveRequested) return;
            saveRequested = false;
            writeYamlNow();
        }, delay);
    }

    /**
     * Anlik goruntuyu ana is parcaciginda alir, diske yazmayi arka plana atar.
     *
     * <p>Esyalar <b>klonlanarak</b> alinir: serilestirme arka planda calisirken
     * bir oyuncu ayni siparise teslim yapabilir ve canli liste degisirse yazma
     * yarida bozulurdu.</p>
     */
    private void writeYamlNow() {
        YamlConfiguration snapshot = new YamlConfiguration();
        for (Order order : new ArrayList<>(this.ordersById.values())) {
            String path = "orders." + order.getId().toString();
            snapshot.set(path + ".owner", order.getOwner().toString());
            snapshot.set(path + ".item", order.getMaterial().name());
            snapshot.set(path + ".needed", order.getNeeded());
            snapshot.set(path + ".filled", order.getFilled());
            snapshot.set(path + ".price", order.getPricePerItem());
            snapshot.set(path + ".created", order.getCreated());
            snapshot.set(path + ".expiry", order.getExpiry());
            snapshot.set(path + ".removedByAdmin", order.isRemovedByAdmin());
            if (order.getPotionType() != null) {
                snapshot.set(path + ".potionType", order.getPotionType());
            }
            if (order.getEnchantmentType() != null) {
                snapshot.set(path + ".enchantmentType", order.getEnchantmentType());
            }
            if (order.getCustomId() != null) {
                snapshot.set(path + ".customId", order.getCustomId());
            }
            List<ItemStack> inv = order.getInventory();
            for (int i = 0; i < inv.size(); ++i) {
                ItemStack stack = inv.get(i);
                snapshot.set(path + ".inventory." + i, stack == null ? null : stack.clone());
            }
        }
        this.yamlConfig = snapshot;
        scheduler.runAsync(plugin, () -> writeSnapshot(snapshot));
    }

    /**
     * Dosyayi once gecici bir esine yazip sonra yerine tasir.
     *
     * <p>Dogrudan {@code data.yml} uzerine yazarken sunucu cokerse dosya yarim
     * kalir ve <b>tum siparisler</b> kaybolur. Gecici dosya + tasima ile en kotu
     * ihtimalde bir onceki kayit hayatta kalir.</p>
     */
    private synchronized void writeSnapshot(YamlConfiguration snapshot) {
        File temp = new File(this.dataFile.getParentFile(), this.dataFile.getName() + ".tmp");
        try {
            snapshot.save(temp);
            java.nio.file.Files.move(temp.toPath(), this.dataFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            this.plugin.getLogger().log(Level.SEVERE, "data.yml kaydedilemedi!", e);
            if (temp.exists() && !temp.delete()) {
                this.plugin.getLogger().warning("Gecici kayit dosyasi silinemedi: " + temp.getName());
            }
        }
    }

    /** Bekleyen kaydi hemen ve bu is parcaciginda yazar (kapanista). */
    public void flushPendingSave() {
        if (this.useSQL || !saveRequested) return;
        saveRequested = false;
        YamlConfiguration snapshot = new YamlConfiguration();
        for (Order order : new ArrayList<>(this.ordersById.values())) {
            String path = "orders." + order.getId().toString();
            snapshot.set(path + ".owner", order.getOwner().toString());
            snapshot.set(path + ".item", order.getMaterial().name());
            snapshot.set(path + ".needed", order.getNeeded());
            snapshot.set(path + ".filled", order.getFilled());
            snapshot.set(path + ".price", order.getPricePerItem());
            snapshot.set(path + ".created", order.getCreated());
            snapshot.set(path + ".expiry", order.getExpiry());
            snapshot.set(path + ".removedByAdmin", order.isRemovedByAdmin());
            if (order.getPotionType() != null) snapshot.set(path + ".potionType", order.getPotionType());
            if (order.getEnchantmentType() != null) snapshot.set(path + ".enchantmentType", order.getEnchantmentType());
            if (order.getCustomId() != null) snapshot.set(path + ".customId", order.getCustomId());
            List<ItemStack> inv = order.getInventory();
            for (int i = 0; i < inv.size(); ++i) {
                snapshot.set(path + ".inventory." + i, inv.get(i));
            }
        }
        writeSnapshot(snapshot);
    }

    public void shutdown() {
        // Kapanista gecikmeli yazma calisamaz (scheduler duruyor); bekleyen
        // degisiklik varsa burada, bu is parcaciginda yazilir.
        flushPendingSave();
        if (this.useSQL) {
            this.dbDisconnect();
        }
    }

    public boolean createOrder(Player player, Material material, int amount, double pricePerItem) {
        return this.createOrder(player, material, amount, pricePerItem, null, null);
    }

    public boolean createOrder(Player player, Material material, int amount, double pricePerItem, String potionType) {
        return this.createOrder(player, material, amount, pricePerItem, potionType, null);
    }

    /**
     * Siparis olusturur; tum kurallar (kara liste, miktar/fiyat sinirlari, rutbe
     * bazli aktif siparis limiti ve olusturma vergisi) burada uygulanir.
     *
     * <p>Kurallar GUI'de degil burada zorlanir: komutla ya da baska bir yoldan
     * gelen her siparis ayni suzgecten gecsin diye.</p>
     */
    public boolean createOrder(Player player, Material material, int amount, double pricePerItem, String potionType, String enchantmentType) {
        Settings settings = plugin.settings();

        if (settings.isBlacklisted(material)) {
            player.sendMessage(plugin.msg(player, "errors.item-blacklisted"));
            return false;
        }
        int minAmount = Math.max(1, settings.minAmount());
        if (amount < minAmount) {
            player.sendMessage(plugin.msg(player, "errors.min-amount-not-met", "%amount%", String.valueOf(minAmount)));
            return false;
        }
        // Seviye bonusu limite EKLENIR. Limit -1 (sinirsiz) ise bonus anlamsizdir
        // ve sinirsizlik bozulmamalidir.
        int maxAmount = settings.maxAmount();
        if (maxAmount > 0) maxAmount += plugin.levels().maxItemsBonus(player);
        if (maxAmount > 0 && amount > maxAmount) {
            player.sendMessage(plugin.msg(player, "errors.max-amount-exceeded",
                "%amount%", String.valueOf(maxAmount)));
            return false;
        }
        if (pricePerItem < settings.minPrice()) {
            player.sendMessage(plugin.msg(player, "errors.min-price-not-met",
                "%price%", TextUtil.formatNumber(settings.minPrice())));
            return false;
        }
        if (settings.maxPrice() > 0 && pricePerItem > settings.maxPrice()) {
            player.sendMessage(plugin.msg(player, "errors.max-price-exceeded",
                "%price%", TextUtil.formatNumber(settings.maxPrice())));
            return false;
        }

        int maxActive = settings.maxActiveOrders(player);
        if (maxActive >= 0) maxActive += plugin.levels().maxOrdersBonus(player);
        long activeCount = getOrdersByPlayer(player.getUniqueId()).stream()
            .filter(o -> !o.isComplete() && o.getExpiry() > System.currentTimeMillis())
            .count();
        if (maxActive >= 0 && activeCount >= maxActive) {
            player.sendMessage(plugin.msg(player, "errors.max-orders-reached", "%limit%", String.valueOf(maxActive)));
            return false;
        }

        double subtotal = (double) amount * pricePerItem;
        TaxService.TaxResult tax = plugin.tax().calculate(player, subtotal, "creation");

        EconomyResponse response = OrderPlugin.getEconomy().withdrawPlayer((OfflinePlayer) player, tax.total());
        if (!response.transactionSuccess()) {
            player.sendMessage(plugin.msg(player, "errors.not-enough-money",
                    "%total%", TextUtil.formatNumber(tax.total())));
            plugin.playEventSound(player, "no-money");
            return false;
        }
        // Para cekildikten SONRA yatirilir: cekme basarisiz olsaydi vergi hesabina
        // karsiligi olmayan para gecerdi.
        plugin.tax().deposit(tax.amount());

        // Izin OLUSTURMA aninda kontrol edilip siparise islenir: sonradan rutbe
        // kaybi siparisi geriye donuk etkilemez, oyuncunun cevrimici olmasi da
        // gerekmez (bkz. Order#expiry, orders.expire-bypass-permission).
        boolean neverExpires = player.hasPermission(settings.orderExpireBypassPermission());
        Order order = new Order(player.getUniqueId(), material, amount, pricePerItem, potionType, enchantmentType, neverExpires);
        addToIndex(order);
        if (this.useSQL) {
            this.scheduler.runAsync(plugin, () -> {
                this.dbSaveOrder(order);
            });
        } else {
            this.saveOrders();
        }

        String itemName = plugin.getGuiManager().getOrderDisplayName(player, order);
        String formattedAmount = TextUtil.formatNumber((double) amount);
        String message = plugin.msg(player, "success.order-created", "%amount%", formattedAmount, "%item%", itemName);
        player.sendMessage(message);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message));
        if (tax.charged()) {
            player.sendMessage(plugin.msg(player, "success.tax-charged",
                "%tax%", GuiManager.formatPercent(tax.rate()), "%amount%", TextUtil.formatNumber(tax.amount())));
            plugin.playEventSound(player, "tax-paid");
        }
        plugin.playEventSound(player, "order-created");
        plugin.sync().publish(SyncMessage.of(SyncService.ORDER_CREATED,
                plugin.sync().serverId(), order.getId().toString()));

        // Siparis TAMAMEN olusup para cekildikten SONRA duyurulur: yukaridaki
        // kontrollerden biri false donseydi hicbir duyuru yapilmayacakti, boylece
        // hayali (basarisiz) bir siparis asla sunucuya duyurulamaz.
        announceOrderCreated(player, itemName, amount, pricePerItem, subtotal);

        // Seviye sistemi kapaliysa bu cagrilarin hicbiri bir sey yapmaz.
        //
        // Varsayilan olarak siparis XP'si BURADA VERILMEZ: acip hemen iptal
        // ederek bedava XP toplamak mumkun olurdu (para tam iade edilir).
        // XP siparis gercekten dolunca odenir — bkz. AntiAbuse.
        if (plugin.levels().antiAbuse().awardCreationImmediately()
                && plugin.levels().antiAbuse().orderQualifies(amount, pricePerItem)) {
            plugin.levels().award(player, "create", 1d);
            plugin.levels().award(player, "create-money", subtotal);
        }
        return true;
    }

    /**
     * Yeni acilan siparisi tum sunucuya duyurur — orders.broadcast.enabled
     * kapaliysa (varsayilan) hicbir sey yapmaz. orders.broadcast.min-total
     * altindaki siparisler de duyurulmaz; 0 = hepsi.
     *
     * <p>LevelManager#announceLevelUp ile ayni sekilde: cevrimici oyuncular
     * tek tek gezilip her birine kendi dilinde plugin.msg ile mesaj gonderilir.</p>
     */
    private void announceOrderCreated(Player owner, String itemName, int amount, double pricePerItem, double total) {
        Settings settings = plugin.settings();
        if (!settings.orderBroadcastEnabled()) return;
        if (total < settings.orderBroadcastMinTotal()) return;

        String amountText = plugin.getGuiManager().formatOrderNumber(amount);
        String priceText = plugin.getGuiManager().formatOrderNumber(pricePerItem);
        String totalText = plugin.getGuiManager().formatOrderNumber(total);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(plugin.msg(online, "success.order-created-broadcast",
                    "%player%", owner.getName(), "%item%", itemName,
                    "%amount%", amountText, "%price%", priceText, "%total%", totalText));
        }
    }

    public boolean cancelOrder(Player player, Order order) {
        if (!order.getOwner().equals(player.getUniqueId())) {
            player.sendMessage(this.plugin.msg(player, "errors.not-owner"));
            return false;
        }
        if (order.isComplete()) {
            player.sendMessage(this.plugin.msg(player, "errors.already-complete"));
            return false;
        }
        // Atomic remove to prevent double-refund race with cleanupExpiredOrders
        if (ordersById.remove(order.getId()) == null) {
            player.sendMessage(this.plugin.msg(player, "errors.order-unavailable"));
            return false;
        }
        // Remove from secondary indexes
        removeFromSecondaryIndexes(order);

        // Kapatma ile doldurmayi ayni Order monitorunde dislar: eszamanli bir
        // fulfillOrder artik ya bu kapanistan once biter ya da hic biter. Iade,
        // kapanis anindaki anlik goruntuden hesaplanir — sonradan okumak yarisa acikti.
        int filledAtClose = order.closeAndGetFilled();
        if (filledAtClose < 0) {
            player.sendMessage(this.plugin.msg(player, "errors.order-unavailable"));
            return false;
        }
        double refund = (double) (order.getNeeded() - filledAtClose) * order.getPricePerItem();
        OrderPlugin.getEconomy().depositPlayer((OfflinePlayer) player, refund);
        for (ItemStack item : order.getInventory()) {
            if (item == null || item.getType() == Material.AIR) continue;
            java.util.HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            if (leftover.isEmpty()) continue;
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItem(player.getLocation().add(player.getLocation().getDirection().multiply(1.5)), drop);
            }
        }

        if (this.useSQL) {
            this.scheduler.runAsync(plugin, () -> {
                this.dbDeleteOrder(order.getId());
            });
        } else {
            this.saveOrders();
        }

        plugin.getGuiManager().invalidateOrderCache(order.getId());
        player.sendMessage(plugin.msg(player, "success.order-cancelled", "%price%", TextUtil.formatNumber(refund)));
        plugin.playSuccess(player);
        return true;
    }

    public void fulfillOrder(Player player, Order order, int amount, double payment, List<ItemStack> deliveredItems) {
        // Prevent self-fulfillment exploit
        if (order.getOwner().equals(player.getUniqueId())) {
            returnItemsToPlayer(player, deliveredItems);
            player.sendMessage(this.plugin.msg(player, "errors.cant-fulfill-own"));
            return;
        }
        // Validate order is still active (not expired, cancelled, or already removed)
        if (getOrderById(order.getId()) == null) {
            returnItemsToPlayer(player, deliveredItems);
            player.sendMessage(this.plugin.msg(player, "errors.order-unavailable"));
            return;
        }
        if (order.isComplete() || order.isRemovedByAdmin() || order.getExpiry() <= System.currentTimeMillis()) {
            returnItemsToPlayer(player, deliveredItems);
            player.sendMessage(this.plugin.msg(player, "errors.order-unavailable"));
            return;
        }

        // Use tryFillIfOpen return value to prevent overpay exploit; unlike tryFill
        // this also refuses a concurrently-cancelled/closed order (bkz. Order.closed).
        int actuallyFilled = order.tryFillIfOpen(amount);
        if (actuallyFilled <= 0) {
            returnItemsToPlayer(player, deliveredItems);
            player.sendMessage(this.plugin.msg(player, "errors.order-already-filled"));
            return;
        }

        // CROSS-SERVER: bellekteki tryFill yalnizca BU sunucuyu baglar. Ag acikken
        // ayni anda baska bir sunucudaki oyuncu da son slotu doldurabilir ve ikisi
        // de odeme alirdi. Kosullu tek UPDATE ile kazanan veritabaninda belirlenir.
        if (plugin.sync().enabled() && mysqlStorage != null) {
            int confirmed = mysqlStorage.tryFillAtomic(order.getId(), actuallyFilled);
            if (confirmed <= 0) {
                // Kaybettik: bellekteki artis geri alinir, esyalar iade edilir.
                order.setFilled(Math.max(0, order.getFilled() - actuallyFilled));
                returnItemsToPlayer(player, deliveredItems);
                player.sendMessage(this.plugin.msg(player, "errors.order-filled-elsewhere"));
                plugin.playError(player);
                reloadFromStorage();
                return;
            }
        }

        // Pay only for actually filled amount
        double gross = (double) actuallyFilled * order.getPricePerItem();
        // Teslim vergisi KAZANCTAN kesilir (olusturma vergisi gibi ustune eklenmez):
        // teslim eden oyuncudan ekstra para istenemez, elinde para olmayabilir.
        TaxService.TaxResult deliveryTax = plugin.tax().calculate(player, gross, "delivery");
        double actualPayment = Math.max(0d, gross - deliveryTax.amount());
        OrderPlugin.getEconomy().depositPlayer((OfflinePlayer) player, actualPayment);
        plugin.tax().deposit(deliveryTax.amount());
        if (deliveryTax.charged()) {
            player.sendMessage(plugin.msg(player, "success.tax-charged",
                "%tax%", GuiManager.formatPercent(deliveryTax.rate()),
                "%amount%", TextUtil.formatNumber(deliveryTax.amount())));
        }
        // Teslim XP'si partner gecmisinden gecer: ayni kisiyle sonsuz tekrar
        // eden ticaret kademeli olarak degersizlesir, alt hesap hic XP vermez.
        if (plugin.levels().antiAbuse().orderQualifies(order.getNeeded(), order.getPricePerItem())) {
            plugin.levels().awardDelivery(player, order.getOwner(), actuallyFilled);
        }

        if (deliveredItems != null && !deliveredItems.isEmpty()) {
            // Only store actuallyFilled worth of items, return excess to player
            int itemsToStore = actuallyFilled;
            List<ItemStack> excess = new ArrayList<>();
            for (ItemStack item : deliveredItems) {
                if (item == null) continue;
                if (itemsToStore <= 0) {
                    excess.add(item.clone());
                    continue;
                }
                int itemAmount = item.getAmount();
                if (itemAmount <= itemsToStore) {
                    order.addItem(item.clone());
                    itemsToStore -= itemAmount;
                } else {
                    ItemStack partial = item.clone();
                    partial.setAmount(itemsToStore);
                    order.addItem(partial);
                    ItemStack remainder = item.clone();
                    remainder.setAmount(itemAmount - itemsToStore);
                    excess.add(remainder);
                    itemsToStore = 0;
                }
            }
            returnItemsToPlayer(player, excess);
        } else {
            Material mat = order.getMaterial();
            for (int remaining = actuallyFilled; remaining > 0; ) {
                int stackSize = Math.min(remaining, mat.getMaxStackSize());
                ItemStack stack = new ItemStack(mat, stackSize);

                if (order.isPotion() && order.getPotionType() != null) {
                    try {
                        PotionMeta potionMeta = (PotionMeta) stack.getItemMeta();
                        PotionType potionType = PotionType.valueOf(order.getPotionType());
                        potionMeta.setBasePotionType(potionType);
                        stack.setItemMeta((ItemMeta) potionMeta);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[Siparis] Iksir metaverisi uygulanamadi, siparis " + order.getId() + ": " + e.getMessage());
                    }
                }

                if (order.isEnchantedBook() && order.getEnchantmentType() != null) {
                    try {
                        EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) stack.getItemMeta();
                        String[] parts = order.getEnchantmentType().split("_");
                        if (parts.length >= 2) {
                            String enchName = parts[0];
                            for (int i = 1; i < parts.length - 1; ++i) {
                                enchName = enchName + "_" + parts[i];
                            }
                            int level = Integer.parseInt(parts[parts.length - 1]);
                            Enchantment ench = Enchantment.getByName(enchName);
                            if (ench != null) {
                                bookMeta.addStoredEnchant(ench, level, true);
                            }
                        }
                        stack.setItemMeta((ItemMeta) bookMeta);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[Siparis] Buyu metaverisi uygulanamadi, siparis " + order.getId() + ": " + e.getMessage());
                    }
                } else if (order.getEnchantmentType() != null && !order.getEnchantmentType().isEmpty()) {
                    try {
                        ItemMeta meta = stack.getItemMeta();
                        String[] enchantments = order.getEnchantmentType().split(";");
                        for (String enchStr : enchantments) {
                            String[] enchParts = enchStr.split("_");
                            if (enchParts.length < 2) continue;
                            String enchKey = enchParts[0];
                            for (int i = 1; i < enchParts.length - 1; ++i) {
                                enchKey = enchKey + "_" + enchParts[i];
                            }
                            int enchLevel = Integer.parseInt(enchParts[enchParts.length - 1]);
                            Enchantment ench = (Enchantment) Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchKey.toLowerCase()));
                            if (ench == null) continue;
                            meta.addEnchant(ench, enchLevel, true);
                        }
                        stack.setItemMeta(meta);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[Siparis] Buyu metaverisi uygulanamadi, siparis " + order.getId() + ": " + e.getMessage());
                    }
                }

                order.addItem(stack);
                remaining -= stackSize;
            }
        }

        if (this.useSQL) {
            this.scheduler.runAsync(plugin, () -> {
                this.dbSaveOrder(order);
            });
        } else {
            this.saveOrders();
        }

        // Her mesaj alicisinin kendi dilinde: satici kendi dilinde, siparis sahibi
        // kendi dilinde okur; esya adi da her biri icin ayri cevrilir.
        String amountText = TextUtil.formatNumber((double) actuallyFilled);
        String sellerItemName = plugin.getGuiManager().getOrderDisplayName(player, order);
        String fulfilled = plugin.msg(player, "success.order-fulfilled",
            "%amount%", amountText, "%item%", sellerItemName, "%price%", TextUtil.formatNumber(actualPayment));
        player.sendMessage(fulfilled);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
            new TextComponent(TextComponent.fromLegacyText(fulfilled)));

        Player orderOwner = Bukkit.getPlayer(order.getOwner());
        if (orderOwner != null && orderOwner.isOnline()) {
            String ownerItemName = plugin.getGuiManager().getOrderDisplayName(orderOwner, order);
            orderOwner.sendMessage(plugin.msg(orderOwner, "success.order-received",
                "%seller%", player.getName(), "%amount%", amountText, "%item%", ownerItemName));
            if (order.isComplete()) {
                orderOwner.sendMessage(plugin.msg(orderOwner, "success.order-complete", "%item%", ownerItemName));
                plugin.playEventSound(orderOwner, "order-completed");
                awardCompletion(orderOwner, order, player.getUniqueId());
            }
        }
        plugin.playEventSound(player, "order-delivered");
        plugin.getGuiManager().invalidateOrderCache(order.getId());
        plugin.sync().publish(SyncMessage.of(SyncService.ORDER_UPDATED,
                plugin.sync().serverId(), order.getId().toString()));
    }

    /**
     * Siparis sahibine tamamlanma XP'si verir.
     *
     * <p>Varsayilan ayarda <b>acilis XP'si de burada</b> odenir: siparis acmak
     * tek basina bedava XP olmamali, cunku acilan siparis hemen iptal edilip
     * parasi geri alinabilir. Sonucu odullendirmek niyeti odullendirmekten
     * guvenlidir ve hicbir kayit tutmayi gerektirmez — sunucu yeniden baslasa
     * bile acik geri gelmez.</p>
     *
     * <p>Ayrica dolduran kisi partner suzgecinden gecirilir: kendi siparisini
     * alt hesabiyla dolduran biri tamamlanma XP'si de alamaz.</p>
     */
    private void awardCompletion(Player owner, Order order, UUID filledBy) {
        var levels = plugin.levels();
        var guard = levels.antiAbuse();
        if (!levels.enabled()) return;
        if (!guard.orderQualifies(order.getNeeded(), order.getPricePerItem())) return;
        // Siparisi kim doldurduysa ona karsi partner/IP kontrolu uygulanir.
        if (guard.deliveryMultiplier(owner, filledBy) <= 0d) return;

        levels.award(owner, "complete", 1d);
        if (guard.awardCreationOnComplete()) {
            levels.award(owner, "create", 1d);
            levels.award(owner, "create-money", order.getNeeded() * order.getPricePerItem());
        }
    }

    /**
     * Yoneticinin bir siparisi kaldirmasi: kalan tutar sahibine iade edilir.
     *
     * <p>Hem {@code /donutordersadmin removeorder} hem de yonetici paneli bunu
     * cagirir; iki yerde ayri mantik olsaydi biri duzeltilip digeri unutulurdu.</p>
     *
     * <p>Siparise teslim edilmis esya varsa kayit <b>silinmez</b>, tamamlanmis
     * isaretlenir: sahibi esyalarini yine de toplayabilmeli. Temizlik gorevi
     * tamamlanmis siparisi tekrar iade etmez.</p>
     *
     * @param admin komutu/paneli kullanan kisi (mesajlar onun dilinde)
     * @return iade edilen tutar
     */
    public double adminRemoveOrder(CommandSender admin, Order order) {
        // Kapatma ile doldurmayi ayni Order monitorunde dislar: eszamanli bir
        // cancelOrder/fulfillOrder ile cift odeme olusamaz. Iade, kapanis
        // anindaki anlik goruntuden hesaplanir.
        int filledAtClose = order.closeAndGetFilled();
        if (filledAtClose < 0) {
            // Baska bir yol (ör. oyuncunun kendi iptali) siparisi ayni anda
            // zaten kapatti; tekrar iade edilmez.
            admin.sendMessage(plugin.msg(admin, "errors.order-unavailable"));
            return 0d;
        }
        OfflinePlayer owner = Bukkit.getOfflinePlayer(order.getOwner());
        double refund = (double) (order.getNeeded() - filledAtClose) * order.getPricePerItem();
        String adminItemName = plugin.getGuiManager()
                .getOrderDisplayName(plugin.getLanguage().resolve(admin), order);

        OrderPlugin.getEconomy().depositPlayer(owner, refund);
        order.setRemovedByAdmin(true);
        int collected = order.getInventoryCount();
        if (order.hasItems()) {
            order.setFilled(order.getNeeded());
            saveOrders();
        } else {
            removeOrder(order);
        }

        String amountText = TextUtil.formatNumber((double) order.getNeeded());
        String refundText = TextUtil.formatNumber(refund);
        admin.sendMessage(collected > 0
                ? plugin.msg(admin, "admin.order-removed-with-items", "%amount%", amountText,
                    "%item%", adminItemName, "%price%", refundText, "%collected%", String.valueOf(collected))
                : plugin.msg(admin, "admin.order-removed", "%amount%", amountText,
                    "%item%", adminItemName, "%price%", refundText));

        // Bildirim siparis sahibinin dilinde; cevrimdisiysa girise kadar bekletilir.
        Player online = owner.getPlayer();
        String ownerItemName = online != null
                ? plugin.getGuiManager().getOrderDisplayName(online, order) : adminItemName;
        String notice = plugin.msg(online, "admin.order-removed-player", "%amount%", amountText,
                "%item%", ownerItemName, "%price%", refundText);
        if (collected > 0) notice = notice + plugin.msg(online, "admin.order-removed-collect");

        if (online != null && online.isOnline()) {
            online.sendMessage(notice);
        } else {
            plugin.getPendingMessageManager().addMessage(order.getOwner(), notice);
        }
        plugin.getGuiManager().invalidateOrderCache(order.getId());
        plugin.sync().publish(SyncMessage.of(SyncService.ORDER_REMOVED,
                plugin.sync().serverId(), order.getId().toString()));
        return refund;
    }

    private void returnItemsToPlayer(Player player, List<ItemStack> items) {
        if (items == null || items.isEmpty()) return;
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) continue;
            java.util.HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack drop : leftover.values()) {
                player.getWorld().dropItem(player.getLocation(), drop);
            }
        }
    }

    public void removeOrder(Order order) {
        removeFromIndex(order);
        if (this.useSQL) {
            this.scheduler.runAsync(plugin, () -> {
                this.dbDeleteOrder(order.getId());
            });
        } else {
            this.saveOrders();
        }
    }

    public int purgeAllOrders() {
        int count = this.ordersById.size();
        // Refund all active orders before purging. closeAndGetFilled() kapatma
        // ile doldurmayi dislar; eszamanli bir cancelOrder/fulfillOrder ile
        // cift odeme olusamaz. -1 donerse siparis baska bir yolla zaten
        // kapatilmis demektir, tekrar iade edilmez.
        for (Order order : new ArrayList<>(this.ordersById.values())) {
            int filledAtClose = order.closeAndGetFilled();
            if (filledAtClose < 0) continue;
            double refund = (double) (order.getNeeded() - filledAtClose) * order.getPricePerItem();
            if (refund > 0) {
                OfflinePlayer owner = Bukkit.getOfflinePlayer(order.getOwner());
                OrderPlugin.getEconomy().depositPlayer(owner, refund);
            }
        }
        this.ordersById.clear();
        this.ordersByPlayer.clear();
        this.ordersByMaterial.clear();
        this.activeOrdersCache.invalidateAll();
        if (this.useSQL) {
            this.dbDeleteAll();
        }
        // Bekleyen yazma iptal edilmezse silinen dosya saniyeler icinde geri gelirdi.
        this.saveRequested = false;
        if (this.dataFile.exists() && !this.dataFile.delete()) {
            this.plugin.getLogger().warning("data.yml silinemedi.");
        }
        return count;
    }

    public void cleanupExpiredOrders() {
        long now = System.currentTimeMillis();
        List<Order> expired = ordersById.values().stream()
            .filter(order -> order.getExpiry() <= now && !order.isComplete() && !order.isRemovedByAdmin())
            .collect(Collectors.toList());

        List<UUID> deletedIds = new ArrayList<>();
        List<Order> keptOrders = new ArrayList<>();
        for (Order order : expired) {
            // Kapatma ile doldurmayi ayni Order monitorunde dislar: eszamanli
            // bir cancelOrder/adminRemoveOrder/fulfillOrder ile cift odeme
            // olusamaz. -1 donerse siparis baska bir yolla zaten kapatilmis
            // demektir, bu turda hic islenmez.
            int filledAtClose = order.closeAndGetFilled();
            if (filledAtClose < 0) continue;
            double refund = (double) (order.getNeeded() - filledAtClose) * order.getPricePerItem();
            if (refund > 0) {
                OfflinePlayer owner = Bukkit.getOfflinePlayer(order.getOwner());
                OrderPlugin.getEconomy().depositPlayer(owner, refund);
            }

            // Alici cevrimdisi oldugu icin metin sunucunun varsayilan dilinde hazirlanir;
            // oyuncu-bazli dil yalnizca canli bir alici varken cozulebilir.
            String lang = plugin.getLanguage().serverDefault();
            String itemName = plugin.getGuiManager().getOrderDisplayName(lang, order);

            if (order.hasItems()) {
                // Mark as complete to prevent re-processing on next cleanup cycle
                order.setFilled(order.getNeeded());
                keptOrders.add(order);
                plugin.getPendingMessageManager().addMessage(order.getOwner(),
                    plugin.msg(null, "delivery.expired-with-items",
                        "%item%", itemName, "%price%", TextUtil.formatNumber(refund)));
            } else {
                // No items to collect — safe to delete
                if (ordersById.remove(order.getId()) == null) continue;
                removeFromSecondaryIndexes(order);
                deletedIds.add(order.getId());
                if (refund > 0) {
                    plugin.getPendingMessageManager().addMessage(order.getOwner(),
                        plugin.msg(null, "delivery.expired",
                            "%item%", itemName, "%price%", TextUtil.formatNumber(refund)));
                }
            }
        }

        // Tamamlanmis ve icinde esya kalmamis siparisler sonsuza dek durmasin:
        // orders.completed-retention-hours kadar bekleyip silinirler.
        long retention = plugin.settings().completedRetentionMillis();
        for (Order order : ordersById.values()) {
            if (!order.isComplete() || order.hasItems()) continue;
            // orders.bypass.expire ile acilmis siparislerde expiry Long.MAX_VALUE'dir;
            // dogrudan toplarsak tasip negatife donerdi ve siparis erkenden silinirdi.
            long retentionDeadline = order.getExpiry() > Long.MAX_VALUE - retention
                    ? Long.MAX_VALUE : order.getExpiry() + retention;
            if (now < retentionDeadline) continue;
            if (ordersById.remove(order.getId()) == null) continue;
            removeFromSecondaryIndexes(order);
            deletedIds.add(order.getId());
        }

        boolean changed = !deletedIds.isEmpty() || !keptOrders.isEmpty();
        if (!deletedIds.isEmpty()) {
            if (useSQL) {
                scheduler.runAsync(plugin, () -> {
                    for (UUID id : deletedIds) {
                        dbDeleteOrder(id);
                    }
                });
            }
            plugin.getLogger().info("[Siparis] " + deletedIds.size() + " suresi dolmus siparis temizlendi.");
        }
        if (!keptOrders.isEmpty()) {
            if (useSQL) {
                scheduler.runAsync(plugin, () -> {
                    for (Order kept : keptOrders) {
                        dbSaveOrder(kept);
                    }
                });
            }
            plugin.getLogger().info("[Siparis] " + keptOrders.size() + " suresi dolmus siparis esya toplama icin korundu.");
        }
        if (changed && !useSQL) {
            saveOrders();
        }
    }

    public List<Order> getActiveOrders() {
        return activeOrdersCache.get("active", key -> {
            long now = System.currentTimeMillis();
            return ordersById.values().stream()
                .filter(order -> order.getExpiry() > now)
                .collect(Collectors.toList());
        });
    }

    public List<Order> getOrdersByPlayer(UUID playerId) {
        Set<UUID> ids = ordersByPlayer.getOrDefault(playerId, Collections.emptySet());
        return ids.stream().map(ordersById::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public List<Order> getOrdersByMaterial(Material material) {
        Set<UUID> ids = ordersByMaterial.getOrDefault(material, Collections.emptySet());
        return ids.stream().map(ordersById::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public Order getOrderById(UUID orderId) {
        return ordersById.get(orderId);
    }
}
