package ru.saita.electrosamiki;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class ScooterManager {
    public static final int MIN_SPEED = 3;
    public static final int MAX_SPEED = 8;

    private static final long CLICK_COOLDOWN_MS = 250L;

    private final Plugin plugin;
    private final NamespacedKey scooterItemKey;
    private final NamespacedKey scooterEntityKey;
    private final NamespacedKey scooterSpeedKey;
    private final Map<UUID, Integer> scooterSpeeds = new HashMap<>();
    private final Map<UUID, Long> lastSpeedClicks = new HashMap<>();
    private BukkitTask movementTask;

    public ScooterManager(Plugin plugin) {
        this.plugin = plugin;
        this.scooterItemKey = new NamespacedKey(plugin, "scooter_item");
        this.scooterEntityKey = new NamespacedKey(plugin, "scooter_entity");
        this.scooterSpeedKey = new NamespacedKey(plugin, "scooter_speed");
    }

    public void start() {
        discoverLoadedScooters();
        movementTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickScooters, 1L, 1L);
    }

    public void stop() {
        if (movementTask != null) {
            movementTask.cancel();
            movementTask = null;
        }
        scooterSpeeds.clear();
        lastSpeedClicks.clear();
    }

    public ItemStack createScooterItem(int amount) {
        ItemStack item = new ItemStack(Material.MINECART, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.setDisplayName(ChatColor.AQUA + "Электросамокат");
        meta.setLore(List.of(
                ChatColor.GRAY + "ПКМ по блоку: поставить",
                ChatColor.GRAY + "ПКМ по самокату: сесть",
                ChatColor.GRAY + "Клик во время езды: сменить скорость"
        ));
        meta.getPersistentDataContainer().set(scooterItemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isScooterItem(ItemStack item) {
        if (item == null || item.getType() != Material.MINECART || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(scooterItemKey, PersistentDataType.BYTE);
    }

    public Minecart spawnScooter(Location location) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Cannot spawn scooter without world");
        }

        Entity entity = world.spawnEntity(location, EntityType.MINECART);
        if (!(entity instanceof Minecart cart)) {
            entity.remove();
            throw new IllegalStateException("EntityType.MINECART did not create a Minecart");
        }

        tagScooter(cart, MIN_SPEED);
        cart.setCustomNameVisible(true);
        cart.setMaxSpeed(MAX_SPEED / 20.0D);
        scooterSpeeds.put(cart.getUniqueId(), MIN_SPEED);
        return cart;
    }

    public boolean isScooter(Entity entity) {
        return entity instanceof Minecart minecart
                && minecart.getPersistentDataContainer().has(scooterEntityKey, PersistentDataType.BYTE);
    }

    public boolean isRidingScooter(Player player) {
        Entity vehicle = player.getVehicle();
        return vehicle != null && isScooter(vehicle);
    }

    public void registerLoadedScooter(Minecart cart) {
        if (!isScooter(cart)) {
            return;
        }
        scooterSpeeds.put(cart.getUniqueId(), getStoredSpeed(cart));
        updateName(cart, getStoredSpeed(cart));
    }

    public void resetSpeed(Minecart cart) {
        setSpeed(cart, MIN_SPEED);
    }

    public void cycleSpeed(Player player) {
        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof Minecart cart) || !isScooter(cart)) {
            return;
        }

        long now = System.currentTimeMillis();
        Long lastClick = lastSpeedClicks.get(player.getUniqueId());
        if (lastClick != null && now - lastClick < CLICK_COOLDOWN_MS) {
            return;
        }
        lastSpeedClicks.put(player.getUniqueId(), now);

        int speed = getStoredSpeed(cart);
        int nextSpeed = speed >= MAX_SPEED ? MIN_SPEED : speed + 1;
        setSpeed(cart, nextSpeed);
        player.sendMessage(ChatColor.AQUA + "Скорость электросамоката: " + nextSpeed + " блок/с.");
    }

    public void dropScooterItem(Location location) {
        World world = location.getWorld();
        if (world != null) {
            world.dropItemNaturally(location, createScooterItem(1));
        }
    }

    private void discoverLoadedScooters() {
        for (World world : Bukkit.getWorlds()) {
            for (Minecart cart : world.getEntitiesByClass(Minecart.class)) {
                if (isScooter(cart)) {
                    registerLoadedScooter(cart);
                }
            }
        }
    }

    private void tickScooters() {
        Iterator<Map.Entry<UUID, Integer>> iterator = scooterSpeeds.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof Minecart cart) || cart.isDead() || !isScooter(cart)) {
                iterator.remove();
                continue;
            }

            Player rider = findPlayerPassenger(cart);
            if (rider == null) {
                Vector velocity = cart.getVelocity();
                cart.setVelocity(new Vector(0.0D, velocity.getY(), 0.0D));
                continue;
            }

            int speed = getStoredSpeed(cart);
            Vector direction = rider.getLocation().getDirection();
            direction.setY(0.0D);
            if (direction.lengthSquared() < 0.0001D) {
                continue;
            }

            direction.normalize().multiply(speed / 20.0D);
            Vector currentVelocity = cart.getVelocity();
            cart.setRotation(rider.getLocation().getYaw(), 0.0F);
            cart.setMaxSpeed(MAX_SPEED / 20.0D);
            cart.setVelocity(new Vector(direction.getX(), currentVelocity.getY(), direction.getZ()));
        }
    }

    private Player findPlayerPassenger(Minecart cart) {
        for (Entity passenger : cart.getPassengers()) {
            if (passenger instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private int getStoredSpeed(Minecart cart) {
        Integer cachedSpeed = scooterSpeeds.get(cart.getUniqueId());
        if (cachedSpeed != null) {
            return cachedSpeed;
        }

        PersistentDataContainer container = cart.getPersistentDataContainer();
        Integer storedSpeed = container.get(scooterSpeedKey, PersistentDataType.INTEGER);
        if (storedSpeed == null || storedSpeed < MIN_SPEED || storedSpeed > MAX_SPEED) {
            return MIN_SPEED;
        }
        return storedSpeed;
    }

    private void setSpeed(Minecart cart, int speed) {
        int clampedSpeed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
        cart.getPersistentDataContainer().set(scooterSpeedKey, PersistentDataType.INTEGER, clampedSpeed);
        scooterSpeeds.put(cart.getUniqueId(), clampedSpeed);
        updateName(cart, clampedSpeed);
    }

    private void tagScooter(Minecart cart, int speed) {
        cart.getPersistentDataContainer().set(scooterEntityKey, PersistentDataType.BYTE, (byte) 1);
        setSpeed(cart, speed);
    }

    private void updateName(Minecart cart, int speed) {
        cart.setCustomName(ChatColor.AQUA + "Электросамокат " + ChatColor.GRAY + "[" + speed + " блок/с]");
    }
}
