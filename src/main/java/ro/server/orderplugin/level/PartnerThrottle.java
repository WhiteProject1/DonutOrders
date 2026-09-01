package ro.server.orderplugin.level;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The pure math of the "repeated trade with the same person" restriction.
 *
 * <p>Has no dependency on Bukkit and takes time as a parameter. The reason is
 * direct testability: shrugging off an XP-farming protection with "it probably
 * works" is no different from not having the protection at all — a check that
 * silently misbehaves gives false confidence.</p>
 *
 * <p>Three rules work together:</p>
 * <ol>
 *   <li><b>Cooldown:</b> at least {@code cooldownMs} must pass between two
 *       XP-granting trades with the same person.</li>
 *   <li><b>Decay:</b> the first {@code fullRewardCount} trades pay full reward;
 *       each one after that is worth {@code decay} times as much.</li>
 *   <li><b>Daily cap:</b> at most {@code dailyXpCap} xp can be earned from a
 *       single partner in 24 hours.</li>
 * </ol>
 *
 * <p>Blocked trades are <b>recorded too</b>. Otherwise a player could keep
 * trading without waiting once they hit the cap and prevent the counter from
 * filling up: counting only the rewarded trades and not the abuse would defeat
 * the purpose.</p>
 */
public final class PartnerThrottle {

    /** Interval at which the daily cap resets. */
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

    /** Tells why the reward wasn't granted — for logging and picking a message. */
    public enum Reason { FULL, DECAYED, COOLDOWN, DAILY_CAP }

    /** Decision: coefficient + reason. */
    public record Decision(double multiplier, Reason reason) {
        public boolean rewarded() {
            return multiplier > 0d;
        }
    }

    /**
     * Computes the XP coefficient for this trade and records the trade.
     *
     * @param now time supplied by the caller (for testability)
     */
    public synchronized Decision evaluate(UUID self, UUID partner, long now) {
        Log log = logs.computeIfAbsent(self, k -> new HashMap<>())
                .computeIfAbsent(partner, k -> {
                    Log fresh = new Log();
                    fresh.dayStart = now;
                    return fresh;
                });

        // Trades that fall outside the window are forgotten.
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
        // A reward that decays below 1% is practically zero; cut it off
        // explicitly instead of leaving a queue that drags the count out forever.
        return multiplier < 0.01d
                ? new Decision(0d, Reason.DECAYED)
                : new Decision(multiplier, Reason.DECAYED);
    }

    /** Records the granted xp against the daily cap. */
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
