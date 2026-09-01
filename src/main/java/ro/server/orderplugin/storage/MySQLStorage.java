package ro.server.orderplugin.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import ro.server.orderplugin.model.Order;
import ro.server.orderplugin.OrderPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

public class MySQLStorage {

    private final OrderPlugin plugin;
    private final Logger logger;
    private HikariDataSource dataSource;
    private final String host, database, username, password, tablePrefix;
    private final int port;
    private final String storageType;

    public MySQLStorage(OrderPlugin plugin, String storageType) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.storageType = storageType;
        String configPath = plugin.getConfig().contains("storage.database.host") ? "storage.database" : "storage.mysql";
        this.host = plugin.getConfig().getString(configPath + ".host", "localhost");
        this.port = plugin.getConfig().getInt(configPath + ".port", 3306);
        this.database = plugin.getConfig().getString(configPath + ".database", "minecraft");
        this.username = plugin.getConfig().getString(configPath + ".username", "root");
        this.password = plugin.getConfig().getString(configPath + ".password", "");
        this.tablePrefix = plugin.getConfig().getString(configPath + ".table-prefix", "donutorders_");
        if (!this.tablePrefix.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid table prefix: " + this.tablePrefix + ". Only alphanumeric characters and underscores allowed.");
        }
    }

    public boolean connect() {
        try {
            HikariConfig config = new HikariConfig();
            String jdbcPrefix = storageType.equals("MARIADB") ? "jdbc:mariadb://" : "jdbc:mysql://";
            config.setJdbcUrl(jdbcPrefix + host + ":" + port + "/" + database + "?characterEncoding=utf8mb4");
            config.setUsername(username);
            config.setPassword(password);

            if (storageType.equals("MARIADB")) {
                config.setDriverClassName("org.mariadb.jdbc.Driver");
            }

            // Pool settings from config
            String configPath = plugin.getConfig().contains("storage.database.host") ? "storage.database" : "storage.mysql";
            config.setMaximumPoolSize(plugin.getConfig().getInt(configPath + ".pool.maximum-pool-size", 10));
            config.setMinimumIdle(plugin.getConfig().getInt(configPath + ".pool.minimum-idle", 2));
            config.setConnectionTimeout(plugin.getConfig().getLong(configPath + ".pool.connection-timeout", 30000));
            config.setIdleTimeout(plugin.getConfig().getLong(configPath + ".pool.idle-timeout", 600000));
            config.setMaxLifetime(plugin.getConfig().getLong(configPath + ".pool.max-lifetime", 1800000));
            config.setPoolName("DonutOrders-Pool");
            config.setLeakDetectionThreshold(60000);

            // Performance settings
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");

            this.dataSource = new HikariDataSource(config);
            logger.info("[Database] HikariCP pool started (" + storageType + " -> " + host + ":" + port + "/" + database + ")");
            createTables();
            return true;
        } catch (Exception e) {
            logger.severe("[Database] Connection pool failed: " + e.getMessage());
            return false;
        }
    }

    /** Havuzdan bir baglanti. Cagiran kapatmakla yukumludur (try-with-resources). */
    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Tablo oneki — sync sorgulari da ayni oneki kullanir. */
    public String prefix() {
        return tablePrefix;
    }

    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("[Database] HikariCP pool closed.");
        }
    }

    private void createTables() throws SQLException {
        String ordersTable = "CREATE TABLE IF NOT EXISTS " + tablePrefix + "orders ("
                + "id VARCHAR(36) PRIMARY KEY,"
                + "owner VARCHAR(36) NOT NULL,"
                + "item VARCHAR(100) NOT NULL,"
                + "needed INT NOT NULL,"
                + "filled INT DEFAULT 0,"
                + "price DOUBLE NOT NULL,"
                + "created BIGINT NOT NULL,"
                + "expiry BIGINT NOT NULL,"
                + "removed_by_admin TINYINT DEFAULT 0,"
                + "potion_type VARCHAR(100) DEFAULT NULL,"
                + "enchantment_type VARCHAR(500) DEFAULT NULL,"
                + "inventory LONGBLOB DEFAULT NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

        // Sunucular arasi olay kuyrugu. Yalnizca network.enabled ise kullanilir
        // ama tablo her zaman olusturulur: sunucu sahibi ozelligi actiginda
        // yeniden baslatmadan calissin.
        String syncTable = "CREATE TABLE IF NOT EXISTS " + tablePrefix + "sync_events ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "type VARCHAR(32) NOT NULL,"
                + "server_id VARCHAR(64) NOT NULL,"
                + "order_id VARCHAR(36) DEFAULT NULL,"
                + "player_id VARCHAR(36) DEFAULT NULL,"
                + "payload TEXT DEFAULT NULL,"
                + "created_at BIGINT NOT NULL,"
                + "INDEX idx_created (created_at),"
                + "INDEX idx_server (server_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

        String serversTable = "CREATE TABLE IF NOT EXISTS " + tablePrefix + "servers ("
                + "server_id VARCHAR(64) PRIMARY KEY,"
                + "last_seen BIGINT NOT NULL,"
                + "version VARCHAR(32) DEFAULT NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(ordersTable);
            stmt.executeUpdate(syncTable);
            stmt.executeUpdate(serversTable);
        }
        addColumnIfMissing("custom_id", "VARCHAR(128) DEFAULT NULL");
        addColumnIfMissing("server_id", "VARCHAR(64) DEFAULT NULL");
        logger.info("[Database] Tables ready.");
    }

    /**
     * Var olan {@code orders} tablosuna eksik kolonu ekler.
     *
     * <p>{@code ALTER TABLE} kosulsuz calistirilamaz: kolon zaten varsa hata
     * verir ve baslangici bozar. Bu yuzden once metaveriden sorulur. Eski
     * kayitlardaki degerler NULL kalir ve eskisi gibi calisir.</p>
     */
    private void addColumnIfMissing(String column, String definition) {
        try (Connection conn = dataSource.getConnection()) {
            try (ResultSet rs = conn.getMetaData().getColumns(
                    conn.getCatalog(), null, tablePrefix + "orders", column)) {
                if (rs.next()) return;
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("ALTER TABLE " + tablePrefix + "orders ADD COLUMN "
                        + column + " " + definition);
                logger.info("[Database] '" + column + "' kolonu eklendi (surum yukseltmesi).");
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "[Database] '" + column + "' kolonu eklenemedi", e);
        }
    }

    /**
     * Siparisi ATOMIK olarak doldurur.
     *
     * <p>Cross-server'in en kritik noktasi. Doldurma yalnizca bellekte
     * yapilsaydi iki sunucudaki iki oyuncu ayni anda son slotu doldurup
     * <b>ikisi de odeme alirdi</b>. Tek bir kosullu UPDATE bunu engeller:
     * kosul veritabaninda degerlendirilir, kazanan tektir.</p>
     *
     * @return gercekten doldurulan miktar; 0 ise siparis bu arada dolmustur
     */
    public int tryFillAtomic(UUID orderId, int amount) {
        String sql = "UPDATE " + tablePrefix + "orders SET filled = filled + ? "
                + "WHERE id = ? AND filled + ? <= needed";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setString(2, orderId.toString());
            ps.setInt(3, amount);
            return ps.executeUpdate() > 0 ? amount : 0;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Atomik doldurma basarisiz: " + orderId, e);
            // Veritabani erisilemiyorsa doldurma YAPILMAZ. Hata durumunda
            // "olmus say" demek cift odemeye kapi acardi.
            return 0;
        }
    }

    public List<Order> loadAllOrders() {
        List<Order> orders = new ArrayList<>();
        String sql = "SELECT * FROM " + tablePrefix + "orders";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                try {
                    UUID id = UUID.fromString(rs.getString("id"));
                    UUID owner = UUID.fromString(rs.getString("owner"));
                    Material material = Material.valueOf(rs.getString("item"));
                    int needed = rs.getInt("needed");
                    int filled = rs.getInt("filled");
                    double price = rs.getDouble("price");
                    long created = rs.getLong("created");
                    long expiry = rs.getLong("expiry");

                    Order order = new Order(id, owner, material, needed, filled, price, created, expiry);
                    order.setRemovedByAdmin(rs.getBoolean("removed_by_admin"));

                    String potionType = rs.getString("potion_type");
                    if (potionType != null) order.setPotionType(potionType);

                    String enchType = rs.getString("enchantment_type");
                    if (enchType != null) order.setEnchantmentType(enchType);

                    byte[] invBytes = rs.getBytes("inventory");
                    if (invBytes != null && invBytes.length > 0) {
                        List<ItemStack> items = deserializeInventory(invBytes);
                        for (ItemStack item : items) {
                            order.addItem(item);
                        }
                    }

                    // Recovery: if filled > 0 but inventory BLOB was lost (NULL), reconstruct items from order metadata
                    if (filled > 0 && order.getInventoryCount() == 0) {
                        try {
                            reconstructInventory(order, material, filled);
                            logger.warning("[Database] Recovered " + filled + " items for order " + id + " (inventory data was previously lost)");
                        } catch (Exception recoveryEx) {
                            logger.log(Level.WARNING, "[Database] Failed to reconstruct inventory for order " + id + " - order will load without items", recoveryEx);
                        }
                    }

                    orders.add(order);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "[Database] Error loading order: " + rs.getString("id"), e);
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Error loading orders", e);
        }

        logger.info("[Database] Loaded " + orders.size() + " orders.");
        return orders;
    }

    public void saveOrder(Order order) {
        List<ItemStack> items = order.getInventory();
        byte[] serialized = null;
        boolean hasItems = items != null && !items.isEmpty();
        boolean serializationFailed = false;

        if (hasItems) {
            serialized = serializeInventory(items);
            if (serialized == null) {
                serializationFailed = true;
                logger.log(Level.SEVERE, "[Database] Failed to serialize inventory for order " + order.getId() + " with " + items.size() + " items - inventory column will NOT be overwritten to prevent data loss");
            }
        }

        String sql;
        if (serializationFailed) {
            // Skip inventory column to preserve existing data in DB
            sql = "INSERT INTO " + tablePrefix + "orders "
                    + "(id, owner, item, needed, filled, price, created, expiry, removed_by_admin, potion_type, enchantment_type, inventory) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL) "
                    + "ON DUPLICATE KEY UPDATE "
                    + "filled=VALUES(filled), removed_by_admin=VALUES(removed_by_admin), "
                    + "potion_type=VALUES(potion_type), enchantment_type=VALUES(enchantment_type)";
        } else {
            sql = "INSERT INTO " + tablePrefix + "orders "
                    + "(id, owner, item, needed, filled, price, created, expiry, removed_by_admin, potion_type, enchantment_type, inventory) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE "
                    + "filled=VALUES(filled), removed_by_admin=VALUES(removed_by_admin), "
                    + "potion_type=VALUES(potion_type), enchantment_type=VALUES(enchantment_type), inventory=VALUES(inventory)";
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, order.getId().toString());
            ps.setString(2, order.getOwner().toString());
            ps.setString(3, order.getMaterial().name());
            ps.setInt(4, order.getNeeded());
            ps.setInt(5, order.getFilled());
            ps.setDouble(6, order.getPricePerItem());
            ps.setLong(7, order.getCreated());
            ps.setLong(8, order.getExpiry());
            ps.setBoolean(9, order.isRemovedByAdmin());
            ps.setString(10, order.getPotionType());
            ps.setString(11, order.getEnchantmentType());

            if (!serializationFailed) {
                if (hasItems) {
                    ps.setBytes(12, serialized);
                } else {
                    ps.setNull(12, Types.BLOB);
                }
            }

            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Error saving order " + order.getId(), e);
        }
    }

    public void saveAllOrders(List<Order> orders) {
        String sqlWithInv = "INSERT INTO " + tablePrefix + "orders "
                + "(id, owner, item, needed, filled, price, created, expiry, removed_by_admin, potion_type, enchantment_type, inventory) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE "
                + "filled=VALUES(filled), removed_by_admin=VALUES(removed_by_admin), "
                + "potion_type=VALUES(potion_type), enchantment_type=VALUES(enchantment_type), inventory=VALUES(inventory)";

        String sqlWithoutInv = "INSERT INTO " + tablePrefix + "orders "
                + "(id, owner, item, needed, filled, price, created, expiry, removed_by_admin, potion_type, enchantment_type, inventory) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL) "
                + "ON DUPLICATE KEY UPDATE "
                + "filled=VALUES(filled), removed_by_admin=VALUES(removed_by_admin), "
                + "potion_type=VALUES(potion_type), enchantment_type=VALUES(enchantment_type)";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement psWithInv = conn.prepareStatement(sqlWithInv);
                 PreparedStatement psWithoutInv = conn.prepareStatement(sqlWithoutInv)) {

                for (Order order : orders) {
                    List<ItemStack> items = order.getInventory();
                    boolean hasItems = items != null && !items.isEmpty();
                    byte[] serialized = null;
                    boolean serializationFailed = false;

                    if (hasItems) {
                        serialized = serializeInventory(items);
                        if (serialized == null) {
                            serializationFailed = true;
                            logger.log(Level.SEVERE, "[Database] Failed to serialize inventory for order " + order.getId() + " with " + items.size() + " items - inventory column will NOT be overwritten");
                        }
                    }

                    PreparedStatement ps = serializationFailed ? psWithoutInv : psWithInv;
                    ps.setString(1, order.getId().toString());
                    ps.setString(2, order.getOwner().toString());
                    ps.setString(3, order.getMaterial().name());
                    ps.setInt(4, order.getNeeded());
                    ps.setInt(5, order.getFilled());
                    ps.setDouble(6, order.getPricePerItem());
                    ps.setLong(7, order.getCreated());
                    ps.setLong(8, order.getExpiry());
                    ps.setBoolean(9, order.isRemovedByAdmin());
                    ps.setString(10, order.getPotionType());
                    ps.setString(11, order.getEnchantmentType());

                    if (!serializationFailed) {
                        if (hasItems) {
                            ps.setBytes(12, serialized);
                        } else {
                            ps.setNull(12, Types.BLOB);
                        }
                    }

                    ps.addBatch();
                }

                psWithInv.executeBatch();
                psWithoutInv.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Error saving all orders", e);
        }
    }

    public void deleteOrder(UUID orderId) {
        String sql = "DELETE FROM " + tablePrefix + "orders WHERE id=?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, orderId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Error deleting order " + orderId, e);
        }
    }

    public void deleteAllOrders() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("DELETE FROM " + tablePrefix + "orders");
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[Database] Error deleting all orders", e);
        }
    }

    private void reconstructInventory(Order order, Material material, int amount) {
        int maxStack = material.getMaxStackSize();
        int remaining = amount;

        while (remaining > 0) {
            int stackSize = Math.min(remaining, maxStack);
            ItemStack item = new ItemStack(material, stackSize);

            // Apply potion type if applicable
            if (order.isPotion() && order.getPotionType() != null) {
                try {
                    PotionMeta potionMeta = (PotionMeta) item.getItemMeta();
                    PotionType potionType = PotionType.valueOf(order.getPotionType());
                    potionMeta.setBasePotionType(potionType);
                    item.setItemMeta(potionMeta);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "[Database] Failed to apply potion type during recovery for order " + order.getId(), e);
                }
            }

            // Apply enchantments if applicable
            if (order.getEnchantmentType() != null && !order.getEnchantmentType().isEmpty()) {
                try {
                    if (order.isEnchantedBook()) {
                        EnchantmentStorageMeta storageMeta = (EnchantmentStorageMeta) item.getItemMeta();
                        String[] parts = order.getEnchantmentType().split("_");
                        if (parts.length >= 2) {
                            String enchantKey = parts[0];
                            int enchantLevel;
                            for (enchantLevel = 1; enchantLevel < parts.length - 1; enchantLevel++) {
                                enchantKey = enchantKey + "_" + parts[enchantLevel];
                            }
                            enchantLevel = Integer.parseInt(parts[parts.length - 1]);
                            Enchantment enchantment = (Enchantment) Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchantKey.toLowerCase()));
                            if (enchantment != null) {
                                storageMeta.addStoredEnchant(enchantment, enchantLevel, true);
                            }
                        }
                        item.setItemMeta(storageMeta);
                    } else {
                        ItemMeta meta = item.getItemMeta();
                        for (String enchantEntry : order.getEnchantmentType().split(";")) {
                            String[] enchantParts = enchantEntry.split("_");
                            if (enchantParts.length < 2) continue;
                            String enchantKey = enchantParts[0].toLowerCase();
                            int enchantLevel;
                            for (enchantLevel = 1; enchantLevel < enchantParts.length - 1; enchantLevel++) {
                                enchantKey = enchantKey + "_" + enchantParts[enchantLevel].toLowerCase();
                            }
                            enchantLevel = Integer.parseInt(enchantParts[enchantParts.length - 1]);
                            Enchantment enchantment = (Enchantment) Registry.ENCHANTMENT.get(NamespacedKey.minecraft(enchantKey));
                            if (enchantment != null) {
                                meta.addEnchant(enchantment, enchantLevel, true);
                            }
                        }
                        item.setItemMeta(meta);
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "[Database] Failed to apply enchantments during recovery for order " + order.getId(), e);
                }
            }

            order.addItem(item);
            remaining -= stackSize;
        }
    }

    private byte[] serializeInventory(List<ItemStack> items) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeInt(items.size());
            for (ItemStack item : items) {
                boos.writeObject(item);
            }
            boos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[Database] Error serializing inventory - data will not be saved", e);
            return null;
        }
    }

    private List<ItemStack> deserializeInventory(byte[] data) {
        List<ItemStack> items = new ArrayList<>();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            int size = bois.readInt();
            for (int i = 0; i < size; i++) {
                ItemStack item = (ItemStack) bois.readObject();
                if (item != null) items.add(item);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "[Database] Error deserializing inventory", e);
        }
        return items;
    }
}
