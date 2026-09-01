package ro.server.orderplugin.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class Order {
    private final UUID id;
    private final UUID owner;
    private final Material material;
    private final int needed;
    private final AtomicInteger filled = new AtomicInteger(0);
    private final double pricePerItem;
    private final long created;
    private final long expiry;
    private volatile boolean removedByAdmin = false;
    private String potionType = null;
    private String enchantmentType = null;
    /**
     * Custom item id ({@code itemsadder:ruby}); null for vanilla orders.
     *
     * <p>Being null means the old behavior continues unchanged: records
     * created in 2.0 load without this field and keep working based on
     * material equality.</p>
     */
    private String customId = null;
    private final List<ItemStack> inventory = new CopyOnWriteArrayList<>();

    /** Flag that makes cancel/removal mutually exclusive with filling. Monitor: this. */
    private boolean closed = false;

    public Order(UUID owner, Material material, int needed, double pricePerItem) {
        this(owner, material, needed, pricePerItem, null);
    }

    public Order(UUID owner, Material material, int needed, double pricePerItem, String potionType) {
        this(owner, material, needed, pricePerItem, potionType, null);
    }

    public Order(UUID owner, Material material, int needed, double pricePerItem, String potionType, String enchantmentType) {
        this(owner, material, needed, pricePerItem, potionType, enchantmentType, false);
    }

    /**
     * @param neverExpires {@code true} if the order owner holds the
     *        {@code orders.expire-bypass-permission} permission at creation time.
     *        It is NOT stored as a separate field — instead {@code expiry} is set
     *        to {@link Long#MAX_VALUE}, so the storage schema stays unchanged and
     *        the existing expiry checks ({@code expiry <= now}) work correctly
     *        without any modification.
     */
    public Order(UUID owner, Material material, int needed, double pricePerItem, String potionType, String enchantmentType, boolean neverExpires) {
        this.id = UUID.randomUUID();
        this.owner = owner;
        this.material = material;
        this.needed = needed;
        this.pricePerItem = pricePerItem;
        this.potionType = potionType;
        this.enchantmentType = enchantmentType;
        this.created = System.currentTimeMillis();
        this.expiry = neverExpires ? Long.MAX_VALUE : this.created + expiryDuration();
    }

    /**
     * The order's validity period — {@code orders.expiry-hours}.
     *
     * <p>Falls back to the old 7-day default if the plugin isn't loaded yet
     * (unit test, early call), so an order can still be created even when
     * config can't be read.</p>
     */
    private static long expiryDuration() {
        try {
            ro.server.orderplugin.OrderPlugin plugin = ro.server.orderplugin.OrderPlugin.getInstance();
            if (plugin != null && plugin.settings() != null) return plugin.settings().expiryMillis();
        } catch (Exception ignored) {
            // No config available: fall back to the default duration.
        }
        return TimeUnit.DAYS.toMillis(7L);
    }

    public Order(UUID id, UUID owner, Material material, int needed, int filled, double pricePerItem, long created, long expiry) {
        this.id = id;
        this.owner = owner;
        this.material = material;
        this.needed = needed;
        this.filled.set(filled);
        this.pricePerItem = pricePerItem;
        this.created = created;
        this.expiry = expiry;
    }

    public UUID getId() {
        return this.id;
    }

    public UUID getOwner() {
        return this.owner;
    }

    public Material getMaterial() {
        return this.material;
    }

    public int getNeeded() {
        return this.needed;
    }

    public int getFilled() {
        return this.filled.get();
    }

    public double getPricePerItem() {
        return this.pricePerItem;
    }

    public long getCreated() {
        return this.created;
    }

    public long getExpiry() {
        return this.expiry;
    }

    public boolean isRemovedByAdmin() {
        return this.removedByAdmin;
    }

    public synchronized List<ItemStack> getInventory() {
        return new ArrayList<>(this.inventory);
    }

    /**
     * Atomically removes a single stack from storage. Use this instead of
     * copy-modify-write: an item added by a delivery landing in between won't
     * be lost.
     *
     * @return the removed stack, or null if the index is invalid
     */
    public synchronized ItemStack removeItemAt(int index) {
        if (index < 0 || index >= this.inventory.size()) return null;
        return this.inventory.remove(index);
    }

    /**
     * Atomically removes a contiguous page range from storage (for page-based
     * collect/dump). Use this instead of copy-modify-write: an item added by
     * a delivery landing in between won't be lost. The range is clamped to
     * the array bounds; an invalid range returns an empty list.
     *
     * @return the removed stacks, in their original order (may be empty, never null)
     */
    public synchronized List<ItemStack> removeItemsInRange(int from, int to) {
        int size = this.inventory.size();
        int start = Math.max(0, Math.min(from, size));
        int end = Math.max(start, Math.min(to, size));
        List<ItemStack> taken = new ArrayList<>();
        for (int i = end - 1; i >= start; i--) {
            taken.add(0, this.inventory.remove(i));
        }
        return taken;
    }

    /**
     * Atomically removes specific stacks from storage that match by reference
     * equality ({@code ==}) (for sell-all confirmation). Use this instead of
     * copy-modify-write: an item added by a delivery landing in between won't
     * be lost.
     */
    public synchronized void removeItemsByIdentity(List<ItemStack> toRemove) {
        if (toRemove == null || toRemove.isEmpty()) return;
        this.inventory.removeIf(stack -> {
            for (ItemStack candidate : toRemove) {
                if (candidate == stack) return true;
            }
            return false;
        });
    }

    public synchronized void addItem(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return;
        }
        ItemStack clone = itemStack.clone();
        int maxStack = clone.getMaxStackSize();
        for (ItemStack existing : this.inventory) {
            int space;
            if (!existing.isSimilar(clone) || (space = maxStack - existing.getAmount()) <= 0) continue;
            int toAdd = Math.min(space, clone.getAmount());
            existing.setAmount(existing.getAmount() + toAdd);
            clone.setAmount(clone.getAmount() - toAdd);
            if (clone.getAmount() > 0) continue;
            return;
        }
        if (clone.getAmount() > 0) {
            this.inventory.add(clone);
        }
    }

    public synchronized void clearInventory() {
        this.inventory.clear();
    }

    public synchronized void setInventory(List<ItemStack> items) {
        this.inventory.clear();
        for (ItemStack item : items) {
            if (item == null || item.getType() == Material.AIR) continue;
            this.addItem(item);
        }
    }

    public int getInventoryCount() {
        int count = 0;
        for (ItemStack item : this.inventory) {
            if (item == null) continue;
            count += item.getAmount();
        }
        return count;
    }

    public synchronized void setFilled(int filled) {
        this.filled.set(filled);
    }

    public int tryFill(int amount) {
        while (true) {
            int current = this.filled.get();
            int remaining = this.needed - current;
            if (remaining <= 0) return 0;
            int toFill = Math.min(amount, remaining);
            if (this.filled.compareAndSet(current, current + toFill)) {
                return toFill;
            }
        }
    }

    /**
     * Fills the order only if it's still open. Uses the same monitor as
     * closing, so a cancellation can't interleave with it; unlike tryFill,
     * this is also safe against closure.
     *
     * @return the amount actually filled; 0 means the order is closed or full
     */
    public synchronized int tryFillIfOpen(int amount) {
        if (closed) return 0;
        int current = this.filled.get();
        int remaining = this.needed - current;
        if (remaining <= 0) return 0;
        int toFill = Math.min(amount, remaining);
        this.filled.set(current + toFill);
        return toFill;
    }

    /**
     * Closes the order and returns its filled value at that instant. The
     * refund amount must be computed from this snapshot — reading it
     * afterward is a race.
     *
     * @return filled at the moment of closing; -1 if the order is already closed
     */
    public synchronized int closeAndGetFilled() {
        if (closed) return -1;
        closed = true;
        return this.filled.get();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public void setRemovedByAdmin(boolean removedByAdmin) {
        this.removedByAdmin = removedByAdmin;
    }

    public String getPotionType() {
        return this.potionType;
    }

    public void setPotionType(String potionType) {
        this.potionType = potionType;
    }

    public String getCustomId() {
        return this.customId;
    }

    public void setCustomId(String customId) {
        this.customId = customId == null || customId.isBlank() ? null : customId;
    }

    public boolean isCustom() {
        return this.customId != null;
    }

    public String getEnchantmentType() {
        return this.enchantmentType;
    }

    public void setEnchantmentType(String enchantmentType) {
        this.enchantmentType = enchantmentType;
    }

    public boolean isPotion() {
        return this.material == Material.POTION || this.material == Material.SPLASH_POTION || this.material == Material.LINGERING_POTION;
    }

    public boolean isEnchantedBook() {
        return this.material == Material.ENCHANTED_BOOK;
    }

    public int getRemaining() {
        return this.needed - this.filled.get();
    }

    public boolean isComplete() {
        return this.filled.get() >= this.needed;
    }

    public boolean hasItems() {
        return !this.inventory.isEmpty();
    }
}
