package ru.saita.electrosamiki;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.ChunkLoadEvent;
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
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                scooterManager.increaseSpeed(player);
            } else if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
                scooterManager.decreaseSpeed(player);
            }
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
        spawnLocation.setYaw(player.getLocation().getYaw());
        spawnLocation.setPitch(0.0F);
        if (!spawnLocation.getBlock().isPassable() || !spawnLocation.clone().add(0.0D, 1.0D, 0.0D).getBlock().isPassable()) {
            player.sendMessage(ChatColor.RED + "Здесь недостаточно места для электросамоката.");
            return;
        }

        scooterManager.spawnScooter(spawnLocation);
        consumeOneScooter(player);
        player.sendMessage(ChatColor.AQUA + "Электросамокат поставлен.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        ArmorStand scooter = scooterManager.getScooterRoot(event.getRightClicked());
        if (scooter == null) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (scooterManager.isRidingScooter(player)) {
            scooterManager.increaseSpeed(player);
            return;
        }

        if (!player.hasPermission("electrosamiki.use")) {
            player.sendMessage(ChatColor.RED + "Нет прав: electrosamiki.use");
            return;
        }

        if (player.getVehicle() != null) {
            player.sendMessage(ChatColor.RED + "Сначала слезьте с текущего транспорта.");
            return;
        }

        if (scooterManager.isOccupied(scooter)) {
            player.sendMessage(ChatColor.RED + "Этот электросамокат уже занят.");
            return;
        }

        scooterManager.registerLoadedScooter(scooter);
        scooterManager.startRide(player, scooter);
        player.sendMessage(ChatColor.AQUA + "Вы сели на электросамокат. ПКМ - газ, ЛКМ - тормоз, Shift - слезть.");
    }

    @EventHandler
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        if (event.getAnimationType() == PlayerAnimationType.ARM_SWING
                && scooterManager.isRidingScooter(event.getPlayer())) {
            scooterManager.decreaseSpeed(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && scooterManager.isRidingScooter(event.getPlayer())) {
            scooterManager.stopRide(event.getPlayer());
        }
    }

    @EventHandler
    public void onEntityDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player
                && scooterManager.isRidingScooter(player)
                && scooterManager.getScooterRoot(event.getDismounted()) != null) {
            scooterManager.stopRide(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        scooterManager.stopRide(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        ArmorStand scooter = scooterManager.getScooterRoot(event.getEntity());
        if (scooter == null) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }

        if (scooterManager.isRidingScooter(player)) {
            scooterManager.decreaseSpeed(player);
            return;
        }

        if (!player.hasPermission("electrosamiki.use")) {
            player.sendMessage(ChatColor.RED + "Нет прав: electrosamiki.use");
            return;
        }

        scooterManager.removeScooter(scooter, player.getGameMode() != GameMode.CREATIVE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) {
            return;
        }

        if (scooterManager.getScooterRoot(event.getEntity()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        scooterManager.registerLoadedEntities(event.getChunk().getEntities());
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
