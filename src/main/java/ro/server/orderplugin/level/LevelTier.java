package ro.server.orderplugin.level;

import java.util.List;

/**
 * {@code levels.yml} icindeki tek bir seviye tanimi.
 *
 * <p>Alanlarin hicbiri kodda sabit degildir; bir alan dosyada yoksa o seviyede
 * o bonus sifirdir. Seviye <b>sayisi</b> da sabit degil: dosyaya kac tane
 * yazilirsa o kadar seviye vardir.</p>
 *
 * @param level          seviye numarasi
 * @param xpRequired     bu seviyeye ulasmak icin gereken TOPLAM xp
 * @param taxDiscount    vergiden yuzde indirim (0-100)
 * @param extraOrders    aktif siparis limitine eklenir
 * @param extraItems     siparis basina esya sayisina eklenir
 * @param rewardCommands seviye atlayinca konsolda calisir ({@code %player%})
 * @param broadcast      sunucuya duyurulsun mu
 */
public record LevelTier(int level, double xpRequired, double taxDiscount,
                        int extraOrders, int extraItems,
                        List<String> rewardCommands, boolean broadcast) {

    /** Seviye tablosu bos kaldiginda kullanilan guvenli taban. */
    public static LevelTier base() {
        return new LevelTier(1, 0d, 0d, 0, 0, List.of(), false);
    }
}
