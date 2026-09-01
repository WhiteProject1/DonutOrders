package ro.server.orderplugin.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import ro.server.orderplugin.OrderPlugin;

/**
 * Tum vergi hesabinin tek adresi.
 *
 * <p>Vergi kapaliyken {@link #calculate} her zaman sifir doner. Bu bilincli:
 * cagiran taraflarin "vergi acik mi" diye ayrica sormasi gerekmez, boylece bir
 * yerde kontrol unutulunca yanlislikla para kesilmez.</p>
 *
 * <p>Oran zinciri (hepsi {@code config.yml -> tax}):</p>
 * <ol>
 *   <li>{@code enabled: false} -&gt; %0</li>
 *   <li>muafiyet izni -&gt; %0</li>
 *   <li>taban oran (islem turune gore)</li>
 *   <li>rutbe orani daha dusukse onu al</li>
 *   <li>rutbe indirimi uygula</li>
 *   <li>seviye indirimi uygula</li>
 *   <li>min/max mutlak tutar sinirlari</li>
 * </ol>
 */
public final class TaxService {

    private final OrderPlugin plugin;

    public TaxService(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param rate   uygulanan yuzde (5.0 = %5)
     * @param amount kesilen tutar
     * @param total  ara toplam + vergi
     * @param reason neden bu oran uygulandi: disabled | exempt | base | rank | level
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
        // Alt sinir yalnizca vergi zaten alinacaksa gecerli: %0 oran "vergi yok"
        // demektir, alt sinir yuzunden birden para kesilmemeli.
        if (min >= 0d && amount > 0d && amount < min) amount = min;
        if (max >= 0d && amount > max) amount = max;

        return new TaxResult(subtotal, percent, amount, subtotal + amount, reason);
    }

    /**
     * Seviye sisteminden gelen indirim.
     *
     * <p>Seviye sistemi henuz yuklenmediyse ya da kapaliysa 0 doner; vergi
     * hesabi seviye sistemine bagimli olmadan calisir.</p>
     */
    private double levelDiscount(Player player) {
        if (player == null || plugin.levels() == null || !plugin.levels().enabled()) return 0d;
        return plugin.levels().taxDiscountPercent(player);
    }

    /**
     * Toplanan vergiyi yerine yatirir.
     *
     * <p>{@code VOID} (varsayilan) hicbir sey yapmaz — para ekonomiden silinir,
     * bu enflasyonu dizginler. {@code ACCOUNT} secilirse tutar yapilandirilan
     * hesaba gecer.</p>
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
