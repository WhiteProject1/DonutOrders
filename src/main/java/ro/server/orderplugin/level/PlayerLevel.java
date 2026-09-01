package ro.server.orderplugin.level;

import java.util.UUID;

/**
 * A single player's level record.
 *
 * @param xp         total xp earned
 * @param level      cached level (derived from xp, but saved so that a
 *                   "went up/went down" difference is visible if the level table changes)
 * @param dailyXp    xp earned today (for {@code daily-cap})
 * @param dailyReset when the daily counter was last reset (epoch ms)
 */
public record PlayerLevel(UUID uuid, double xp, int level, double dailyXp, long dailyReset) {

    public static PlayerLevel fresh(UUID uuid) {
        return new PlayerLevel(uuid, 0d, 1, 0d, System.currentTimeMillis());
    }

    public PlayerLevel withXp(double newXp, int newLevel, double newDailyXp, long newDailyReset) {
        return new PlayerLevel(uuid, newXp, newLevel, newDailyXp, newDailyReset);
    }
}
