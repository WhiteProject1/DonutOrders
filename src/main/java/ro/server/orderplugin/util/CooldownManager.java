package ro.server.orderplugin.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import ro.server.orderplugin.OrderPlugin;

/**
 * Spam and macro protection.
 *
 * <p>Menus make the server do work on every click: the order list gets
 * filtered, sorted, and 54 items get rebuilt. An auto-clicking macro (or just
 * a player clicking unrealistically fast) can trigger this dozens of times a
 * second. This is the single answer to "did this player just do this action
 * too recently".</p>
 *
 * <h2>Why the message is also throttled</h2>
 * <p>Sending "you're too fast" on every blocked click would spam chat instead
 * of preventing spam. The warning has its own cooldown; the player is warned
 * once, and subsequent blocks stay silent.</p>
 *
 * <h2>Memory</h2>
 * <p>Records are cleared when a player quits ({@link #forget}). Expired
 * entries are also cleaned up in place on every access, so the map stays
 * bounded by the number of online players.</p>
 */
public final class CooldownManager {

    /** A cooldown type — each has its own duration and its own record. */
    public enum Type {
        /** Every click inside a menu. */
        CLICK,
        /** Opening a menu (command or button). */
        MENU,
        /** {@code /order} and other commands. */
        COMMAND,
        /** Creating a new order. */
        CREATE,
        /** Delivering an item to an order. */
        DELIVER,
        /** Requesting chat/sign input. */
        INPUT,
        /** The "you're too fast" warning itself. */
        WARNING
    }

    private final OrderPlugin plugin;
    private final Map<Type, Map<UUID, Long>> stamps = new ConcurrentHashMap<>();

    public CooldownManager(OrderPlugin plugin) {
        this.plugin = plugin;
        for (Type type : Type.values()) {
            stamps.put(type, new ConcurrentHashMap<>());
        }
    }

    /**
     * Is the action allowed right now? If so the timer starts <b>on this call</b>.
     *
     * <p>Checking and stamping are combined into one method: with two separate
     * calls, forgetting to stamp somewhere would silently disable the protection.</p>
     *
     * @return true if the action is allowed, false if the player must wait
     */
    public boolean check(Player player, Type type) {
        if (player == null) return true;
        long cooldown = millisFor(type);
        if (cooldown <= 0L) return true;
        if (bypasses(player)) return true;

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Map<UUID, Long> map = stamps.get(type);

        Long previous = map.get(id);
        if (previous != null && now - previous < cooldown) return false;
        map.put(id, now);
        return true;
    }

    /**
     * Same as {@link #check}, but when blocked sends the player a (throttled)
     * warning message and an error sound.
     */
    public boolean checkAndWarn(Player player, Type type) {
        if (check(player, type)) return true;
        warn(player);
        return false;
    }

    /** Warning message — has its own cooldown, otherwise it would become the spam itself. */
    public void warn(Player player) {
        if (player == null || !plugin.settings().protectionWarn()) return;
        if (!check(player, Type.WARNING)) return;
        player.sendMessage(plugin.msg(player, "errors.too-fast"));
        plugin.playError(player);
    }

    /** Clears all of the player's records (on quit). */
    public void forget(UUID playerId) {
        if (playerId == null) return;
        for (Map<UUID, Long> map : stamps.values()) {
            map.remove(playerId);
        }
    }

    /** Resets all counters on reload. */
    public void clear() {
        for (Map<UUID, Long> map : stamps.values()) {
            map.clear();
        }
    }

    private boolean bypasses(Player player) {
        String permission = plugin.settings().protectionBypassPermission();
        return permission != null && !permission.isBlank() && player.hasPermission(permission);
    }

    private long millisFor(Type type) {
        return switch (type) {
            case CLICK -> plugin.settings().cooldownClickMs();
            case MENU -> plugin.settings().cooldownMenuMs();
            case COMMAND -> plugin.settings().cooldownCommandMs();
            case CREATE -> plugin.settings().cooldownCreateMs();
            case DELIVER -> plugin.settings().cooldownDeliverMs();
            case INPUT -> plugin.settings().cooldownInputMs();
            case WARNING -> plugin.settings().cooldownWarnMs();
        };
    }
}
