package cn.tohsaka.factory.zstdnet26.spigot;

import cn.tohsaka.factory.zstdnet26.core.proxy.ZstdProxyConfig;
import cn.tohsaka.factory.zstdnet26.core.stats.TrafficStats;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ZstdNetCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("status", "reload", "start", "stop", "setup");

    private final ZstdNetPlugin plugin;

    ZstdNetCommand(ZstdNetPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("zstdnet.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> sendStatus(sender);
            case "reload" -> sender.sendMessage(plugin.reloadProxy()
                ? ChatColor.GREEN + "ZstdNet proxy reloaded."
                : failureMessage("reload"));
            case "start" -> sender.sendMessage(plugin.startProxy()
                ? ChatColor.GREEN + "ZstdNet proxy started."
                : failureMessage("start"));
            case "stop" -> {
                plugin.stopProxy();
                sender.sendMessage(ChatColor.YELLOW + "ZstdNet proxy stopped.");
            }
            case "setup" -> runSetup(sender, args);
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: /zstdnet <status|reload|start|stop|setup [publicPort]>");
        }
        return true;
    }

    private void runSetup(CommandSender sender, String[] args) {
        Integer publicPort = null;
        if (args.length > 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /zstdnet setup [publicPort]");
            return;
        }
        if (args.length == 2) {
            try {
                publicPort = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Public port must be a number from 1 to 65535.");
                return;
            }
            if (publicPort <= 0 || publicPort > 65535) {
                sender.sendMessage(ChatColor.RED + "Public port must be a number from 1 to 65535.");
                return;
            }
        }
        sender.sendMessage(plugin.runSetup(publicPort)
            ? ChatColor.YELLOW + "ZstdNet same-port config updated. Run /zstdnet start or /zstdnet reload."
            : ChatColor.RED + "ZstdNet setup failed. Check the server log.");
    }

    private String failureMessage(String action) {
        if (plugin.isSetupPendingRestart()) {
            return ChatColor.YELLOW + "ZstdNet setup is pending restart. Restart the server before running /zstdnet " + action + ".";
        }
        return ChatColor.RED + "ZstdNet proxy " + action + " failed. Check the server log.";
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "ZstdNet running: " + plugin.isProxyRunning());
        if (plugin.isSetupPendingRestart()) {
            sender.sendMessage(ChatColor.YELLOW + "Setup pending restart: restart the server before starting the proxy.");
        }
        ZstdProxyConfig config = plugin.activeConfig();
        if (config != null) {
            sender.sendMessage(ChatColor.GRAY + "Listen: " + config.listen() + " -> target: " + config.target());
        } else {
            ZstdProxyConfig configured = plugin.configuredConfig();
            sender.sendMessage(ChatColor.GRAY + "Configured: " + configured.listen() + " -> target: " + configured.target());
        }
        int serverPort = plugin.currentServerPort();
        if (serverPort > 0) {
            sender.sendMessage(ChatColor.GRAY + "server.properties server-port: " + serverPort);
        }
        TrafficStats.Snapshot stats = plugin.proxyStats();
        if (stats != null) {
            sender.sendMessage(ChatColor.GRAY + String.format(
                Locale.ROOT,
                "Active=%d total=%d raw=%d/%d wire=%d/%d ratio=%.2f%%",
                stats.connections(),
                stats.totalConnections(),
                stats.rawUpBytes(),
                stats.rawDownBytes(),
                stats.wireUpBytes(),
                stats.wireDownBytes(),
                stats.ratioPercent()
            ));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("zstdnet.admin")) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String subcommand : SUBCOMMANDS) {
            if (subcommand.startsWith(prefix)) {
                matches.add(subcommand);
            }
        }
        return matches;
    }
}
