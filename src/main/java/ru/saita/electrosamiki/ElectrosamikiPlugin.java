package ru.saita.electrosamiki;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ElectrosamikiPlugin extends JavaPlugin {
    private ScooterManager scooterManager;

    @Override
    public void onEnable() {
        scooterManager = new ScooterManager(this);
        scooterManager.start();

        ScooterCommand scooterCommand = new ScooterCommand(scooterManager);
        PluginCommand command = getCommand("electrosamik");
        if (command == null) {
            throw new IllegalStateException("Command electrosamik is missing in plugin.yml");
        }
        command.setExecutor(scooterCommand);
        command.setTabCompleter(scooterCommand);

        getServer().getPluginManager().registerEvents(new ScooterListener(scooterManager), this);
    }

    @Override
    public void onDisable() {
        if (scooterManager != null) {
            scooterManager.stop();
        }
    }
}
