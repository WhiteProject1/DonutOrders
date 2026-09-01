package ro.server.orderplugin.level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import ro.server.orderplugin.OrderPlugin;

/**
 * Level system abuse protection.
 *
 * <h2>The exploit being closed</h2>
 * <p>The level system rewards "economic activity". But two friends can level
 * each other up without producing any real economic activity:</p>
 * <ol>
 *   <li><b>Wash trading:</b> A opens an order for 1 dirt, B fills it, then
 *       they swap roles. Money goes back and forth between the two of them,
 *       both get XP. Can be repeated hundreds of times a minute with a macro.</li>
 *   <li><b>Alt account:</b> The same person sets up the same loop with a second
 *       account; the money never actually gets lost.</li>
 *   <li><b>Open-and-cancel:</b> Opening an order grants XP, canceling it refunds
 *       the money. If tax is 0, that's free infinite XP.</li>
 *   <li><b>Huge order + cancel:</b> Since XP scales with money, opening a
 *       10-million order and canceling it immediately levels up in one move.</li>
 *   <li><b>Splitting:</b> Opening 64 one-item orders instead of a single
 *       64-item order to multiply the flat "order created" XP.</li>
 * </ol>
 *
 * <h2>How it's closed</h2>
 * <ul>
 *   <li>{@code creation-xp-mode: ON_COMPLETE} — order-creation XP isn't granted
 *       on open, it's granted when the order is <b>actually filled</b>. This
 *       kills items 3 and 4 outright and needs no bookkeeping at all: the
 *       exploit doesn't come back even after a server restart.</li>
 *   <li>Partner limit — XP earned from repeated trades with the same person
 *       decays with each repetition, hits a daily cap, and a minimum cooldown
 *       is enforced between two trades.</li>
 *   <li>Same IP — no XP if the deliverer and the order owner are on the same address.</li>
 *   <li>Minimum value — trivially small orders produce no XP at all.</li>
 * </ul>
 *
 * <p><b>None of this is mandatory.</b> {@code anti-abuse.enabled: false} turns
 * off all checks; each item can also be disabled individually. A disabled
 * check has no cost.</p>
 */
public final class AntiAbuse {

    /** When order-creation XP is granted. */
    public enum CreationMode {
        /** When the order is completely filled (safe, default). */
        ON_COMPLETE,
        /** As soon as the order is opened (old behavior; open to cancel-abuse). */
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

    /** Repeated-trade math — pure, testable. */
    private final PartnerThrottle throttle =
            new PartnerThrottle(60_000L, 3, 0.5d, 100d, 86_400_000L);

    public AntiAbuse(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ loading

    public void load(ConfigurationSection section) {
        throttle.clear();
        if (section == null) {
            // If the section is entirely absent, run with safe defaults; silently
            // ending up unprotected would be wrong.
            enabled = true;
            return;
        }

        enabled = section.getBoolean("enabled", true);
        if (!enabled) {
            plugin.getLogger().info("Level abuse protection DISABLED (levels.yml -> anti-abuse.enabled: false).");
            return;
        }

        String mode = section.getString("creation-xp-mode", "ON_COMPLETE");
        try {
            creationMode = CreationMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("anti-abuse.creation-xp-mode '" + mode
                    + "' is invalid (ON_COMPLETE or IMMEDIATE), using ON_COMPLETE.");
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

        plugin.getLogger().info("Level abuse protection enabled (order XP: " + creationMode
                + ", partner limit: " + (partnerLimitEnabled ? "on" : "off") + ").");
    }

    // ------------------------------------------------------------------ queries

    public boolean enabled() {
        return enabled;
    }

    /**
     * Should order-creation XP be granted on open?
     *
     * <p>If protection is disabled, the old behavior (on open) applies: a
     * server owner who turned it off has said "I don't want any checks."</p>
     */
    public boolean awardCreationImmediately() {
        return !enabled || creationMode == CreationMode.IMMEDIATE;
    }

    /** Should creation XP also be paid out when the order fills? */
    public boolean awardCreationOnComplete() {
        return enabled && creationMode == CreationMode.ON_COMPLETE;
    }

    /**
     * Is this order worth producing XP for (minimum-size check)?
     *
     * <p>Trivial and single-item orders produce no real economic activity but
     * can trigger the flat XP entries (open/complete) endlessly.</p>
     */
    public boolean orderQualifies(int amount, double pricePerItem) {
        if (!enabled) return true;
        if (minOrderAmount > 0 && amount < minOrderAmount) return false;
        return !(minOrderValue > 0d) || amount * pricePerItem >= minOrderValue;
    }

    /**
     * The coefficient for delivery XP: 1.0 full reward, 0.0 no XP at all.
     *
     * <p>Trading with the same person over and over becomes progressively
     * worthless. Decaying it instead of banning it outright is a deliberate
     * choice: two friends <b>genuinely</b> trading with each other is
     * legitimate, it's only endlessly repeating it that isn't.</p>
     *
     * @param deliverer the delivering player
     * @param owner     order owner
     */
    public double deliveryMultiplier(Player deliverer, UUID owner) {
        if (!enabled || deliverer == null || owner == null) return 1d;

        UUID self = deliverer.getUniqueId();
        if (self.equals(owner)) {
            // Normally the layer above blocks this; XP still shouldn't be granted either way.
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

    /** Records the granted XP against the partner's account (for the daily cap). */
    public void recordPartnerXp(UUID self, UUID partner, double xp) {
        if (!enabled || !partnerLimitEnabled) return;
        throttle.record(self, partner, xp);
    }

    /** No need to drop the record on player quit — the window is time-based anyway. */
    public void forget(UUID playerId) {
        throttle.forget(playerId);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Whether two players are connected from the same address.
     *
     * <p>If the order owner is offline, their address can't be known and the
     * check <b>passes</b>: an unknown must not be treated as guilty, otherwise
     * anyone delivering to an offline player would lose their XP.</p>
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
        plugin.getLogger().info("[Level] " + player.getName() + " did not receive XP: " + reason + ".");
    }

    /** Summary for the admin panel. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add("enabled=" + enabled);
        out.add("creation-xp-mode=" + creationMode);
        out.add("partner-limit=" + partnerLimitEnabled);
        out.add("block-same-ip=" + blockSameIp);
        return out;
    }
}
