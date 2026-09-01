package ro.server.orderplugin.util;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import io.papermc.paper.event.player.AsyncChatEvent;

import ro.server.orderplugin.OrderPlugin;

/**
 * Text input via chat ({@code orders.input-mode: CHAT}).
 *
 * <p>Sign input is problematic on some servers: anti-grief plugins can block
 * temporary placement, and the sign-editing screen doesn't open on some
 * clients. Chat mode offers a working alternative for those cases.</p>
 *
 * <p>While an input is pending, the message the player types is <b>not</b>
 * sent to chat; so typing a price doesn't end up shouting "1000" to everyone.</p>
 */
public final class ChatInputManager implements Listener {

    private record Pending(Consumer<String> callback, Runnable onCancel, String cancelWord, Object timeoutTask) {}

    private final OrderPlugin plugin;
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatInputManager(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    public void request(Player player, String promptMessage, String cancelWord,
                        Consumer<String> callback, Runnable onCancel) {
        UUID id = player.getUniqueId();
        cancel(id, false);

        player.closeInventory();
        player.sendMessage(promptMessage);

        Object task = plugin.getSchedulerAdapter().runOnEntityLater(plugin, player, () -> {
            Pending expired = pending.remove(id);
            if (expired == null) return;
            if (player.isOnline()) {
                player.sendMessage(plugin.msg(player, "input.chat.timeout"));
                expired.onCancel().run();
            }
            // Duration: config.yml -> performance.chat-input-timeout-ticks
        }, plugin.settings().chatInputTimeoutTicks());

        pending.put(id, new Pending(callback, onCancel, cancelWord, task));
    }

    public boolean hasPending(UUID playerId) {
        return pending.containsKey(playerId);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending data = pending.remove(player.getUniqueId());
        if (data == null) return;

        event.setCancelled(true);
        if (data.timeoutTask() != null) {
            plugin.getSchedulerAdapter().cancelTask(data.timeoutTask());
        }

        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        // The chat event fires asynchronously; opening a GUI and touching the
        // inventory has to happen on the main thread (on Folia, on the player's region).
        plugin.getSchedulerAdapter().runOnEntity(plugin, player, () -> {
            if (!player.isOnline()) return;
            if (input.isEmpty() || input.equalsIgnoreCase(data.cancelWord())) {
                player.sendMessage(plugin.msg(player, "input.chat.cancelled"));
                data.onCancel().run();
                return;
            }
            data.callback().accept(input);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), false);
    }

    /** Cancels the pending input; if {@code runCallback} is true also runs the cancel callback. */
    public void cancel(UUID playerId, boolean runCallback) {
        Pending data = pending.remove(playerId);
        if (data == null) return;
        if (data.timeoutTask() != null) {
            plugin.getSchedulerAdapter().cancelTask(data.timeoutTask());
        }
        if (runCallback) data.onCancel().run();
    }
}
