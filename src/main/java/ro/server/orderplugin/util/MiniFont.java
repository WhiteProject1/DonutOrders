package ro.server.orderplugin.util;

import java.util.Map;

/**
 * Metni "kucuk buyuk harf" (small caps) gorunumune cevirir: {@code Order -> ᴏʀᴅᴇʀ}.
 *
 * <p>Harf tablosu yapilandirmadan gelir ({@code text.minifont.map}); tabloda
 * olmayan karakter oldugu gibi birakilir. Boylece rakamlar, noktalama ve
 * sunucu sahibinin istemedigi harfler degismez.</p>
 *
 * <p>Iki sey <b>hicbir zaman</b> donusturulmez:</p>
 * <ul>
 *   <li><b>Renk kodlari</b> ({@code &a}, {@code §a}, {@code &#RRGGBB}) — donusturulurse
 *       renk bozulur ve metinde ham kod gorunur.</li>
 *   <li><b>Yer tutucu adlari</b> ({@code %player%}) — donusturulurse
 *       {@code text.replace("%player%", ...)} artik eslesmez ve oyuncu ekranda
 *       ham yer tutucuyu gorur.</li>
 * </ul>
 *
 * <p>Bu sinif yer tutucu <i>degerlerine</i> de dokunmaz, cunku donusum
 * degistirme isleminden <b>once</b> uygulanir: sabit etiket kucuk harfe doner,
 * icine yazilan oyuncu adi / fiyat okunakli kalir.</p>
 */
public final class MiniFont {

    /** Renk kodu isaretcisi (U+00A7). pom.xml sourceEncoding=UTF-8 oldugu icin duz yazilabilir. */
    private static final char SECTION = '§';

    private MiniFont() {
    }

    /**
     * @param input ham metin (renk kodlari ve yer tutucular icerebilir)
     * @param map   kucuk harf -> gosterilecek karakter; bos ise metin degismez
     */
    public static String apply(String input, Map<String, String> map) {
        if (input == null || input.isEmpty() || map == null || map.isEmpty()) return input;

        StringBuilder out = new StringBuilder(input.length() + 8);
        int length = input.length();
        int i = 0;
        while (i < length) {
            char c = input.charAt(i);

            // Renk kodu: isaretci + bir karakter aynen gecer.
            if ((c == '&' || c == SECTION) && i + 1 < length) {
                // &#RRGGBB bicimli hex renkler de korunur.
                if (input.charAt(i + 1) == '#' && i + 8 <= length) {
                    out.append(input, i, Math.min(i + 8, length));
                    i = Math.min(i + 8, length);
                    continue;
                }
                out.append(c).append(input.charAt(i + 1));
                i += 2;
                continue;
            }

            // Yer tutucu: %ad% arasi aynen gecer. Kapanis yuzdesi yoksa (metinde
            // tek basina duran % isareti) normal karakter gibi islenir.
            if (c == '%') {
                int end = input.indexOf('%', i + 1);
                if (end > i && isPlaceholderName(input, i + 1, end)) {
                    out.append(input, i, end + 1);
                    i = end + 1;
                    continue;
                }
            }

            String mapped = map.get(String.valueOf(Character.toLowerCase(c)));
            out.append(mapped != null ? mapped : String.valueOf(c));
            i++;
        }
        return out.toString();
    }

    /**
     * {@code %...%} arasindaki metin gercekten bir yer tutucu adi mi?
     *
     * <p>Yer tutucu adlari harf/rakam/alt cizgi/tire icerir. "%50 indirim%" gibi
     * bir cumle yanlislikla korunmasin diye bosluk kabul edilmez.</p>
     */
    private static boolean isPlaceholderName(String text, int from, int to) {
        if (to - from == 0 || to - from > 40) return false;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }
}
