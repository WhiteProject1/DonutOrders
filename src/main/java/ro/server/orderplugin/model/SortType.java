package ro.server.orderplugin.model;

public enum SortType {
    MOST_PAID("Most Paid"),
    MOST_DELIVERED("Most Delivered"),
    RECENTLY_LISTED("Recently Listed"),
    MOST_MONEY_PER_ITEM("Most Money Per Item");

    private final String displayName;

    SortType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    /**
     * The {@code gui.lore.<key>} name in the language file.
     *
     * <p>Derived from the enum name so that adding a new sort type doesn't
     * require updating a separate mapping here.</p>
     */
    public String messageKey() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public SortType next() {
        SortType[] values = SortType.values();
        return values[(this.ordinal() + 1) % values.length];
    }
}
