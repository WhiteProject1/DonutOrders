package ro.server.orderplugin.level;

import java.util.UUID;

/**
 * Tek bir oyuncunun seviye kaydi.
 *
 * @param xp         toplam kazanilmis xp
 * @param level      onbellege alinmis seviye (xp'den turetilir, kaydedilir ki
 *                   seviye tablosu degistiginde "dustu/yukseldi" farki gorulebilsin)
 * @param dailyXp    bugun kazanilan xp ({@code daily-cap} icin)
 * @param dailyReset gunluk sayacin sifirlandigi zaman (epoch ms)
 */
public record PlayerLevel(UUID uuid, double xp, int level, double dailyXp, long dailyReset) {

    public static PlayerLevel fresh(UUID uuid) {
        return new PlayerLevel(uuid, 0d, 1, 0d, System.currentTimeMillis());
    }

    public PlayerLevel withXp(double newXp, int newLevel, double newDailyXp, long newDailyReset) {
        return new PlayerLevel(uuid, newXp, newLevel, newDailyXp, newDailyReset);
    }
}
