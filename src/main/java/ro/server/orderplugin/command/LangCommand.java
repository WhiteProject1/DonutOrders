package ro.server.orderplugin.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import ro.server.orderplugin.OrderPlugin;

/**
 * {@code /orderlang} — lets a player choose their own language.
 *
 * <p>Used with no arguments, it opens the language menu; {@code /orderlang <code>}
 * selects directly, and {@code /orderlang auto} clears the selection and reverts to
 * automatic resolution (client language -> server default).</p>
 */
public final class LangCommand implements CommandExecutor, TabCompleter {

    private final OrderPlugin plugin;

    public LangCommand(OrderPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.msg(sender, "general.player-only"));
            return true;
        }
        if (!plugin.getLanguage().isPerPlayer()) {
            player.sendMessage(plugin.msg(player, "errors.feature-disabled"));
            return true;
        }

        if (args.length == 0) {
            plugin.getGuiManager().openLanguageMenu(player);
            return true;
        }

        String code = args[0].toLowerCase(Locale.ROOT);
        if (code.equals("auto") || code.equals("reset")) {
            plugin.getLanguage().setOverride(player.getUniqueId(), null);
            plugin.getLangStorage().set(player.getUniqueId(), null);
            player.sendMessage(plugin.msg(player, "success.language-changed",
                    "%language%", plugin.getLanguage().displayName(plugin.getLanguage().resolve(player))));
            plugin.playSuccess(player);
            return true;
        }

        if (!plugin.getLanguage().isSupported(code)) {
            player.sendMessage(plugin.msg(player, "errors.language-not-supported",
                    "%language%", code, "%languages%", String.join(", ", plugin.getGuiManager().availableLanguages())));
            plugin.playError(player);
            return true;
        }

        plugin.getLanguage().setOverride(player.getUniqueId(), code);
        plugin.getLangStorage().set(player.getUniqueId(), code);
        player.sendMessage(plugin.msg(player, "success.language-changed",
                "%language%", plugin.getLanguage().displayName(code)));
        plugin.playSuccess(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String partial = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>(plugin.getGuiManager().availableLanguages());
        options.add("auto");
        options.removeIf(code -> !code.startsWith(partial));
        return options;
    }
}
