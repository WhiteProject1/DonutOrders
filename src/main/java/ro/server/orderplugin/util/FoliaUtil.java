package ro.server.orderplugin.util;

public final class FoliaUtil {

    private static final boolean FOLIA;

    static {
        boolean detected;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            detected = true;
        } catch (ClassNotFoundException e) {
            detected = false;
        }
        FOLIA = detected;
    }

    private FoliaUtil() {
    }

    public static boolean isFolia() {
        return FOLIA;
    }
}
