package ro.server.orderplugin.model;

import org.bukkit.Material;
import ro.server.orderplugin.util.MaterialCategoryCache;

public enum FilterType {
    ALL("All", 0xFF),
    BLOCKS("Blocks", MaterialCategoryCache.CATEGORY_BLOCK),
    TOOLS("Tools", MaterialCategoryCache.CATEGORY_TOOL),
    FOOD("Food", MaterialCategoryCache.CATEGORY_FOOD),
    COMBAT("Combat", MaterialCategoryCache.CATEGORY_COMBAT),
    POTIONS("Potions", MaterialCategoryCache.CATEGORY_POTION),
    BOOKS("Books", MaterialCategoryCache.CATEGORY_BOOK),
    INGREDIENTS("Ingredients", MaterialCategoryCache.CATEGORY_INGREDIENT),
    UTILITIES("Utilities", MaterialCategoryCache.CATEGORY_UTILITY);

    private final String displayName;
    private final int categoryFlag;

    FilterType(String displayName, int categoryFlag) {
        this.displayName = displayName;
        this.categoryFlag = categoryFlag;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    /** Dil dosyasindaki {@code gui.lore.filter-<anahtar>} adi. */
    public String messageKey() {
        return "filter-" + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public FilterType next() {
        FilterType[] values = FilterType.values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public boolean matches(Material material) {
        if (this == ALL) return true;
        if (material == null) return false;
        return MaterialCategoryCache.matchesFilter(material, this.categoryFlag);
    }
}
