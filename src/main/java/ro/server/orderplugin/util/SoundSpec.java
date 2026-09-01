package ro.server.orderplugin.util;

/**
 * Bir sesin tam tanimi: vanilya ses anahtari, ses seviyesi ve perde.
 *
 * <p>Yapilandirmada tek satirda yazilir:</p>
 * <pre>
 * sound: "ui.button.click"              # seviye 1.0, perde 1.0
 * sound: "ui.button.click:0.5"          # seviye 0.5
 * sound: "ui.button.click:0.5:1.4"      # seviye 0.5, perde 1.4
 * sound: "none"                         # sessiz
 * </pre>
 *
 * <p>Ses anahtari <b>vanilya adidir</b> ({@code ui.button.click}), Bukkit'in
 * {@code Sound} enum'u degil. Enum kullanilsaydi Minecraft surumleri arasinda
 * yeniden adlandirilan her ses eklentiyi bozardi.</p>
 */
public record SoundSpec(String key, float volume, float pitch) {

    /** Hicbir ses calmayan tanim. */
    public static final SoundSpec NONE = new SoundSpec(null, 0f, 0f);

    public boolean silent() {
        return key == null || key.isBlank() || key.equalsIgnoreCase("none");
    }

    /**
     * @param raw               yapilandirmadaki ham metin (null olabilir)
     * @param fallback          {@code raw} bos/null ise donecek deger
     * @param defaultVolume     metinde seviye yazilmamissa kullanilacak deger
     * @param defaultPitch      metinde perde yazilmamissa kullanilacak deger
     */
    public static SoundSpec parse(String raw, SoundSpec fallback, float defaultVolume, float defaultPitch) {
        if (raw == null || raw.isBlank()) return fallback;

        String[] parts = raw.trim().split(":");
        String key = parts[0].trim();
        if (key.isEmpty() || key.equalsIgnoreCase("none")) return NONE;

        float volume = defaultVolume;
        float pitch = defaultPitch;
        if (parts.length > 1) volume = parseFloat(parts[1], defaultVolume);
        if (parts.length > 2) pitch = parseFloat(parts[2], defaultPitch);
        return new SoundSpec(key, volume, pitch);
    }

    public static SoundSpec parse(String raw, SoundSpec fallback) {
        return parse(raw, fallback, 1f, 1f);
    }

    /**
     * Gecersiz sayi menuyu bozmamali: yazim hatasi olan bir seviye/perde
     * degeri sessizce varsayilana duser (ses tamamen kaybolmaz).
     */
    private static float parseFloat(String raw, float fallback) {
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return silent() ? "none" : key + ":" + volume + ":" + pitch;
    }
}
