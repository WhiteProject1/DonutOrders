package ro.server.orderplugin.util;

import org.bukkit.Material;

public final class MaterialCategoryCache {

    public static final int CATEGORY_BLOCK      = 1;
    public static final int CATEGORY_TOOL       = 1 << 1;
    public static final int CATEGORY_FOOD       = 1 << 2;
    public static final int CATEGORY_COMBAT     = 1 << 3;
    public static final int CATEGORY_POTION     = 1 << 4;
    public static final int CATEGORY_BOOK       = 1 << 5;
    public static final int CATEGORY_INGREDIENT = 1 << 6;
    public static final int CATEGORY_UTILITY    = 1 << 7;

    private static final int[] CATEGORY_TABLE = new int[Material.values().length];

    static {
        for (Material mat : Material.values()) {
            int flags = 0;
            String name = mat.name();

            if (mat.isBlock()) flags |= CATEGORY_BLOCK;
            if (mat.isEdible()) flags |= CATEGORY_FOOD;

            // Tools
            if (name.contains("PICKAXE") || name.contains("AXE") || name.contains("SHOVEL")
                || name.contains("HOE") || name.contains("SHEARS") || name.contains("FLINT_AND_STEEL")
                || name.contains("FISHING_ROD") || name.contains("BRUSH") || name.contains("SPYGLASS")) {
                flags |= CATEGORY_TOOL;
            }

            // Combat
            if (name.contains("SWORD") || name.contains("BOW") || name.contains("CROSSBOW")
                || name.contains("ARROW") || name.contains("SHIELD") || name.contains("TRIDENT")
                || name.contains("HELMET") || name.contains("CHESTPLATE") || name.contains("LEGGINGS")
                || name.contains("BOOTS") || name.contains("TOTEM") || name.contains("MACE")) {
                flags |= CATEGORY_COMBAT;
            }

            // Potions
            if (name.contains("POTION") || mat == Material.GLASS_BOTTLE || mat == Material.DRAGON_BREATH) {
                flags |= CATEGORY_POTION;
            }

            // Books
            if (name.contains("BOOK") || mat == Material.WRITABLE_BOOK || mat == Material.WRITTEN_BOOK
                || mat == Material.ENCHANTED_BOOK || mat == Material.KNOWLEDGE_BOOK) {
                flags |= CATEGORY_BOOK;
            }

            // Ingredients
            if (name.contains("DYE") || mat == Material.BLAZE_POWDER || mat == Material.BLAZE_ROD
                || mat == Material.GHAST_TEAR || mat == Material.MAGMA_CREAM || mat == Material.NETHER_WART
                || mat == Material.SPIDER_EYE || mat == Material.FERMENTED_SPIDER_EYE
                || mat == Material.GLISTERING_MELON_SLICE || mat == Material.RABBIT_FOOT
                || mat == Material.PHANTOM_MEMBRANE || mat == Material.GUNPOWDER || mat == Material.REDSTONE
                || mat == Material.GLOWSTONE_DUST || mat == Material.SUGAR || mat == Material.SLIME_BALL
                || mat == Material.HONEY_BOTTLE || mat == Material.INK_SAC || mat == Material.GLOW_INK_SAC
                || mat == Material.LAPIS_LAZULI || mat == Material.BONE_MEAL) {
                flags |= CATEGORY_INGREDIENT;
            }

            // Utilities
            if (mat == Material.ENDER_PEARL || mat == Material.ENDER_EYE || mat == Material.FIREWORK_ROCKET
                || mat == Material.LEAD || mat == Material.NAME_TAG || mat == Material.SADDLE
                || mat == Material.ELYTRA || mat == Material.COMPASS || mat == Material.CLOCK
                || mat == Material.MAP || mat == Material.FILLED_MAP || mat == Material.BUCKET
                || name.contains("BUCKET") || name.contains("MINECART") || name.contains("BOAT")
                || mat == Material.TNT) {
                flags |= CATEGORY_UTILITY;
            }

            CATEGORY_TABLE[mat.ordinal()] = flags;
        }
    }

    private MaterialCategoryCache() {}

    public static boolean matchesFilter(Material material, int categoryFlag) {
        int ordinal = material.ordinal();
        if (ordinal >= CATEGORY_TABLE.length) return false;
        return (CATEGORY_TABLE[ordinal] & categoryFlag) != 0;
    }
}
