package ro.server.orderplugin.sync;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import ro.server.orderplugin.OrderPlugin;
import ro.server.orderplugin.storage.MySQLStorage;

/**
 * Sync implementation that periodically polls the shared database.
 *
 * <p>Requires no extra software: it works as long as every server on the
 * network already uses the same MySQL/MariaDB. Compared to Redis it's
 * delayed (by {@code poll.interval-ticks}), but setup is zero.</p>
 *
 * <p>The query runs <b>asynchronously</b>; results are carried over to the
 * main thread and applied there. Blocking on SQL on the main thread would
 * freeze the server.</p>
 */
public final class PollingSyncService implements SyncService {

    private final OrderPlugin plugin;
    private final MySQLStorage storage;
    private final String serverId;
    private final long intervalTicks;
    private final int batchSize;
    private final long heartbeatTimeoutMillis;

    private Object taskHandle;
    private volatile long lastSeenId = -1L;
    private volatile long lastSync = 0L;
    private volatile List<String> servers = List.of();
    private long lastHeartbeat = 0L;

    public PollingSyncService(OrderPlugin plugin, MySQLStorage storage, String serverId,
                              long intervalTicks, int batchSize, long heartbeatTimeoutMillis) {
        this.plugin = plugin;
        this.storage = storage;
        this.serverId = serverId;
        this.intervalTicks = Math.max(20L, intervalTicks);
        this.batchSize = Math.max(1, batchSize);
        this.heartbeatTimeoutMillis = heartbeatTimeoutMillis;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void start() {
        // Resumes from the END of the queue on startup: if events that piled up
        // while the server was off got reprocessed, the same message would go
        // out to players a second time.
        plugin.getSchedulerAdapter().runAsync(plugin, this::initCursor);
        taskHandle = plugin.getSchedulerAdapter().runGlobalTimer(plugin,
                () -> plugin.getSchedulerAdapter().runAsync(plugin, this::poll),
                intervalTicks, intervalTicks);
        plugin.getLogger().info("Cross-server (POLL) acik. Sunucu kimligi: " + serverId);
    }

    @Override
    public void stop() {
        if (taskHandle != null) {
            plugin.getSchedulerAdapter().cancelTask(taskHandle);
            taskHandle = null;
        }
    }

    private void initCursor() {
        try (Connection conn = storage.connection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COALESCE(MAX(id), 0) FROM " + storage.prefix() + "sync_events");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) lastSeenId = rs.getLong(1);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Sync baslangic imleci okunamadi", e);
            lastSeenId = 0L;
        }
    }

    @Override
    public void publish(SyncMessage message) {
        plugin.getSchedulerAdapter().runAsync(plugin, () -> {
            try (Connection conn = storage.connection();
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO " + storage.prefix()
                         + "sync_events (type, server_id, order_id, player_id, payload, created_at)"
                         + " VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, message.type());
                ps.setString(2, message.serverId());
                ps.setString(3, message.orderId());
                ps.setString(4, message.playerId());
                ps.setString(5, message.payload());
                ps.setLong(6, message.timestamp());
                ps.executeUpdate();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Sync olayi yazilamadi: " + message.type(), e);
            }
        });
    }

    // ------------------------------------------------------------------ polling

    private void poll() {
        if (lastSeenId < 0) return;   // cursor not ready yet

        heartbeat();
        List<SyncMessage> batch = new ArrayList<>();
        long maxId = lastSeenId;

        try (Connection conn = storage.connection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, type, server_id, order_id,"
                     + " player_id, payload, created_at FROM " + storage.prefix() + "sync_events"
                     + " WHERE id > ? AND server_id <> ? ORDER BY id ASC LIMIT " + batchSize)) {
            ps.setLong(1, lastSeenId);
            ps.setString(2, serverId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    maxId = Math.max(maxId, rs.getLong("id"));
                    batch.add(new SyncMessage(rs.getString("type"), rs.getString("server_id"),
                            rs.getString("order_id"), rs.getString("player_id"),
                            rs.getString("payload"), rs.getLong("created_at")));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Sync taramasi basarisiz", e);
            return;
        }

        lastSeenId = maxId;
        lastSync = System.currentTimeMillis();
        if (batch.isEmpty()) return;

        plugin.getSchedulerAdapter().runGlobal(plugin, () -> {
            for (SyncMessage message : batch) apply(message);
        });
    }

    /** Runs on the main thread. */
    private void apply(SyncMessage message) {
        switch (message.type()) {
            case ORDER_CREATED, ORDER_UPDATED, ORDER_REMOVED -> {
                // The order list changed on another server: invalidate the cache.
                if (message.orderId() != null) {
                    try {
                        plugin.getGuiManager().invalidateOrderCache(UUID.fromString(message.orderId()));
                    } catch (IllegalArgumentException ignored) {
                        // Malformed id: only this event is skipped.
                    }
                }
                plugin.getOrderManager().reloadFromStorage();
            }
            case PLAYER_MESSAGE -> {
                if (message.playerId() == null || message.payload() == null) return;
                UUID target;
                try {
                    target = UUID.fromString(message.playerId());
                } catch (IllegalArgumentException e) {
                    return;
                }
                Player player = Bukkit.getPlayer(target);
                if (player != null && player.isOnline()) {
                    player.sendMessage(message.payload());
                }
                // If offline, nothing happens: the message has already been
                // written to PendingMessageManager on the server that sees them.
            }
            default -> { }
        }
    }

    private void heartbeat() {
        long now = System.currentTimeMillis();
        if (now - lastHeartbeat < 10_000L) return;
        lastHeartbeat = now;

        try (Connection conn = storage.connection()) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + storage.prefix()
                    + "servers (server_id, last_seen, version) VALUES (?,?,?)"
                    + " ON DUPLICATE KEY UPDATE last_seen=VALUES(last_seen), version=VALUES(version)")) {
                ps.setString(1, serverId);
                ps.setLong(2, now);
                ps.setString(3, plugin.getDescription().getVersion());
                ps.executeUpdate();
            }
            List<String> alive = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT server_id FROM "
                    + storage.prefix() + "servers WHERE last_seen > ?")) {
                ps.setLong(1, now - heartbeatTimeoutMillis);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) alive.add(rs.getString(1));
                }
            }
            servers = List.copyOf(alive);

            // Don't let events older than 24 hours bloat the queue.
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + storage.prefix()
                    + "sync_events WHERE created_at < ?")) {
                ps.setLong(1, now - 86_400_000L);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Heartbeat yazilamadi", e);
        }
    }

    @Override public String serverId() { return serverId; }

    @Override public List<String> knownServers() { return servers; }

    @Override public long lastSyncMillis() { return lastSync; }
}
