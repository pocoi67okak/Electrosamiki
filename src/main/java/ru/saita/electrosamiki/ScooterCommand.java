package ru.saita.electrosamiki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class ScooterCommand implements CommandExecutor, TabCompleter {
    private final ScooterManager scooterManager;

    public ScooterCommand(ScooterManager scooterManager) {
        this.scooterManager = scooterManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return giveScooter(sender, args[1]);
        }

        sender.sendMessage(ChatColor.RED + "Использование: /" + label + " give <ник>");
        return true;
    }

    private boolean giveScooter(CommandSender sender, String playerName) {
        if (!sender.hasPermission("electrosamiki.give")) {
            sender.sendMessage(ChatColor.RED + "Нет прав: electrosamiki.give");
            return true;
        }

        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Игрок '" + playerName + "' не найден или не онлайн.");
            return true;
        }

        target.getInventory().addItem(scooterManager.createScooterItem(1));
        target.sendMessage(ChatColor.AQUA + "Вы получили электросамокат.");

        if (!target.equals(sender)) {
            sender.sendMessage(ChatColor.GREEN + "Электросамокат выдан игроку " + target.getName() + ".");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("give"), args[0]);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give") && sender.hasPermission("electrosamiki.give")) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return filter(names, args[1]);
        }

        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String input) {
        String lowered = input.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase().startsWith(lowered)) {
                result.add(value);
            }
        }
        return result;
    }
}
