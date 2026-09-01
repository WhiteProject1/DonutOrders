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
     * Ozel esya kimligi ({@code itemsadder:ruby}); vanilya siparislerde null.
     *
     * <p>null olmasi eski davranisin aynen surdugu anlamina gelir: 2.0'da
     * olusturulmus kayitlar bu alan olmadan yuklenir ve materyal esitligine
     * gore calismaya devam eder.</p>
     */
    private String customId = null;
    private final List<ItemStack> inventory = new CopyOnWriteArrayList<>();

    /** İptal/kaldırma ile doldurmayı birbirine dışlayan bayrak. Monitör: this. */
    private boolean closed = false;

    public Order(UUID owner, Material material, int needed, double pricePerItem) {
        this(owner, material, needed, pricePerItem, null);
    }

    public Order(UUID owner, Material material, int needed, double pricePerItem, String potionType) {
        this(owner, material, needed, pricePerItem, potionType, null);
    }

    public Order(UUID owner, Material material, int needed, double pricePerItem, String potionType, String enchantmentType) {
        this.id = UUID.randomUUID();
        this.owner = owner;
        this.material = material;
        this.needed = needed;
        this.pricePerItem = pricePerItem;
        this.potionType = potionType;
        this.enchantmentType = enchantmentType;
        this.created = System.currentTimeMillis();
        this.expiry = this.created + expiryDuration();
    }

    /**
     * Siparisin gecerlilik suresi — {@code orders.expiry-hours}.
     *
     * <p>Eklenti henuz yuklenmediyse (birim testi, erken cagri) 7 gunluk eski
     * varsayilana duser; boylece yapilandirma okunamadiginda da siparis olusur.</p>
     */
    private static long expiryDuration() {
        try {
            ro.server.orderplugin.OrderPlugin plugin = ro.server.orderplugin.OrderPlugin.getInstance();
            if (plugin != null && plugin.settings() != null) return plugin.settings().expiryMillis();
        } catch (Exception ignored) {
            // Yapilandirma yoksa varsayilan sure kullanilir.
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
     * Depodan tek bir yığını atomik olarak çıkarır. Kopyala-değiştir-yaz
     * yerine bunu kullanın: arada gelen bir teslimatın eklediği eşya silinmez.
     *
     * @return çıkarılan yığın, index geçersizse null
     */
    public synchronized ItemStack removeItemAt(int index) {
        if (index < 0 || index >= this.inventory.size()) return null;
        return this.inventory.remove(index);
    }

    /**
     * Depodan ardışık bir sayfa aralığını atomik olarak çıkarır (sayfa bazlı
     * toplama/dökme için). Kopyala-değiştir-yaz yerine bunu kullanın: arada
     * gelen bir teslimatın eklediği eşya silinmez. Aralık dizi sınırlarına
     * göre kırpılır; geçersiz aralıkta boş liste döner.
     *
     * @return çıkarılan yığınlar, orijinal sıraları korunarak (boş olabilir, asla null değil)
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
     * Referans eşitliğiyle ({@code ==}) eşleşen belirli yığınları depodan
     * atomik olarak çıkarır (sat-tümü onayı için). Kopyala-değiştir-yaz
     * yerine bunu kullanın: arada gelen bir teslimatın eklediği eşya silinmez.
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
     * Sipariş hâlâ açıksa doldurur. Kapatma ile aynı monitörü kullandığı için
     * bir iptalle araya girilemez; tryFill'in aksine kapanışa karşı da güvenlidir.
     *
     * @return gerçekten doldurulan miktar; 0 ise sipariş kapalı veya dolu
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
     * Siparişi kapatır ve o anki filled değerini döndürür. İade tutarı bu
     * anlık görüntüden hesaplanmalıdır — sonradan okumak yarışa açıktır.
     *
     * @return kapatma anındaki filled; sipariş zaten kapalıysa -1
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
