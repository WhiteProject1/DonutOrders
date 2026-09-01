package ro.server.orderplugin.util;

import java.util.List;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import ro.server.orderplugin.OrderPlugin;
import ro.server.orderplugin.config.Settings;

/**
 * The single entry point for free-text inputs like amount/price/search.
 *
 * <p>The caller doesn't know whether the input comes from a sign or chat;
 * which one is used is decided by {@code orders.input-mode}. Prompt texts
 * come from the language file, so each player sees the prompt in their own
 * language.</p>
 */
public final class InputManager {

    /** Which input is being requested — determines the language key and default text. */
    public enum Type {
        AMOUNT("amount"),
        PRICE("price"),
        FILTER("filter"),
        SEARCH("search");

        private final String key;

        Type(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private final OrderPlugin plugin;

    public InputManager(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    public void request(Player player, Type type, Consumer<String> onInput, Runnable onCancel) {
        if (plugin.settings().inputMode() == Settings.InputMode.CHAT) {
            String prompt = plugin.msg(player, "input.chat." + type.key(),
                    "%cancel%", plugin.rawMsg(player, "input.chat.cancel-word"));
            plugin.getChatInputManager().request(player, prompt,
                    plugin.rawMsg(player, "input.chat.cancel-word"), onInput, onCancel);
            return;
        }

        List<String> lines = plugin.msgList(player, "input.sign." + type.key());
        String[] promptLines = new String[4];
        for (int i = 0; i < 4; i++) {
            promptLines[i] = i < lines.size() ? lines.get(i) : "";
        }
        plugin.getSignInputManager().requestInput(player, promptLines, onInput, onCancel);
    }

    /** Whether the player has a pending input (in either mode). */
    public boolean hasPending(Player player) {
        return plugin.getChatInputManager().hasPending(player.getUniqueId())
                || plugin.getSignInputManager().hasPendingInput(player.getUniqueId());
    }
}
