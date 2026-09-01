package ro.server.orderplugin.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import ro.server.orderplugin.OrderPlugin;

/**
 * Spam ve makro korumasi.
 *
 * <p>Menuler her tiklamada sunucuya is yaptirir: siparis listesi suzulur,
 * siralanir, 54 esya yeniden kurulur. Otomatik tiklayan bir makro (ya da sadece
 * sinirsiz hizli tiklayan bir oyuncu) bunu saniyede onlarca kez tetikleyebilir.
 * Burasi "bu oyuncu bu islemi cok kisa sure once yapti mi" sorusunun tek
 * cevabidir.</p>
 *
 * <h2>Neden mesaj da sinirlaniyor</h2>
 * <p>Engellenen her tiklamaya "cok hizlisin" yazmak, spam'i onlemek yerine
 * sohbeti spam'lardi. Uyari kendi araligina tabidir; oyuncu bir kez uyarilir,
 * sonraki engellemeler sessizdir.</p>
 *
 * <h2>Bellek</h2>
 * <p>Kayitlar oyuncu cikisinda silinir ({@link #forget}). Ayrica her erisimde
 * suresi gecmis girdiler yerinde temizlendigi icin harita cevrimici oyuncu
 * sayisiyla sinirli kalir.</p>
 */
public final class CooldownManager {

    /** Bir bekleme turu — her birinin kendi suresi ve kendi kaydi vardir. */
    public enum Type {
        /** Menu icindeki her tiklama. */
        CLICK,
        /** Menu acma (komut ya da buton). */
        MENU,
        /** {@code /order} ve diger komutlar. */
        COMMAND,
        /** Yeni siparis olusturma. */
        CREATE,
        /** Siparise esya teslim etme. */
        DELIVER,
        /** Sohbet/tabela girdisi isteme. */
        INPUT,
        /** "Cok hizlisin" uyarisinin kendisi. */
        WARNING
    }

    private final OrderPlugin plugin;
    private final Map<Type, Map<UUID, Long>> stamps = new ConcurrentHashMap<>();

    public CooldownManager(OrderPlugin plugin) {
        this.plugin = plugin;
        for (Type type : Type.values()) {
            stamps.put(type, new ConcurrentHashMap<>());
        }
    }

    /**
     * Islem simdi yapilabilir mi? Yapilabiliyorsa sayac <b>bu cagride</b> baslar.
     *
     * <p>Kontrol ve isaretleme tek metotta birlestirildi: iki ayri cagri olsaydi
     * bir yerde isaretlemeyi unutmak korumayi sessizce devre disi birakirdi.</p>
     *
     * @return true ise islem serbest, false ise oyuncu beklemeli
     */
    public boolean check(Player player, Type type) {
        if (player == null) return true;
        long cooldown = millisFor(type);
        if (cooldown <= 0L) return true;
        if (bypasses(player)) return true;

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        Map<UUID, Long> map = stamps.get(type);

        Long previous = map.get(id);
        if (previous != null && now - previous < cooldown) return false;
        map.put(id, now);
        return true;
    }

    /**
     * {@link #check} ile ayni, ama engellendiginde oyuncuya (aralikli olarak)
     * uyari mesaji ve hata sesi gonderir.
     */
    public boolean checkAndWarn(Player player, Type type) {
        if (check(player, type)) return true;
        warn(player);
        return false;
    }

    /** Uyari mesaji — kendi araligina tabidir, aksi halde spam'in kendisi olurdu. */
    public void warn(Player player) {
        if (player == null || !plugin.settings().protectionWarn()) return;
        if (!check(player, Type.WARNING)) return;
        player.sendMessage(plugin.msg(player, "errors.too-fast"));
        plugin.playError(player);
    }

    /** Oyuncunun tum kayitlarini siler (cikista). */
    public void forget(UUID playerId) {
        if (playerId == null) return;
        for (Map<UUID, Long> map : stamps.values()) {
            map.remove(playerId);
        }
    }

    /** Yeniden yuklemede tum sayaclari sifirlar. */
    public void clear() {
        for (Map<UUID, Long> map : stamps.values()) {
            map.clear();
        }
    }

    private boolean bypasses(Player player) {
        String permission = plugin.settings().protectionBypassPermission();
        return permission != null && !permission.isBlank() && player.hasPermission(permission);
    }

    private long millisFor(Type type) {
        return switch (type) {
            case CLICK -> plugin.settings().cooldownClickMs();
            case MENU -> plugin.settings().cooldownMenuMs();
            case COMMAND -> plugin.settings().cooldownCommandMs();
            case CREATE -> plugin.settings().cooldownCreateMs();
            case DELIVER -> plugin.settings().cooldownDeliverMs();
            case INPUT -> plugin.settings().cooldownInputMs();
            case WARNING -> plugin.settings().cooldownWarnMs();
        };
    }
}
