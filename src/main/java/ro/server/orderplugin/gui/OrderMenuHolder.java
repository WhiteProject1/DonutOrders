package ro.server.orderplugin.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import ro.server.orderplugin.model.Order;

/**
 * Marker that carries which DonutOrders menu an opened inventory belongs to.
 *
 * <p>Menus used to be identified by looking at the title text. That no longer
 * works now that titles are both configurable and <b>change per player
 * language</b>: a Turkish-speaking player sees "SIPARISLER", an English-speaking
 * one sees "ORDERS", and the same comparison can't be correct for both at once.
 * On top of that, matching by title also risked accidentally interfering with
 * another plugin's menu that happened to share the same title.</p>
 *
 * <p>With the holder, the menu id, page and context (order, filter) are carried
 * on the inventory itself, completely independent of the text.</p>
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
    /** Search or filter text; null if none. */
    public String query() { return query; }
    /** The order this menu operates on; null if none. */
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
