package ro.server.orderplugin.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import ro.server.orderplugin.OrderPlugin;

/**
 * The single place for all tax calculation.
 *
 * <p>When tax is disabled, {@link #calculate} always returns zero. This is
 * deliberate: callers don't need to separately ask "is tax on", so a forgotten
 * check somewhere else can't accidentally deduct money.</p>
 *
 * <p>The rate chain (all from {@code config.yml -> tax}):</p>
 * <ol>
 *   <li>{@code enabled: false} -&gt; 0%</li>
 *   <li>exemption permission -&gt; 0%</li>
 *   <li>base rate (by transaction type)</li>
 *   <li>if the rank rate is lower, use it instead</li>
 *   <li>apply the rank discount</li>
 *   <li>apply the level discount</li>
 *   <li>min/max absolute amount limits</li>
 * </ol>
 */
public final class TaxService {

    private final OrderPlugin plugin;

    public TaxService(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param rate   percentage applied (5.0 = 5%)
     * @param amount amount deducted
     * @param total  subtotal + tax
     * @param reason why this rate was applied: disabled | exempt | base | rank | level
     */
    public record TaxResult(double subtotal, double rate, double amount, double total, String reason) {

        public boolean charged() {
            return amount > 0d;
        }
    }

    public boolean enabled() {
        return plugin.settings().taxEnabled();
    }

    public TaxResult calculate(Player player, double subtotal, String type) {
        if (!enabled()) {
            return new TaxResult(subtotal, 0d, 0d, subtotal, "disabled");
        }
        if (player != null && player.hasPermission(plugin.settings().taxExemptPermission())) {
            return new TaxResult(subtotal, 0d, 0d, subtotal, "exempt");
        }

        double percent = plugin.settings().taxBasePercent(type);
        String reason = "base";

        double rankRate = plugin.settings().taxRankRate(player);
        if (!Double.isNaN(rankRate) && rankRate < percent) {
            percent = rankRate;
            reason = "rank";
        }

        double rankDiscount = plugin.settings().taxRankDiscount(player);
        if (rankDiscount > 0d) {
            percent = percent * (1d - rankDiscount / 100d);
            reason = "rank";
        }

        double levelDiscount = levelDiscount(player);
        if (levelDiscount > 0d) {
            percent = percent * (1d - levelDiscount / 100d);
            reason = "level";
        }

        if (percent < 0d) percent = 0d;

        double amount = subtotal * percent / 100d;
        double min = plugin.settings().taxMinAmount();
        double max = plugin.settings().taxMaxAmount();
        // The minimum only applies if tax is already being charged: a 0% rate
        // means "no tax", it shouldn't suddenly start deducting money because of the floor.
        if (min >= 0d && amount > 0d && amount < min) amount = min;
        if (max >= 0d && amount > max) amount = max;

        return new TaxResult(subtotal, percent, amount, subtotal + amount, reason);
    }

    /**
     * Discount coming from the level system.
     *
     * <p>Returns 0 if the level system hasn't loaded yet or is disabled; tax
     * calculation works without depending on the level system.</p>
     */
    private double levelDiscount(Player player) {
        if (player == null || plugin.levels() == null || !plugin.levels().enabled()) return 0d;
        return plugin.levels().taxDiscountPercent(player);
    }

    /**
     * Deposits collected tax to its destination.
     *
     * <p>{@code VOID} (default) does nothing — the money is removed from the
     * economy, which curbs inflation. If {@code ACCOUNT} is chosen, the amount
     * goes to the configured account.</p>
     */
    public void deposit(double amount) {
        if (amount <= 0d || !enabled()) return;
        if (!"ACCOUNT".equals(plugin.settings().taxDestination())) return;

        String name = plugin.settings().taxDestinationAccount();
        if (name == null || name.isBlank()) {
            plugin.getLogger().warning("tax.destination ACCOUNT ama tax.destination-account bos; vergi yok edildi.");
            return;
        }
        try {
            OfflinePlayer target = plugin.getServer().getOfflinePlayer(name);
            OrderPlugin.getEconomy().depositPlayer(target, amount);
        } catch (Exception e) {
            plugin.getLogger().warning("Vergi hesabina yatirilamadi (" + name + "): " + e.getMessage());
        }
    }
}
