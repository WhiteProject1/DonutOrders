package ro.server.orderplugin.util;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ro.server.orderplugin.OrderPlugin;
import ro.server.orderplugin.scheduler.SchedulerAdapter;

public class SignInputManager implements Listener {
    private static final Logger LOGGER = Logger.getLogger("DonutOrders");
    private final OrderPlugin plugin;
    private final SchedulerAdapter scheduler;
    private final ConcurrentHashMap<UUID, SignInputData> pendingInputs = new ConcurrentHashMap<>();

    private static class SignInputData {
        final Location signLocation;
        final BlockState originalState;
        final Consumer<String> callback;
        final Runnable onCancel;

        SignInputData(Location loc, BlockState state, Consumer<String> cb, Runnable cancel) {
            this.signLocation = loc;
            this.originalState = state;
            this.callback = cb;
            this.onCancel = cancel;
        }
    }

    public SignInputManager(OrderPlugin plugin) {
        this.plugin = plugin;
        this.scheduler = plugin.getSchedulerAdapter();
    }

    public void requestInput(Player player, String[] promptLines, Consumer<String> callback, Runnable onCancel) {
        UUID playerId = player.getUniqueId();
        cancelInput(playerId);

        // Find a safe location for the sign: try multiple positions
        Location signLoc = findSignLocation(player);

        scheduler.runOnRegion(plugin, signLoc, () -> {
            try {
                Block block = signLoc.getBlock();
                BlockState originalState = block.getState();

                // Place sign
                block.setType(Material.OAK_SIGN, false);

                // Verify sign was placed
                BlockState newState = block.getState();
                if (!(newState instanceof Sign)) {
                    LOGGER.warning("[DonutOrders] Failed to place sign at " + formatLoc(signLoc) + " for " + player.getName());
                    originalState.update(true, false);
                    if (onCancel != null) {
                        scheduler.runOnEntity(plugin, player, onCancel);
                    }
                    return;
                }

                // Set prompt text on the sign
                Sign sign = (Sign) newState;
                for (int i = 0; i < 4; i++) {
                    if (i < promptLines.length && promptLines[i] != null) {
                        sign.setLine(i, promptLines[i]);
                    } else {
                        sign.setLine(i, "");
                    }
                }
                sign.update(true, false);

                pendingInputs.put(playerId, new SignInputData(signLoc, originalState, callback, onCancel));

                // Delay opening the sign editor so the client receives the block change packet first.
                // Use 3 ticks instead of 2 for better reliability on slower connections.
                scheduler.runOnEntityLater(plugin, player, () -> {
                    try {
                        if (!player.isOnline()) {
                            restoreSign(playerId);
                            return;
                        }
                        Block signBlock = signLoc.getBlock();
                        if (!(signBlock.getState() instanceof Sign)) {
                            LOGGER.warning("[DonutOrders] Sign disappeared before opening for " + player.getName());
                            restoreSign(playerId);
                            if (onCancel != null) onCancel.run();
                            return;
                        }
                        Sign freshSign = (Sign) signBlock.getState();
                        player.openSign(freshSign, Side.FRONT);
                    } catch (Exception ex) {
                        LOGGER.log(Level.WARNING, "[DonutOrders] Failed to open sign editor for " + player.getName(), ex);
                        restoreSign(playerId);
                        if (onCancel != null) onCancel.run();
                    }
                }, 3L);
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "[DonutOrders] Failed to setup sign input for " + player.getName(), ex);
                if (onCancel != null) {
                    scheduler.runOnEntity(plugin, player, onCancel);
                }
            }
        });
    }

    /**
     * Finds a safe location to place a temporary sign.
     * Tries below the player first, then at player level, then above.
     */
    private Location findSignLocation(Player player) {
        Location base = player.getLocation().clone();
        int minY = player.getWorld().getMinHeight();
        int maxY = player.getWorld().getMaxHeight() - 1;

        // Try 3 blocks below player (least visible)
        Location below = base.clone();
        below.setY(Math.max(base.getY() - 3, minY));
        if (isSafeForSign(below)) return below;

        // Try 2 blocks below
        below.setY(Math.max(base.getY() - 2, minY));
        if (isSafeForSign(below)) return below;

        // Try at player feet level
        if (isSafeForSign(base)) return base;

        // Try above player
        Location above = base.clone();
        above.setY(Math.min(base.getY() + 3, maxY));
        if (isSafeForSign(above)) return above;

        // Fallback: use the original -3 location regardless
        Location fallback = base.clone();
        fallback.setY(Math.max(base.getY() - 3, minY));
        return fallback;
    }

    private boolean isSafeForSign(Location loc) {
        try {
            Block block = loc.getBlock();
            Material type = block.getType();
            // Safe if air, replaceable, or non-solid
            return type.isAir() || !type.isSolid();
        } catch (Exception e) {
            return false;
        }
    }

    private String formatLoc(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        SignInputData data = pendingInputs.remove(playerId);
        if (data == null) return;

        event.setCancelled(true);

        // Restore original block
        scheduler.runOnRegion(plugin, data.signLocation, () -> {
            data.originalState.update(true, false);
        });

        // Get input from first line (line 0)
        String input = event.getLine(0);
        if (input != null && !input.trim().isEmpty()) {
            scheduler.runOnEntity(plugin, event.getPlayer(), () -> {
                data.callback.accept(input.trim());
            });
        } else if (data.onCancel != null) {
            scheduler.runOnEntity(plugin, event.getPlayer(), () -> {
                data.onCancel.run();
            });
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cancelInput(event.getPlayer().getUniqueId());
    }

    private void restoreSign(UUID playerId) {
        SignInputData data = pendingInputs.remove(playerId);
        if (data != null) {
            scheduler.runOnRegion(plugin, data.signLocation, () -> {
                data.originalState.update(true, false);
            });
        }
    }

    private void cancelInput(UUID playerId) {
        restoreSign(playerId);
    }

    public boolean hasPendingInput(UUID playerId) {
        return pendingInputs.containsKey(playerId);
    }
}
