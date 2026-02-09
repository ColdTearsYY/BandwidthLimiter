package com.bandwidthlimiter;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 插件命令处理器
 *
 * 命令:
 *   /bwl set <玩家> <KB/s>     - 设置玩家带宽限制
 *   /bwl remove <玩家>           - 移除玩家独立限制
 *   /bwl info <玩家>             - 查看玩家带宽信息
 *   /bwl default <KB/s>          - 设置默认带宽限制
 *   /bwl list                     - 列出所有在线玩家的带宽状态
 *   /bwl reload                   - 重新加载配置
 */
public class BandwidthCommand implements CommandExecutor, TabCompleter {

    private final BandwidthLimiterPlugin plugin;
    private final String PREFIX = ChatColor.GREEN + "[BWL] " + ChatColor.RESET;

    public BandwidthCommand(BandwidthLimiterPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bandwidthlimiter.admin")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "你没有权限使用此命令");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        BandwidthManager manager = plugin.getBandwidthManager();

        switch (args[0].toLowerCase()) {
            case "set":
                handleSet(sender, args, manager);
                break;

            case "remove":
            case "reset":
                handleRemove(sender, args, manager);
                break;

            case "info":
            case "check":
                handleInfo(sender, args, manager);
                break;

            case "default":
                handleDefault(sender, args, manager);
                break;

            case "list":
                handleList(sender, manager);
                break;

            case "reload":
                handleReload(sender, manager);
                break;

            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    private void handleSet(CommandSender sender, String[] args, BandwidthManager manager) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + ChatColor.RED + "用法: /bwl set <玩家> ");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "玩家 " + args[1] + " 不在线");
            return;
        }

        long limit;
        try {
            limit = Long.parseLong(args[2]);
            if (limit <= 0) {
                sender.sendMessage(PREFIX + ChatColor.RED + "带宽限制必须大于 0");
                return;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(PREFIX + ChatColor.RED + "无效的数字: " + args[2]);
            return;
        }

        manager.setPlayerLimit(target.getUniqueId(), limit);
        sender.sendMessage(PREFIX + ChatColor.GREEN + "已将玩家 " + ChatColor.WHITE
            + target.getName() + ChatColor.GREEN + " 的带宽限制设置为 "
            + ChatColor.YELLOW + limit + " KB/s");
    }

    private void handleRemove(CommandSender sender, String[] args, BandwidthManager manager) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + ChatColor.RED + "用法: /bwl remove <玩家>");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "玩家 " + args[1] + " 不在线");
            return;
        }

        manager.removePlayerLimit(target.getUniqueId());
        sender.sendMessage(PREFIX + ChatColor.GREEN + "已移除玩家 " + ChatColor.WHITE
            + target.getName() + ChatColor.GREEN + " 的独立限制，现在使用默认值: "
            + ChatColor.YELLOW + manager.getDefaultLimit() + " KB/s");
    }

    private void handleInfo(CommandSender sender, String[] args, BandwidthManager manager) {
        if (args.length < 2) {
            // 如果发送者是玩家，显示自己的信息
            if (sender instanceof Player) {
                showPlayerInfo(sender, (Player) sender, manager);
                return;
            }
            sender.sendMessage(PREFIX + ChatColor.RED + "用法: /bwl info <玩家>");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "玩家 " + args[1] + " 不在线");
            return;
        }

        showPlayerInfo(sender, target, manager);
    }

    private void showPlayerInfo(CommandSender sender, Player target, BandwidthManager manager) {
        long limit = manager.getPlayerLimit(target);
        long currentRate = manager.getPlayerCurrentRate(target);
        boolean hasHandler = manager.hasHandler(target);
        boolean bypassing = target.hasPermission("bandwidthlimiter.bypass");

        sender.sendMessage(PREFIX + ChatColor.AQUA + "=== " + target.getName() + " 带宽信息 ===");
        sender.sendMessage(PREFIX + "状态: " + (hasHandler
            ? ChatColor.GREEN + "已限制" : (bypassing
            ? ChatColor.YELLOW + "绕过" : ChatColor.RED + "未注入")));
        sender.sendMessage(PREFIX + "带宽上限: " + ChatColor.YELLOW + limit + " KB/s");

        if (currentRate >= 0) {
            double currentKBps = currentRate / 1024.0;
            ChatColor rateColor = currentKBps > limit * 0.8 ? ChatColor.RED
                : currentKBps > limit * 0.5 ? ChatColor.YELLOW : ChatColor.GREEN;
            sender.sendMessage(PREFIX + "当前速率: " + rateColor
                + String.format("%.2f KB/s", currentKBps));
            sender.sendMessage(PREFIX + "使用率: " + rateColor
                + String.format("%.1f%%", (currentKBps / limit) * 100));
        } else {
            sender.sendMessage(PREFIX + "当前速率: " + ChatColor.GRAY + "N/A");
        }
    }

    private void handleDefault(CommandSender sender, String[] args, BandwidthManager manager) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "当前默认限制: " + ChatColor.YELLOW
                + manager.getDefaultLimit() + " KB/s");
            return;
        }

        long limit;
        try {
            limit = Long.parseLong(args[1]);
            if (limit <= 0) {
                sender.sendMessage(PREFIX + ChatColor.RED + "带宽限制必须大于 0");
                return;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(PREFIX + ChatColor.RED + "无效的数字: " + args[1]);
            return;
        }

        manager.setDefaultLimit(limit);
        sender.sendMessage(PREFIX + ChatColor.GREEN + "默认带宽限制已设置为 "
            + ChatColor.YELLOW + limit + " KB/s");

        // 更新所有使用默认值的在线玩家
        for (Player player : Bukkit.getOnlinePlayers()) {
            manager.updatePlayerLimit(player);
        }
        sender.sendMessage(PREFIX + ChatColor.GREEN + "已更新所有在线玩家的限制");
    }

    private void handleList(CommandSender sender, BandwidthManager manager) {
        sender.sendMessage(PREFIX + ChatColor.AQUA + "=== 在线玩家带宽状态 ===");
        sender.sendMessage(PREFIX + "默认限制: " + ChatColor.YELLOW
            + manager.getDefaultLimit() + " KB/s");
        sender.sendMessage("");

        for (Player player : Bukkit.getOnlinePlayers()) {
            long limit = manager.getPlayerLimit(player);
            long currentRate = manager.getPlayerCurrentRate(player);
            boolean hasHandler = manager.hasHandler(player);

            String status;
            if (player.hasPermission("bandwidthlimiter.bypass")) {
                status = ChatColor.YELLOW + "[绕过]";
            } else if (hasHandler) {
                status = ChatColor.GREEN + "[限制中]";
            } else {
                status = ChatColor.RED + "[未注入]";
            }

            String rateStr;
            if (currentRate >= 0) {
                double currentKBps = currentRate / 1024.0;
                rateStr = String.format("%.1f/%d KB/s", currentKBps, limit);
            } else {
                rateStr = "-/" + limit + " KB/s";
            }

            sender.sendMessage(PREFIX + status + " " + ChatColor.WHITE
                + player.getName() + ChatColor.GRAY + " - " + rateStr);
        }
    }

    private void handleReload(CommandSender sender, BandwidthManager manager) {
        manager.loadConfig();
        sender.sendMessage(PREFIX + ChatColor.GREEN + "配置已重新加载！");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + ChatColor.AQUA + "=== BandwidthLimiter 帮助 ===");
        sender.sendMessage(PREFIX + ChatColor.YELLOW + "/bwl set <玩家> "
            + ChatColor.GRAY + " - 设置带宽限制");
        sender.sendMessage(PREFIX + ChatColor.YELLOW + "/bwl remove <玩家>"
            + ChatColor.GRAY + " - 移除独立限制");
        sender.sendMessage(PREFIX + ChatColor.YELLOW + "/bwl info [玩家]"
            + ChatColor.GRAY + " - 查看带宽信息");
        sender.sendMessage(PREFIX + ChatColor.YELLOW + "/bwl default [KB/s]"
            + ChatColor.GRAY + " - 设置/查看默认限制");
        sender.sendMessage(PREFIX + ChatColor.YELLOW + "/bwl list"
            + ChatColor.GRAY + " - 列出所有玩家状态");
        sender.sendMessage(PREFIX + ChatColor.YELLOW + "/bwl reload"
            + ChatColor.GRAY + " - 重新加载配置");
    }

    @Override
    public List onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("bandwidthlimiter.admin")) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            return filterCompletions(
                Arrays.asList("set", "remove", "info", "default", "list", "reload"),
                args[0]
            );
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("set") || sub.equals("remove") || sub.equals("info") || sub.equals("check")) {
                return filterCompletions(
                    Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .collect(Collectors.toList()),
                    args[1]
                );
            }
            if (sub.equals("default")) {
                return Arrays.asList("128", "256", "512", "1024", "2048");
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return Arrays.asList("128", "256", "512", "1024", "2048");
        }

        return new ArrayList<>();
    }

    private List<String> filterCompletions(List<String> options, String input) {
        return options.stream()
            .filter(s -> s.toLowerCase().startsWith(input.toLowerCase()))
            .collect(Collectors.toList());
    }
}