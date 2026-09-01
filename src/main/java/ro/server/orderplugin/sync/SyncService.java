package ro.server.orderplugin.sync;

import java.util.List;

/**
 * Sunucular arasi eslesme.
 *
 * <p>Kapali oldugunda {@link NoOpSyncService} devreye girer; cagiran taraflarin
 * "ag acik mi" diye sormasi gerekmez.</p>
 */
public interface SyncService {

    /** Olay turleri. */
    String ORDER_CREATED = "ORDER_CREATED";
    String ORDER_UPDATED = "ORDER_UPDATED";
    String ORDER_REMOVED = "ORDER_REMOVED";
    String PLAYER_MESSAGE = "PLAYER_MESSAGE";
    String HEARTBEAT = "HEARTBEAT";

    void start();

    void stop();

    /** Olayi aga duyurur. Kapaliyken hicbir sey yapmaz. */
    void publish(SyncMessage message);

    /** Bu sunucunun ag icindeki kimligi. */
    String serverId();

    /** Son 'heartbeat-timeout' icinde haber veren sunucular. */
    List<String> knownServers();

    /** Son basarili eslesmenin zamani (epoch ms); hic olmadiysa 0. */
    long lastSyncMillis();

    default boolean enabled() {
        return false;
    }
}
