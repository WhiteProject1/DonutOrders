package ro.server.orderplugin.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import ro.server.orderplugin.model.Order;
import ro.server.orderplugin.OrderPlugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
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

public class SQLiteStorage {

    private final OrderPlugin plugin;
    private final Logger logger;
    private HikariDataSource dataSource;
    private final String tablePrefix;
    private final String filePath;

    public SQLiteStorage(OrderPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.tablePrefix = plugin.getConfig().getString("storage.sqlite.table-prefix",
                plugin.getConfig().getString("storage.database.table-prefix", "donutorders_"));
        if (!this.tablePrefix.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid table prefix: " + this.tablePrefix + ". Only alphanumeric characters and underscores allowed.");
        }
        this.filePath = plugin.getConfig().getString("storage.sqlite.file-path", "orders.db");
    }

    public boolean connect() {
        try {
            File dbFile = new File(plugin.getDataFolder(), filePath);
            if (!dbFile.getParentFile().exists()) {
                dbFile.getParentFile().mkdirs();
            }

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(30000);
            config.setIdleTimeout(600000);
            config.setMaxLifetime(1800000);
            config.setPoolName("DonutOrders-SQLite-Pool");
            config.setLeakDetectionThreshold(60000);

            // SQLite specific settings
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("synchronous", "NORMAL");

            this.dataSource = new HikariDataSource(config);
            logger.info("[Database] SQLite pool started (" + dbFile.getAbsolutePath() + ")");

            // Enable WAL mode for better concurrent read/write
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
            }

            createTables();
            return true;
        } catch (Exception e) {
            logger.severe("[Database] SQLite connection failed: " + e.getMessage());
            return false;
        }
    }

    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("[Database] SQLite pool closed.");
        }
    }

    private void createTables() throws SQLException {
        String ordersTable = "CREATE TABLE IF NOT EXISTS " + tablePrefix + "orders ("
                + "id TEXT PRIMARY KEY,"
                + "owner TEXT NOT NULL,"
                + "item TEXT NOT NULL,"
                + "needed INTEGER NOT NULL,"
                + "filled INTEGER DEFAULT 0,"
                + "price REAL NOT NULL,"
                + "created INTEGER NOT NULL,"
                + "expiry INTEGER NOT NULL,"
                + "removed_by_admin INTEGER DEFAULT 0,"
                + "potion_type TEXT DEFAULT NULL,"
                + "enchantment_type TEXT DEFAULT NULL,"
                + "inventory BLOB DEFAULT NULL"
                + ")";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(ordersTable);
        }
        logger.info("[Database] SQLite tables ready.");
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
                    order.setRemovedByAdmin(rs.getInt("removed_by_admin") == 1);

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

        logger.info("[Database] Loaded " + orders.size() + " orders from SQLite.");
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
                logger.log(Level.SEVERE, "[Database] Failed to serialize inventory for order " + order.getId() + " with " + items.size() + " items - inventory column will NOT be overwritten");
            }
        }

        String sql;
        if (serializationFailed) {
            sql = "INSERT INTO " + tablePrefix + "orders "
                    + "(id, owner, item, needed, filled, price, created, expiry, removed_by_admin, potion_type, enchantment_type, inventory) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL) "
                    + "ON CONFLICT(id) DO UPDATE SET "
                    + "filled=excluded.filled, removed_by_admin=excluded.removed_by_admin, "
                    + "potion_type=excluded.potion_type, enchantment_type=excluded.enchantment_type";
        } else {
            sql = "INSERT INTO " + tablePrefix + "orders "
                    + "(id, owner, item, needed, filled, price, created, expiry, removed_by_admin, potion_type, enchantment_type, inventory) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) "
                    + "ON CONFLICT(id) DO UPDATE SET "
                    + "filled=excluded.filled, removed_by_admin=excluded.removed_by_admin, "
                    + "potion_type=excluded.potion_type, enchantment_type=excluded.enchantment_type, inventory=excluded.inventory";
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
            ps.setInt(9, order.isRemovedByAdmin() ? 1 : 0);
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
                + "ON CONFLICT(id) DO UPDATE SET "
                + "filled=excluded.filled, removed_by_admin=excluded.removed_by_admin, "
                + "potion_type=excluded.potion_type, enchantment_type=excluded.enchantment_type, inventory=excluded.inventory";

        String sqlWithoutInv = "INSERT INTO " + tablePrefix + "orders "
                + "(id, owner, item, needed, filled, price, created, expiry, removed_by_admin, potion_type, enchantment_type, inventory) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL) "
                + "ON CONFLICT(id) DO UPDATE SET "
                + "filled=excluded.filled, removed_by_admin=excluded.removed_by_admin, "
                + "potion_type=excluded.potion_type, enchantment_type=excluded.enchantment_type";

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
                    ps.setInt(9, order.isRemovedByAdmin() ? 1 : 0);
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
