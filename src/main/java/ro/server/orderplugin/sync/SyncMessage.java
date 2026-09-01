package ro.server.orderplugin.sync;

/**
 * A single cross-server event.
 *
 * @param type      one of the constants in {@link SyncService}
 * @param serverId  the server that PRODUCED the event (we never reprocess our own)
 * @param orderId   the related order id, or null
 * @param playerId  the related player id, or null
 * @param payload   free-form text (message body etc.), or null
 * @param timestamp epoch ms
 */
public record SyncMessage(String type, String serverId, String orderId,
                          String playerId, String payload, long timestamp) {

    public static SyncMessage of(String type, String serverId, String orderId) {
        return new SyncMessage(type, serverId, orderId, null, null, System.currentTimeMillis());
    }

    public static SyncMessage message(String serverId, String playerId, String text) {
        return new SyncMessage(SyncService.PLAYER_MESSAGE, serverId, null, playerId, text,
                System.currentTimeMillis());
    }
}
