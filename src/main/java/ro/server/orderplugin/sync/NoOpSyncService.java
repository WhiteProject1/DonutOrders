package ro.server.orderplugin.sync;

import java.util.List;

/**
 * Ag kapaliyken kullanilan bos uygulama.
 *
 * <p>null yerine bunun donmesi, cagiran her yerde {@code if (sync != null)}
 * kontrolu yazma ihtiyacini ortadan kaldirir; unutulan bir kontrol
 * {@code NullPointerException} uretemez.</p>
 */
public final class NoOpSyncService implements SyncService {

    private final String serverId;

    public NoOpSyncService(String serverId) {
        this.serverId = serverId;
    }

    @Override public void start() { }

    @Override public void stop() { }

    @Override public void publish(SyncMessage message) { }

    @Override public String serverId() { return serverId; }

    @Override public List<String> knownServers() { return List.of(serverId); }

    @Override public long lastSyncMillis() { return 0L; }
}
