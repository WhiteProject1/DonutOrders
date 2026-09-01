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

    /** Is there a change waiting to be written (YAML mode)? */
    private volatile boolean saveRequested = false;
    /** Is a delayed save task already queued — prevents more than one at a time. */
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
     * Re-reads orders from storage (cross-server refresh).
     *
     * <p>Only meaningful in SQL mode: this server's memory goes stale when
     * another server modifies the database. In MEMORY/SQLITE mode networking
     * is already off, so this does nothing.</p>
     */
    /** Cross-server only works with MySQL/MariaDB; null otherwise. */
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
                // This field didn't exist in 2.0; if missing it stays null and the
                // order keeps working based on material equality like before.
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
     * <b>Requests</b> a save; does not write to disk immediately.
     *
     * <p>Previously, every order creation/cancellation/delivery would
     * re-serialize ALL orders (including every item inside them) to YAML on
     * the main thread from scratch and write them to disk. On a server with
     * 500 orders a single delivery click could take hundreds of milliseconds;
     * if 10 players delivered in the same second, that happened 10 times.</p>
     *
     * <p>Now the change is just flagged and a <b>single</b> write happens
     * after a short delay. During busy periods, 10 back-to-back requests
     * collapse into one file write.</p>
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
        if (delay <= 0L) {                       // delay disabled: old behavior
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
     * Takes the snapshot on the main thread, offloads the disk write to the
     * background.
     *
     * <p>Items are taken <b>cloned</b>: while serialization runs in the
     * background, a player could deliver to the same order, and if the live
     * list changed mid-write, the write would end up corrupted.</p>
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
     * Writes the file to a temporary copy first, then moves it into place.
     *
     * <p>If the server crashes while writing directly to {@code data.yml},
     * the file is left half-written and <b>all orders</b> are lost. With a
     * temp file + move, the previous save survives in the worst case.</p>
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

    /** Writes a pending save immediately, on this thread (on shutdown). */
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
        // The delayed write can't run on shutdown (the scheduler is stopping);
        // if a change is pending, it's written here, on this thread.
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
     * Creates an order; all rules (blacklist, amount/price limits,
     * rank-based active-order limit, and creation tax) are enforced here.
     *
     * <p>Rules are enforced here, not in the GUI, so that every order —
     * whether it comes from a command or any other path — goes through the
     * same filter.</p>
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
        // The level bonus is ADDED to the limit. If the limit is -1 (unlimited)
        // the bonus is meaningless and unlimited must stay unlimited.
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
        // Deposited AFTER the withdrawal succeeds: if the withdrawal had failed,
        // the tax account would receive money with nothing backing it.
        plugin.tax().deposit(tax.amount());

        // The permission is checked at CREATION time and baked into the order:
        // losing the rank later doesn't retroactively affect the order, and the
        // player doesn't need to be online either (see Order#expiry,
        // orders.expire-bypass-permission).
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

        // Announced only AFTER the order is FULLY created and the money is
        // withdrawn: if any of the checks above had returned false, no
        // announcement would happen, so a phantom (failed) order can never be
        // announced to the server.
        announceOrderCreated(player, itemName, amount, pricePerItem, subtotal);

        // If the level system is disabled, none of these calls do anything.
        //
        // By default, order XP is NOT awarded HERE: opening and immediately
        // cancelling would let players farm free XP (the money is fully
        // refunded). XP is paid only when the order actually gets filled —
        // see AntiAbuse.
        if (plugin.levels().antiAbuse().awardCreationImmediately()
                && plugin.levels().antiAbuse().orderQualifies(amount, pricePerItem)) {
            plugin.levels().award(player, "create", 1d);
            plugin.levels().award(player, "create-money", subtotal);
        }
        return true;
    }

    /**
     * Announces a newly opened order to the whole server — does nothing if
     * orders.broadcast.enabled is off (default). Orders below
     * orders.broadcast.min-total are also not announced; 0 = announce all.
     *
     * <p>Same approach as LevelManager#announceLevelUp: online players are
     * iterated one by one and each is sent the message via plugin.msg in
     * their own language.</p>
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

        // Closing excludes filling within the same Order monitor: a concurrent
        // fulfillOrder now either finishes before this close or never finishes
        // at all. The refund is computed from the snapshot at close time —
        // reading it afterward would be a race.
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
        // this also refuses a concurrently-cancelled/closed order (see Order.closed).
        int actuallyFilled = order.tryFillIfOpen(amount);
        if (actuallyFilled <= 0) {
            returnItemsToPlayer(player, deliveredItems);
            player.sendMessage(this.plugin.msg(player, "errors.order-already-filled"));
            return;
        }

        // CROSS-SERVER: the in-memory tryFill only binds THIS server. With
        // networking on, a player on another server could fill the last slot
        // at the same time, and both would get paid. A single conditional
        // UPDATE decides the winner in the database.
        if (plugin.sync().enabled() && mysqlStorage != null) {
            int confirmed = mysqlStorage.tryFillAtomic(order.getId(), actuallyFilled);
            if (confirmed <= 0) {
                // We lost: the in-memory increment is rolled back and items are refunded.
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
        // Delivery tax is deducted FROM the earnings (unlike creation tax, it's
        // not added on top): the delivering player can't be charged extra —
        // they may not have the money on hand.
        TaxService.TaxResult deliveryTax = plugin.tax().calculate(player, gross, "delivery");
        double actualPayment = Math.max(0d, gross - deliveryTax.amount());
        OrderPlugin.getEconomy().depositPlayer((OfflinePlayer) player, actualPayment);
        plugin.tax().deposit(deliveryTax.amount());
        if (deliveryTax.charged()) {
            player.sendMessage(plugin.msg(player, "success.tax-charged",
                "%tax%", GuiManager.formatPercent(deliveryTax.rate()),
                "%amount%", TextUtil.formatNumber(deliveryTax.amount())));
        }
        // Delivery XP goes through the partner-history filter: endlessly
        // repeated trading with the same person gets progressively devalued,
        // and an alt account gives no XP at all.
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

        // Each recipient gets their own language: the seller reads it in their
        // own language, the order owner in theirs; the item name is also
        // translated separately for each.
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
     * Awards completion XP to the order owner.
     *
     * <p>Under the default setting, <b>the creation XP is also paid here</b>:
     * opening an order shouldn't be free XP by itself, because an open order
     * can be cancelled immediately and the money refunded. Rewarding the
     * outcome is safer than rewarding the intent, and it requires no
     * bookkeeping — an open order doesn't come back even if the server
     * restarts.</p>
     *
     * <p>The person who filled it is also run through the partner filter:
     * someone who fills their own order with an alt account doesn't get
     * completion XP either.</p>
     */
    private void awardCompletion(Player owner, Order order, UUID filledBy) {
        var levels = plugin.levels();
        var guard = levels.antiAbuse();
        if (!levels.enabled()) return;
        if (!guard.orderQualifies(order.getNeeded(), order.getPricePerItem())) return;
        // The partner/IP check is applied against whoever filled the order.
        if (guard.deliveryMultiplier(owner, filledBy) <= 0d) return;

        levels.award(owner, "complete", 1d);
        if (guard.awardCreationOnComplete()) {
            levels.award(owner, "create", 1d);
            levels.award(owner, "create-money", order.getNeeded() * order.getPricePerItem());
        }
    }

    /**
     * An admin removing an order: the remaining amount is refunded to the owner.
     *
     * <p>Called by both {@code /donutordersadmin removeorder} and the admin
     * panel; if the two had separate logic, one would get fixed and the other
     * would be forgotten.</p>
     *
     * <p>If the order has delivered items, the record is <b>not deleted</b> —
     * it's marked complete instead: the owner should still be able to collect
     * their items. The cleanup task never refunds a completed order again.</p>
     *
     * @param admin the person using the command/panel (messages are in their language)
     * @return the refunded amount
     */
    public double adminRemoveOrder(CommandSender admin, Order order) {
        // Closing excludes filling within the same Order monitor: a concurrent
        // cancelOrder/fulfillOrder can't produce a double payout. The refund
        // is computed from the snapshot at close time.
        int filledAtClose = order.closeAndGetFilled();
        if (filledAtClose < 0) {
            // Some other path (e.g. the player's own cancellation) has already
            // closed the order in the meantime; it isn't refunded again.
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

        // The notification is in the order owner's language; if offline, it's
        // held until they log in.
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
        // Refund all active orders before purging. closeAndGetFilled() excludes
        // filling from closing; a concurrent cancelOrder/fulfillOrder can't
        // produce a double payout. A -1 return means the order was already
        // closed through some other path, so it isn't refunded again.
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
        // If the pending write isn't cancelled, the deleted file would come back within seconds.
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
            // Closing excludes filling within the same Order monitor: a
            // concurrent cancelOrder/adminRemoveOrder/fulfillOrder can't
            // produce a double payout. A -1 return means the order was
            // already closed through some other path, so it's skipped
            // entirely this round.
            int filledAtClose = order.closeAndGetFilled();
            if (filledAtClose < 0) continue;
            double refund = (double) (order.getNeeded() - filledAtClose) * order.getPricePerItem();
            if (refund > 0) {
                OfflinePlayer owner = Bukkit.getOfflinePlayer(order.getOwner());
                OrderPlugin.getEconomy().depositPlayer(owner, refund);
            }

            // The recipient is offline, so the text is prepared in the server's
            // default language; a per-player language can only be resolved
            // when there's a live recipient.
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

        // Completed orders with no items left shouldn't stick around forever:
        // they wait orders.completed-retention-hours and then get deleted.
        long retention = plugin.settings().completedRetentionMillis();
        for (Order order : ordersById.values()) {
            if (!order.isComplete() || order.hasItems()) continue;
            // Orders opened via orders.bypass.expire have expiry = Long.MAX_VALUE;
            // adding directly would overflow into negative and delete the order early.
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
