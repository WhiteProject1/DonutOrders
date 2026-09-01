package ro.server.orderplugin.sync;

import java.util.List;

/**
 * Empty implementation used when networking is disabled.
 *
 * <p>Returning this instead of null removes the need to write
 * {@code if (sync != null)} checks everywhere it's called; a forgotten check
 * can never produce a {@code NullPointerException}.</p>
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
