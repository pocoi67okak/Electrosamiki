package ru.saita.electrosamiki;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class ScooterListener implements Listener {
    private final ScooterManager scooterManager;

    public ScooterListener(ScooterManager scooterManager) {
        this.scooterManager = scooterManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        if (scooterManager.isRidingScooter(player)) {
            event.setCancelled(true);
            scooterManager.cycleSpeed(player);
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !scooterManager.isScooterItem(event.getItem())) {
            return;
        }

        event.setCancelled(true);
        if (!player.hasPermission("electrosamiki.use")) {
            player.sendMessage(ChatColor.RED + "Нет прав: electrosamiki.use");
            return;
        }

        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }

        Location spawnLocation = clickedBlock.getLocation().add(0.5D, 1.05D, 0.5D);
        if (!spawnLocation.getBlock().isPassable()) {
            player.sendMessage(ChatColor.RED + "Здесь недостаточно места для электросамоката.");
            return;
        }

        Minecart cart = scooterManager.spawnScooter(spawnLocation);
        cart.setRotation(player.getLocation().getYaw(), 0.0F);
        consumeOneScooter(player);
        player.sendMessage(ChatColor.AQUA + "Электросамокат поставлен.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Entity entity = event.getRightClicked();
        if (!(entity instanceof Minecart cart) || !scooterManager.isScooter(cart)) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("electrosamiki.use")) {
            player.sendMessage(ChatColor.RED + "Нет прав: electrosamiki.use");
            return;
        }

        if (player.getVehicle() != null) {
            player.sendMessage(ChatColor.RED + "Сначала слезьте с текущего транспорта.");
            return;
        }

        if (!cart.getPassengers().isEmpty()) {
            player.sendMessage(ChatColor.RED + "Этот электросамокат уже занят.");
            return;
        }

        scooterManager.registerLoadedScooter(cart);
        scooterManager.resetSpeed(cart);
        cart.addPassenger(player);
        player.sendMessage(ChatColor.AQUA + "Вы сели на электросамокат. Кликайте мышью, чтобы менять скорость.");
    }

    @EventHandler
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        if (event.getAnimationType() == PlayerAnimationType.ARM_SWING
                && scooterManager.isRidingScooter(event.getPlayer())) {
            scooterManager.cycleSpeed(event.getPlayer());
        }
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getVehicle() instanceof Minecart cart && scooterManager.isScooter(cart)) {
            scooterManager.resetSpeed(cart);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Entity vehicle = event.getPlayer().getVehicle();
        if (vehicle != null && scooterManager.isScooter(vehicle)) {
            vehicle.removePassenger(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart) || !scooterManager.isScooter(cart)) {
            return;
        }

        event.setCancelled(true);
        Location dropLocation = cart.getLocation();
        cart.remove();
        scooterManager.dropScooterItem(dropLocation);
    }

    private void consumeOneScooter(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        item.setAmount(item.getAmount() - 1);
    }
}
