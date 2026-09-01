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
 * Sohbet uzerinden metin girdisi ({@code orders.input-mode: CHAT}).
 *
 * <p>Tabela girdisi bazi sunucularda sorunlu: anti-grief eklentileri gecici blogu
 * engelleyebiliyor, tabela duzenleme ekrani bazi istemcilerde acilmiyor. Sohbet
 * modu bu durumlarda calisir bir alternatif sunar.</p>
 *
 * <p>Girdi beklenirken oyuncunun yazdigi mesaj sohbete <b>gonderilmez</b>; boylece
 * fiyat yazarken herkese "1000" diye bagirmis olmaz.</p>
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
            // Sure config.yml -> performance.chat-input-timeout-ticks
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

        // Sohbet olayi asenkron gelir; GUI acmak ve envantere dokunmak ana is
        // parcaciginda (Folia'da oyuncunun bolgesinde) olmak zorunda.
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

    /** Bekleyen girdiyi iptal eder; {@code runCallback} true ise iptal geri cagrisini da calistirir. */
    public void cancel(UUID playerId, boolean runCallback) {
        Pending data = pending.remove(playerId);
        if (data == null) return;
        if (data.timeoutTask() != null) {
            plugin.getSchedulerAdapter().cancelTask(data.timeoutTask());
        }
        if (runCallback) data.onCancel().run();
    }
}
