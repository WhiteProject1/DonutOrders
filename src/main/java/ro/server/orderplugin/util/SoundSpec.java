package ro.server.orderplugin.util;

/**
 * The full definition of a sound: vanilla sound key, volume, and pitch.
 *
 * <p>Written on a single line in config:</p>
 * <pre>
 * sound: "ui.button.click"              # volume 1.0, pitch 1.0
 * sound: "ui.button.click:0.5"          # volume 0.5
 * sound: "ui.button.click:0.5:1.4"      # volume 0.5, pitch 1.4
 * sound: "none"                         # silent
 * </pre>
 *
 * <p>The sound key is the <b>vanilla name</b> ({@code ui.button.click}), not
 * Bukkit's {@code Sound} enum. Using the enum would break the plugin every
 * time a sound gets renamed between Minecraft versions.</p>
 */
public record SoundSpec(String key, float volume, float pitch) {

    /** Definition that plays no sound. */
    public static final SoundSpec NONE = new SoundSpec(null, 0f, 0f);

    public boolean silent() {
        return key == null || key.isBlank() || key.equalsIgnoreCase("none");
    }

    /**
     * @param raw               raw text from config (may be null)
     * @param fallback          value to return if {@code raw} is empty/null
     * @param defaultVolume     value to use if no volume is written in the text
     * @param defaultPitch      value to use if no pitch is written in the text
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
     * An invalid number shouldn't break the menu: a typo'd volume/pitch
     * value silently falls back to the default (the sound isn't lost entirely).
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
