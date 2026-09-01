package ro.server.orderplugin.sync;

import java.util.List;

/**
 * Cross-server sync.
 *
 * <p>{@link NoOpSyncService} takes over when disabled; callers never need to
 * ask "is networking on?".</p>
 */
public interface SyncService {

    /** Event types. */
    String ORDER_CREATED = "ORDER_CREATED";
    String ORDER_UPDATED = "ORDER_UPDATED";
    String ORDER_REMOVED = "ORDER_REMOVED";
    String PLAYER_MESSAGE = "PLAYER_MESSAGE";
    String HEARTBEAT = "HEARTBEAT";

    void start();

    void stop();

    /** Broadcasts the event to the network. Does nothing when disabled. */
    void publish(SyncMessage message);

    /** This server's id within the network. */
    String serverId();

    /** Servers that have checked in within the last 'heartbeat-timeout'. */
    List<String> knownServers();

    /** Time of the last successful sync (epoch ms); 0 if it never happened. */
    long lastSyncMillis();

    default boolean enabled() {
        return false;
    }
}
