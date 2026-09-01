package ro.server.orderplugin.level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Ayni kisiyle tekrar eden ticaret" kisitlamasinin saf matematigi.
 *
 * <p>Bukkit'e hicbir bagi yoktur ve zamani disaridan alir. Bunun sebebi
 * dogrudan test edilebilmesi: bir XP ciftciligi korumasinin "herhalde
 * calisiyordur" ile gecistirilmesi, korumanin hic olmamasindan farksizdir —
 * sessizce yanlis calisan bir koruma yanlis bir guven verir.</p>
 *
 * <p>Uc kural birlikte calisir:</p>
 * <ol>
 *   <li><b>Bekleme:</b> ayni kisiyle iki XP'li ticaret arasinda en az
 *       {@code cooldownMs} gecmeli.</li>
 *   <li><b>Azalma:</b> ilk {@code fullRewardCount} ticaret tam odul verir;
 *       sonrakiler her seferinde {@code decay} kati kadar deger tasir.</li>
 *   <li><b>Gunluk tavan:</b> tek bir partnerden 24 saatte en fazla
 *       {@code dailyXpCap} xp alinabilir.</li>
 * </ol>
 *
 * <p>Engellenen ticaretler de <b>kaydedilir</b>. Aksi halde oyuncu tavana
 * ulastiktan sonra beklemeden devam edip sayacin dolmasini engelleyebilirdi:
 * yalnizca odullendirilen ticaretleri saymak, istismari saymamak demek olurdu.</p>
 */
public final class PartnerThrottle {

    /** Gunluk tavanin sifirlanma araligi. */
    public static final long DAY_MS = 86_400_000L;

    private static final class Log {
        final Deque<Long> times = new ArrayDeque<>();
        double xpToday;
        long dayStart;
    }

    private final Map<UUID, Map<UUID, Log>> logs = new HashMap<>();

    private long cooldownMs;
    private int fullRewardCount;
    private double decay;
    private double dailyXpCap;
    private long windowMs;

    public PartnerThrottle(long cooldownMs, int fullRewardCount, double decay,
                           double dailyXpCap, long windowMs) {
        configure(cooldownMs, fullRewardCount, decay, dailyXpCap, windowMs);
    }

    public void configure(long cooldownMs, int fullRewardCount, double decay,
                          double dailyXpCap, long windowMs) {
        this.cooldownMs = Math.max(0L, cooldownMs);
        this.fullRewardCount = Math.max(0, fullRewardCount);
        this.decay = decay < 0d ? 0d : Math.min(decay, 1d);
        this.dailyXpCap = dailyXpCap;
        this.windowMs = Math.max(1000L, windowMs);
    }

    /** Neden odul verilmedigini soyler — gunluge yazmak ve mesaj secmek icin. */
    public enum Reason { FULL, DECAYED, COOLDOWN, DAILY_CAP }

    /** Karar: katsayi + gerekce. */
    public record Decision(double multiplier, Reason reason) {
        public boolean rewarded() {
            return multiplier > 0d;
        }
    }

    /**
     * Bu ticaretin XP katsayisini hesaplar ve ticareti kaydeder.
     *
     * @param now cagiranin sagladigi zaman (test edilebilirlik icin)
     */
    public synchronized Decision evaluate(UUID self, UUID partner, long now) {
        Log log = logs.computeIfAbsent(self, k -> new HashMap<>())
                .computeIfAbsent(partner, k -> {
                    Log fresh = new Log();
                    fresh.dayStart = now;
                    return fresh;
                });

        // Pencere disina cikan ticaretler unutulur.
        while (!log.times.isEmpty() && now - log.times.peekFirst() > windowMs) {
            log.times.pollFirst();
        }
        if (now - log.dayStart >= DAY_MS) {
            log.dayStart = now;
            log.xpToday = 0d;
        }

        Long last = log.times.peekLast();
        int count = log.times.size();
        log.times.addLast(now);

        if (last != null && cooldownMs > 0L && now - last < cooldownMs) {
            return new Decision(0d, Reason.COOLDOWN);
        }
        if (dailyXpCap >= 0d && log.xpToday >= dailyXpCap) {
            return new Decision(0d, Reason.DAILY_CAP);
        }
        if (count < fullRewardCount) {
            return new Decision(1d, Reason.FULL);
        }
        double multiplier = Math.pow(decay, count - fullRewardCount + 1d);
        // %1'in altina inen odul pratikte sifirdir; sayiyi surunduren bir kuyruk
        // birakmak yerine acikca bitirilir.
        return multiplier < 0.01d
                ? new Decision(0d, Reason.DECAYED)
                : new Decision(multiplier, Reason.DECAYED);
    }

    /** Verilen xp'yi gunluk tavana isler. */
    public synchronized void record(UUID self, UUID partner, double xp) {
        if (xp <= 0d) return;
        Map<UUID, Log> mine = logs.get(self);
        if (mine == null) return;
        Log log = mine.get(partner);
        if (log != null) log.xpToday += xp;
    }

    public synchronized void forget(UUID playerId) {
        logs.remove(playerId);
        for (Map<UUID, Log> mine : logs.values()) {
            mine.remove(playerId);
        }
    }

    public synchronized void clear() {
        logs.clear();
    }
}
