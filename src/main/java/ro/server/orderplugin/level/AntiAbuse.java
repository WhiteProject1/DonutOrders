package ro.server.orderplugin.level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import ro.server.orderplugin.OrderPlugin;

/**
 * Seviye sistemi istismar korumasi.
 *
 * <h2>Kapatilan acik</h2>
 * <p>Seviye sistemi "ekonomik hareket" oduldur. Ama iki arkadas hicbir ekonomik
 * hareket uretmeden birbirini seviye atlatabilir:</p>
 * <ol>
 *   <li><b>Yikama ticareti:</b> A 1 adet toprak icin siparis acar, B doldurur,
 *       sonra roller degisir. Para iki kisi arasinda gidip gelir, ikisi de XP
 *       kazanir. Makroyla dakikada yuzlerce kez tekrarlanabilir.</li>
 *   <li><b>Alt hesap:</b> Ayni kisi ikinci hesabiyla ayni donguyu kurar; para
 *       hic kaybolmaz.</li>
 *   <li><b>Ac-iptal et:</b> Siparis acmak XP verir, iptal etmek parayi geri
 *       verir. Vergi 0 ise bedava sonsuz XP demektir.</li>
 *   <li><b>Dev siparis + iptal:</b> XP para ile olcekli oldugu icin 10 milyonluk
 *       bir siparis acip hemen iptal etmek tek hamlede seviye atlatir.</li>
 *   <li><b>Bolme:</b> 64 esyalik tek siparis yerine 1 esyalik 64 siparis acarak
 *       sabit "siparis acma" XP'sini katlamak.</li>
 * </ol>
 *
 * <h2>Nasil kapatiliyor</h2>
 * <ul>
 *   <li>{@code creation-xp-mode: ON_COMPLETE} — siparis XP'si acilista degil,
 *       siparis <b>gercekten dolunca</b> verilir. 3. ve 4. maddeyi kokten bitirir
 *       ve hicbir kayit tutmayi gerektirmez: sunucu yeniden baslasa bile acik
 *       geri gelmez.</li>
 *   <li>Partner limiti — ayni kisiyle yapilan ticaretlerden alinan XP her
 *       tekrarda azalir, gunluk bir tavana takilir ve iki ticaret arasinda en az
 *       bir bekleme suresi aranir.</li>
 *   <li>Ayni IP — teslim eden ile siparis sahibi ayni adresteyse XP verilmez.</li>
 *   <li>Asgari deger — kurusluk siparisler hic XP uretmez.</li>
 * </ul>
 *
 * <p><b>Hicbiri zorunlu degildir.</b> {@code anti-abuse.enabled: false} tum
 * kontrolleri kapatir; her madde ayrica tek tek kapatilabilir. Kapali bir
 * kontrolun maliyeti yoktur.</p>
 */
public final class AntiAbuse {

    /** Siparis olusturma XP'si ne zaman verilir. */
    public enum CreationMode {
        /** Siparis tamamen dolunca (guvenli, varsayilan). */
        ON_COMPLETE,
        /** Siparis acilir acilmaz (eski davranis; iptal istismarina aciktir). */
        IMMEDIATE
    }

    private final OrderPlugin plugin;

    private boolean enabled = true;
    private CreationMode creationMode = CreationMode.ON_COMPLETE;

    private boolean partnerLimitEnabled = true;

    private boolean blockSameIp = true;
    private boolean blockSelfDelivery = true;

    private double minOrderValue = 100d;
    private int minOrderAmount = 16;

    private boolean logSuspicious = true;

    /** Tekrar eden ticaret matematigi — saf, test edilebilir. */
    private final PartnerThrottle throttle =
            new PartnerThrottle(60_000L, 3, 0.5d, 100d, 86_400_000L);

    public AntiAbuse(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ yukleme

    public void load(ConfigurationSection section) {
        throttle.clear();
        if (section == null) {
            // Bolum hic yoksa guvenli varsayilanlarla calisilir; sessizce
            // korumasiz kalmak yanlis olurdu.
            enabled = true;
            return;
        }

        enabled = section.getBoolean("enabled", true);
        if (!enabled) {
            plugin.getLogger().info("Seviye istismar korumasi KAPALI (levels.yml -> anti-abuse.enabled: false).");
            return;
        }

        String mode = section.getString("creation-xp-mode", "ON_COMPLETE");
        try {
            creationMode = CreationMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("anti-abuse.creation-xp-mode '" + mode
                    + "' gecersiz (ON_COMPLETE veya IMMEDIATE), ON_COMPLETE kullaniliyor.");
            creationMode = CreationMode.ON_COMPLETE;
        }

        partnerLimitEnabled = section.getBoolean("partner-limit.enabled", true);
        throttle.configure(
                Math.max(0L, section.getLong("partner-limit.cooldown-seconds", 60L)) * 1000L,
                Math.max(0, section.getInt("partner-limit.full-reward-count", 3)),
                section.getDouble("partner-limit.decay", 0.5d),
                section.getDouble("partner-limit.daily-xp-cap", 100d),
                Math.max(60L, section.getLong("partner-limit.window-hours", 24L) * 3600L) * 1000L);

        blockSameIp = section.getBoolean("block-same-ip", true);
        blockSelfDelivery = section.getBoolean("block-self-delivery", true);

        minOrderValue = section.getDouble("min-order-value", 100d);
        minOrderAmount = section.getInt("min-order-amount", 16);

        logSuspicious = section.getBoolean("log-suspicious", true);

        plugin.getLogger().info("Seviye istismar korumasi acik (siparis XP'si: " + creationMode
                + ", partner limiti: " + (partnerLimitEnabled ? "acik" : "kapali") + ").");
    }

    // ------------------------------------------------------------------ sorgular

    public boolean enabled() {
        return enabled;
    }

    /**
     * Siparis olusturma XP'si acilista mi verilecek?
     *
     * <p>Koruma kapaliysa eski davranis (acilista) gecerlidir: kapatan sunucu
     * sahibi "hicbir kontrol istemiyorum" demistir.</p>
     */
    public boolean awardCreationImmediately() {
        return !enabled || creationMode == CreationMode.IMMEDIATE;
    }

    /** Siparis dolunca acilis XP'si de odenecek mi? */
    public boolean awardCreationOnComplete() {
        return enabled && creationMode == CreationMode.ON_COMPLETE;
    }

    /**
     * Bu siparis XP uretmeye deger mi (asgari buyukluk kontrolu)?
     *
     * <p>Kurusluk ve tek esyalik siparisler hicbir ekonomik hareket uretmez ama
     * sabit XP kalemlerini (acma/tamamlama) sonsuz kez tetikleyebilir.</p>
     */
    public boolean orderQualifies(int amount, double pricePerItem) {
        if (!enabled) return true;
        if (minOrderAmount > 0 && amount < minOrderAmount) return false;
        return !(minOrderValue > 0d) || amount * pricePerItem >= minOrderValue;
    }

    /**
     * Teslim XP'sinin katsayisi: 1.0 tam odul, 0.0 hic XP yok.
     *
     * <p>Ayni kisiyle tekrar tekrar ticaret yapmak kademeli olarak degersizlesir.
     * Tamamen yasaklamak yerine azaltmak bilincli bir tercih: iki arkadasin
     * <b>gercekten</b> birbirinden alisveris yapmasi mesru, sadece bunu sonsuz
     * kez tekrarlamak degil.</p>
     *
     * @param deliverer teslim eden oyuncu
     * @param owner     siparis sahibi
     */
    public double deliveryMultiplier(Player deliverer, UUID owner) {
        if (!enabled || deliverer == null || owner == null) return 1d;

        UUID self = deliverer.getUniqueId();
        if (self.equals(owner)) {
            // Normalde ust katman engelliyor; yine de XP verilmemeli.
            return blockSelfDelivery ? 0d : 1d;
        }

        if (blockSameIp && sameAddress(deliverer, owner)) {
            flag(deliverer, "ayni IP adresinden teslim (alt hesap suphesi)");
            return 0d;
        }

        if (!partnerLimitEnabled) return 1d;

        PartnerThrottle.Decision decision = throttle.evaluate(self, owner, System.currentTimeMillis());
        if (!decision.rewarded()) {
            flag(deliverer, switch (decision.reason()) {
                case COOLDOWN -> "ayni kisiyle cok kisa arayla tekrar ticaret";
                case DAILY_CAP -> "bu partnerden gunluk XP tavanina ulasildi";
                default -> "ayni kisiyle tekrarlanan ticaret degersizlesti";
            });
        }
        return decision.multiplier();
    }

    /** Verilen XP'yi partner hesabina isler (gunluk tavan icin). */
    public void recordPartnerXp(UUID self, UUID partner, double xp) {
        if (!enabled || !partnerLimitEnabled) return;
        throttle.record(self, partner, xp);
    }

    /** Oyuncu cikisinda kaydini birakma — pencere zaten zaman tabanli. */
    public void forget(UUID playerId) {
        throttle.forget(playerId);
    }

    // ------------------------------------------------------------------ yardimcilar

    /**
     * Iki oyuncunun ayni adresten baglanip baglanmadigi.
     *
     * <p>Siparis sahibi cevrimdisiysa adresi bilinemez ve kontrol <b>gecer</b>:
     * bilinmeyeni sucluyor duruma dusmemek gerekir, aksi halde cevrimdisi birine
     * teslim eden herkes XP kaybederdi.</p>
     */
    private boolean sameAddress(Player deliverer, UUID owner) {
        Player other = deliverer.getServer().getPlayer(owner);
        if (other == null || !other.isOnline()) return false;
        String a = addressOf(deliverer);
        String b = addressOf(other);
        return a != null && a.equals(b);
    }

    private static String addressOf(Player player) {
        try {
            java.net.InetSocketAddress address = player.getAddress();
            if (address == null || address.getAddress() == null) return null;
            return address.getAddress().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private void flag(Player player, String reason) {
        if (!logSuspicious || player == null) return;
        plugin.getLogger().info("[Seviye] " + player.getName() + " XP alamadi: " + reason + ".");
    }

    /** Yonetici paneli icin ozet. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("enabled=" + enabled);
        out.add("creation-xp-mode=" + creationMode);
        out.add("partner-limit=" + partnerLimitEnabled);
        out.add("block-same-ip=" + blockSameIp);
        return out;
    }
}
