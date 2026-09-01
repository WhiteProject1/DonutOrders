package ro.server.orderplugin.sync;

/**
 * Sunucular arasi tek bir olay.
 *
 * @param type      {@link SyncService} icindeki sabitlerden biri
 * @param serverId  olayi URETEN sunucu (kendi olayimizi tekrar islemeyiz)
 * @param orderId   ilgili siparis kimligi ya da null
 * @param playerId  ilgili oyuncu kimligi ya da null
 * @param payload   serbest metin (mesaj govdesi vb.) ya da null
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
