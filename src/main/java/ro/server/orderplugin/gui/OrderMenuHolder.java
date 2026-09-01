package ro.server.orderplugin.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import ro.server.orderplugin.model.Order;

/**
 * Acilan envanterin hangi DonutOrders menusu oldugunu tasiyan isaretci.
 *
 * <p>Eskiden menuler baslik metnine bakilarak taniniyordu. Basliklar artik hem
 * yapilandirilabilir hem de <b>oyuncunun diline gore</b> degistigi icin bu yontem
 * calismaz: Turkce oynayan biri "SIPARISLER", Ingilizce oynayan "ORDERS" gorur ve
 * ayni karsilastirma ikisinde birden dogru olamaz. Ustelik baslik eslestirmesi,
 * ayni basligi tasiyan baska bir eklentinin menusune yanlislikla mudahale etme
 * riskini de tasiyordu.</p>
 *
 * <p>Holder ile menu kimligi, sayfa ve baglam (siparis, filtre) envanterin
 * kendisinde tasinir; metinden tamamen bagimsizdir.</p>
 */
public final class OrderMenuHolder implements InventoryHolder {

    private final String menuId;
    private final int page;
    private final String query;
    private final Order order;
    private Inventory inventory;

    public OrderMenuHolder(String menuId) {
        this(menuId, 1, null, null);
    }

    public OrderMenuHolder(String menuId, int page) {
        this(menuId, page, null, null);
    }

    public OrderMenuHolder(String menuId, int page, String query, Order order) {
        this.menuId = menuId;
        this.page = page;
        this.query = query;
        this.order = order;
    }

    public String menuId() { return menuId; }
    public int page() { return page; }
    /** Arama ya da filtre metni; yoksa null. */
    public String query() { return query; }
    /** Menunun uzerinde calistigi siparis; yoksa null. */
    public Order order() { return order; }

    public boolean is(String id) {
        return menuId.equals(id);
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
