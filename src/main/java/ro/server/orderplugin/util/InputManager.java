package ro.server.orderplugin.util;

import java.util.List;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import ro.server.orderplugin.OrderPlugin;
import ro.server.orderplugin.config.Settings;

/**
 * Miktar/fiyat/arama gibi serbest metin girdilerinin tek kapisi.
 *
 * <p>Cagiran taraf girdinin tabeladan mi sohbetten mi geldigini bilmez; hangisinin
 * kullanilacagi {@code orders.input-mode} ile belirlenir. Istem metinleri dil
 * dosyasindan gelir, yani her oyuncu istemi kendi dilinde gorur.</p>
 */
public final class InputManager {

    /** Hangi girdi isteniyor — dil anahtarini ve varsayilan metni belirler. */
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

    /** Oyuncu icin bekleyen bir girdi var mi (iki modda da). */
    public boolean hasPending(Player player) {
        return plugin.getChatInputManager().hasPending(player.getUniqueId())
                || plugin.getSignInputManager().hasPendingInput(player.getUniqueId());
    }
}
