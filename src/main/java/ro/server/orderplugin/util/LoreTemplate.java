package ro.server.orderplugin.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Yapilandirmadan gelen satir listesini ({@code lang/*.yml -> ...template})
 * gercek lore'a cevirir.
 *
 * <p>Iki tur yer tutucu vardir:</p>
 * <ul>
 *   <li><b>Skaler</b> ({@code %price%} gibi) — satir icinde gectigi yerde
 *       degeriyle degistirilir.</li>
 *   <li><b>Liste</b> ({@code %enchants%} gibi) — satirin <b>tamami</b> yalnizca
 *       bu yer tutucudan ibaretse, o tek satir listedeki eleman sayisi kadar
 *       satira genisler. Liste bossa satir tamamen <b>silinir</b> — geride bos
 *       bir satir birakmaz. Boylece buyusu olmayan bir siparis, buyu bolumunde
 *       bosluk birakmaz.</li>
 * </ul>
 *
 * <p>Taninmayan bir yer tutucu (yazim hatasi, silinmis anahtar) oldugu gibi
 * birakilir; sunucu sahibinin bir harf hatasi butun GUI'yi bozmamali.</p>
 *
 * <p>Renklendirme ({@code &#RRGGBB}, {@code &a}, {@code §a}) her satirda
 * {@link TextUtil#colorize} ile en sonda yapilir; yer tutucu degerleri
 * (ornegin oyuncunun renkli yazdigi bir esya adi) de boylece renklenir.</p>
 */
public final class LoreTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("%([A-Za-z0-9_-]+)%");

    private LoreTemplate() {
    }

    /**
     * @param template   yapilandirmadaki ham satirlar ({@code ''} kasti bos satir demektir)
     * @param values     skaler yer tutucu -> deger (renklendirilmemis, ham metin)
     * @param listValues liste yer tutucu -> hazir (zaten bicimlendirilmis) satirlar
     * @return renklendirilmis, oynamaya hazir lore
     */
    public static List<String> render(List<String> template, Map<String, String> values,
            Map<String, List<String>> listValues) {
        if (template == null || template.isEmpty()) return List.of();

        List<String> out = new ArrayList<>(template.size());
        for (String line : template) {
            if (line == null) continue;

            String listKey = pureListPlaceholder(line);
            if (listKey != null && listValues != null && listValues.containsKey(listKey)) {
                List<String> items = listValues.get(listKey);
                if (items != null) {
                    for (String item : items) {
                        if (item == null) continue;
                        out.add(TextUtil.colorize(item));
                    }
                }
                // Liste bos ise satir hic eklenmez -> geride bosluk kalmaz.
                continue;
            }

            out.add(TextUtil.colorize(substitute(line, values)));
        }
        return out;
    }

    /**
     * Satir, bastan sona (bosluklar haric) tek bir {@code %isim%} yer tutucusundan
     * ibaretse o ismi dondurur; degilse {@code null}.
     */
    private static String pureListPlaceholder(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 3 || trimmed.charAt(0) != '%' || trimmed.charAt(trimmed.length() - 1) != '%') {
            return null;
        }
        String inner = trimmed.substring(1, trimmed.length() - 1);
        if (inner.isEmpty() || inner.indexOf('%') >= 0) return null;
        return inner;
    }

    private static String substitute(String line, Map<String, String> values) {
        if (line.isEmpty() || values == null || values.isEmpty() || line.indexOf('%') < 0) return line;

        Matcher matcher = PLACEHOLDER.matcher(line);
        StringBuilder buffer = new StringBuilder(line.length() + 16);
        while (matcher.find()) {
            String replacement = values.get(matcher.group(1));
            // Bilinmeyen yer tutucu -> oldugu gibi birakilir (hata firlatmaz).
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
