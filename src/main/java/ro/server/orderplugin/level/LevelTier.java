package ro.server.orderplugin.level;

import java.util.List;

/**
 * A single level definition inside {@code levels.yml}.
 *
 * <p>None of the fields are hard-coded in the code; if a field is absent from
 * the file, that bonus is zero for that level. The <b>number</b> of levels
 * isn't fixed either: however many are written into the file, that many exist.</p>
 *
 * @param level          the level number
 * @param xpRequired     TOTAL xp required to reach this level
 * @param taxDiscount    tax discount percentage (0-100)
 * @param extraOrders    added to the active order limit
 * @param extraItems     added to the item count per order
 * @param rewardCommands run on the console on level-up ({@code %player%})
 * @param broadcast      whether to announce it to the server
 */
public record LevelTier(int level, double xpRequired, double taxDiscount,
                        int extraOrders, int extraItems,
                        List<String> rewardCommands, boolean broadcast) {

    /** Safe base used when the level table is empty. */
    public static LevelTier base() {
        return new LevelTier(1, 0d, 0d, 0, 0, List.of(), false);
    }
}
