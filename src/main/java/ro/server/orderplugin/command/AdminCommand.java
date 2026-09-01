package ro.server.orderplugin.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ro.server.orderplugin.OrderPlugin;
import ro.server.orderplugin.model.Order;
import ro.server.orderplugin.util.TextUtil;

public class AdminCommand implements CommandExecutor, TabCompleter {
    private final OrderPlugin plugin;

    public AdminCommand(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("orders.admin")) {
            sender.sendMessage(this.plugin.msg(sender, "general.no-permission"));
            return true;
        }
        // Argumansiz cagri ve "menu": paneli acar. Konsoldan calistirilirsa
        // menu acilamayacagi icin yardim metni gosterilir.
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (sender instanceof Player player) {
                this.plugin.adminGui().openAdminMenu(player);
            } else {
                this.sendHelp(sender);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            this.plugin.reloadAllConfigs();
            sender.sendMessage(this.plugin.msg(sender, "general.reload-success"));
            return true;
        }
        if (args[0].equalsIgnoreCase("removeorder")) {
            if (args.length < 3) {
                sender.sendMessage(this.plugin.msg(sender, "admin.usage-removeorder"));
                return true;
            }
            String playerName = args[1];
            String orderIdentifier = args[2];
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
            if (!offlinePlayer.hasPlayedBefore() && !offlinePlayer.isOnline()) {
                sender.sendMessage(this.plugin.msg(sender, "errors.player-not-found", "%player%", playerName));
                return true;
            }

            Order targetOrder = null;
            List<Order> playerOrders = this.plugin.getOrderManager().getOrdersByPlayer(offlinePlayer.getUniqueId());
            for (Order order : playerOrders) {
                if (order.isComplete()) continue;
                String shortId = order.getId().toString().substring(0, 8);
                String orderKey = order.getMaterial().name() + "_x" + order.getNeeded() + "_" + shortId;
                if (!orderIdentifier.equalsIgnoreCase(orderKey) && !orderIdentifier.equals(order.getId().toString())) continue;
                targetOrder = order;
                break;
            }

            if (targetOrder == null) {
                sender.sendMessage(this.plugin.msg(sender, "errors.order-not-found"));
                return true;
            }

            // Kaldirma mantigi OrderManager'da tek yerde: yonetici paneli de ayni
            // metodu cagirir, boylece iki yol birbirinden ayrisamaz.
            this.plugin.getOrderManager().adminRemoveOrder(sender, targetOrder);
            return true;
        }
        this.sendHelp(sender);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(this.plugin.msg(sender, "admin.help-header"));
        sender.sendMessage(this.plugin.msg(sender, "admin.help-menu"));
        sender.sendMessage(this.plugin.msg(sender, "admin.help-reload"));
        sender.sendMessage(this.plugin.msg(sender, "admin.help-removeorder"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        ArrayList<String> completions = new ArrayList<>();
        if (!sender.hasPermission("orders.admin")) {
            return completions;
        }
        if (args.length == 1) {
            completions.add("menu");
            completions.add("reload");
            completions.add("removeorder");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("removeorder")) {
            List<UUID> ownerIds = this.plugin.getOrderManager().getActiveOrders().stream()
                .filter(order -> !order.isComplete())
                .map(Order::getOwner)
                .distinct()
                .collect(Collectors.toList());
            for (UUID uuid : ownerIds) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                if (op.getName() == null) continue;
                completions.add(op.getName());
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("removeorder")) {
            String targetName = args[1];
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(targetName);
            if (offlinePlayer.hasPlayedBefore() || offlinePlayer.isOnline()) {
                List<Order> orders = this.plugin.getOrderManager().getActiveOrders().stream()
                    .filter(order -> order.getOwner().equals(offlinePlayer.getUniqueId()))
                    .filter(order -> !order.isComplete())
                    .collect(Collectors.toList());
                for (Order order : orders) {
                    String shortId = order.getId().toString().substring(0, 8);
                    String orderKey = order.getMaterial().name() + "_x" + order.getNeeded() + "_" + shortId;
                    completions.add(orderKey);
                }
            }
        }
        String input = args[args.length - 1].toLowerCase();
        return completions.stream().filter(s -> s.toLowerCase().startsWith(input)).collect(Collectors.toList());
    }
}
