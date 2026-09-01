package ro.server.orderplugin.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ro.server.orderplugin.OrderPlugin;
import ro.server.orderplugin.util.CooldownManager;

public class OrderCommand implements CommandExecutor {
    private final OrderPlugin plugin;

    public OrderCommand(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(this.plugin.msg(sender, "general.player-only"));
            return true;
        }
        Player player = (Player) sender;
        // Menu acmak siparis listesini suzup siralar ve 54 esya kurar; komutu
        // tekrar tekrar yazarak bunu tetiklemek ucuz bir yuk saldirisidir.
        if (!this.plugin.cooldowns().checkAndWarn(player, CooldownManager.Type.COMMAND)) {
            return true;
        }
        if (args.length == 0) {
            this.plugin.getGuiManager().openMainMenu(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("purge")) {
            if (!player.hasPermission("orders.admin")) {
                player.sendMessage(this.plugin.msg(sender, "general.no-permission"));
                return true;
            }
            int count = this.plugin.getOrderManager().purgeAllOrders();
            player.sendMessage(this.plugin.msg(sender, "admin.orders-deleted", "%amount%", String.valueOf(count)));
            return true;
        }
        String searchQuery = String.join(" ", args);
        this.plugin.getGuiManager().openMainMenu(player, searchQuery);
        return true;
    }
}
