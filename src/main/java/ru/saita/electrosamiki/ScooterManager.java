package ru.saita.electrosamiki;

import java.util.ArrayList;
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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class ScooterManager {
    public static final int MIN_SPEED = 3;
    public static final int MAX_SPEED = 8;

    private static final long CLICK_COOLDOWN_MS = 250L;
    private static final double TICKS_PER_SECOND = 20.0D;

    private static final List<ModelPart> MODEL_PARTS = List.of(
            new ModelPart(Material.POLISHED_BLACKSTONE, 0.48F, 0.08F, 1.48F, 0.0D, 0.28D, 0.0D),
            new ModelPart(Material.BLACK_CONCRETE, 0.30F, 0.30F, 0.12F, 0.0D, 0.13D, 0.66D),
            new ModelPart(Material.BLACK_CONCRETE, 0.30F, 0.30F, 0.12F, 0.0D, 0.13D, -0.66D),
            new ModelPart(Material.IRON_BLOCK, 0.08F, 1.05F, 0.08F, 0.0D, 0.82D, 0.56D),
            new ModelPart(Material.IRON_BLOCK, 0.86F, 0.06F, 0.06F, 0.0D, 1.34D, 0.56D),
            new ModelPart(Material.SEA_LANTERN, 0.16F, 0.12F, 0.08F, 0.0D, 1.08D, 0.63D),
            new ModelPart(Material.REDSTONE_BLOCK, 0.16F, 0.10F, 0.08F, 0.0D, 0.38D, -0.76D)
    );

    private final Plugin plugin;
    private final NamespacedKey scooterItemKey;
    private final NamespacedKey scooterEntityKey;
    private final NamespacedKey scooterSpeedKey;
    private final NamespacedKey scooterVisualKey;
    private final NamespacedKey scooterRootKey;
    private final Map<UUID, Integer> scooterSpeeds = new HashMap<>();
    private final Map<UUID, ScooterVisual> visuals = new HashMap<>();
    private final Map<UUID, Long> lastSpeedClicks = new HashMap<>();
    private BukkitTask movementTask;

    public ScooterManager(Plugin plugin) {
        this.plugin = plugin;
        this.scooterItemKey = new NamespacedKey(plugin, "scooter_item");
        this.scooterEntityKey = new NamespacedKey(plugin, "scooter_entity");
        this.scooterSpeedKey = new NamespacedKey(plugin, "scooter_speed");
        this.scooterVisualKey = new NamespacedKey(plugin, "scooter_visual");
        this.scooterRootKey = new NamespacedKey(plugin, "scooter_root");
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
        removeAllVisuals();
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

    public ArmorStand spawnScooter(Location location) {
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException("Cannot spawn scooter without world");
        }

        ArmorStand stand = world.spawn(location, ArmorStand.class);
        configureScooterStand(stand);
        tagScooter(stand, MIN_SPEED);
        ensureVisuals(stand);
        updateVisuals(stand);
        return stand;
    }

    public ArmorStand getScooterRoot(Entity entity) {
        if (entity == null) {
            return null;
        }

        if (entity instanceof ArmorStand stand && isScooter(stand)) {
            return stand;
        }

        String rootId = entity.getPersistentDataContainer().get(scooterRootKey, PersistentDataType.STRING);
        if (rootId == null) {
            return null;
        }

        try {
            Entity root = Bukkit.getEntity(UUID.fromString(rootId));
            if (root instanceof ArmorStand stand && isScooter(stand)) {
                return stand;
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    public boolean isScooter(Entity entity) {
        return entity instanceof ArmorStand stand
                && stand.getPersistentDataContainer().has(scooterEntityKey, PersistentDataType.BYTE);
    }

    public boolean isRidingScooter(Player player) {
        return getScooterRoot(player.getVehicle()) != null;
    }

    public void registerLoadedEntities(Entity[] entities) {
        for (Entity entity : entities) {
            if (isScooterVisual(entity)) {
                entity.remove();
                continue;
            }
            if (entity instanceof Minecart minecart && hasScooterEntityTag(minecart)) {
                migrateOldMinecart(minecart);
                continue;
            }
            if (entity instanceof ArmorStand stand && isScooter(stand)) {
                registerLoadedScooter(stand);
            }
        }
    }

    public void registerLoadedScooter(ArmorStand stand) {
        if (!isScooter(stand)) {
            return;
        }
        configureScooterStand(stand);
        scooterSpeeds.put(stand.getUniqueId(), getStoredSpeed(stand));
        updateName(stand, getStoredSpeed(stand));
        ensureVisuals(stand);
        updateVisuals(stand);
    }

    public void resetSpeed(ArmorStand stand) {
        setSpeed(stand, MIN_SPEED);
    }

    public void cycleSpeed(Player player) {
        ArmorStand stand = getScooterRoot(player.getVehicle());
        if (stand == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Long lastClick = lastSpeedClicks.get(player.getUniqueId());
        if (lastClick != null && now - lastClick < CLICK_COOLDOWN_MS) {
            return;
        }
        lastSpeedClicks.put(player.getUniqueId(), now);

        int speed = getStoredSpeed(stand);
        int nextSpeed = speed >= MAX_SPEED ? MIN_SPEED : speed + 1;
        setSpeed(stand, nextSpeed);
        player.sendMessage(ChatColor.AQUA + "Скорость электросамоката: " + nextSpeed + " блок/с.");
    }

    public void removeScooter(ArmorStand stand, boolean dropItem) {
        Location dropLocation = stand.getLocation();
        stand.eject();
        removeVisuals(stand.getUniqueId());
        scooterSpeeds.remove(stand.getUniqueId());
        stand.remove();

        if (dropItem) {
            dropScooterItem(dropLocation);
        }
    }

    public void dropScooterItem(Location location) {
        World world = location.getWorld();
        if (world != null) {
            world.dropItemNaturally(location, createScooterItem(1));
        }
    }

    private void discoverLoadedScooters() {
        for (World world : Bukkit.getWorlds()) {
            registerLoadedEntities(world.getEntities().toArray(Entity[]::new));
        }
    }

    private void configureScooterStand(ArmorStand stand) {
        stand.setVisible(false);
        stand.setSmall(true);
        stand.setGravity(false);
        stand.setSilent(true);
        stand.setInvulnerable(false);
        stand.setBasePlate(false);
        stand.setArms(false);
        stand.setCustomNameVisible(false);
        stand.setPersistent(true);
    }

    private void migrateOldMinecart(Minecart minecart) {
        Location location = minecart.getLocation();
        location.setY(location.getY() + 0.05D);
        minecart.remove();
        spawnScooter(location);
    }

    private void tickScooters() {
        Iterator<Map.Entry<UUID, Integer>> iterator = scooterSpeeds.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof ArmorStand stand) || stand.isDead() || !isScooter(stand)) {
                removeVisuals(entry.getKey());
                iterator.remove();
                continue;
            }

            Player rider = findPlayerPassenger(stand);
            if (rider != null) {
                moveScooter(stand, rider, getStoredSpeed(stand));
            }
            updateVisuals(stand);
        }
    }

    private void moveScooter(ArmorStand stand, Player rider, int speed) {
        Vector direction = rider.getLocation().getDirection();
        direction.setY(0.0D);
        if (direction.lengthSquared() < 0.0001D) {
            return;
        }

        Location current = stand.getLocation();
        Location desired = current.clone().add(direction.normalize().multiply(speed / TICKS_PER_SECOND));
        desired.setYaw(rider.getLocation().getYaw());
        desired.setPitch(0.0F);

        Location next = findRideLocation(current, desired);
        if (next == null) {
            return;
        }

        stand.teleport(next);
    }

    private Location findRideLocation(Location current, Location desired) {
        double[] yOffsets = {0.0D, 0.5D, 1.0D, -0.5D, -1.0D};
        for (double yOffset : yOffsets) {
            Location candidate = desired.clone();
            candidate.setY(current.getY() + yOffset);
            if (hasRideSpace(candidate) && hasGround(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean hasRideSpace(Location location) {
        return location.getBlock().isPassable()
                && location.clone().add(0.0D, 1.0D, 0.0D).getBlock().isPassable();
    }

    private boolean hasGround(Location location) {
        return !location.clone().subtract(0.0D, 0.12D, 0.0D).getBlock().isPassable();
    }

    private Player findPlayerPassenger(ArmorStand stand) {
        for (Entity passenger : stand.getPassengers()) {
            if (passenger instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private void ensureVisuals(ArmorStand stand) {
        ScooterVisual current = visuals.get(stand.getUniqueId());
        if (current != null && current.isValid()) {
            return;
        }

        removeVisuals(stand.getUniqueId());

        Location location = stand.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        Interaction hitbox = world.spawn(location, Interaction.class);
        hitbox.setInteractionWidth(1.1F);
        hitbox.setInteractionHeight(1.35F);
        hitbox.setResponsive(true);
        hitbox.setGravity(false);
        hitbox.setInvulnerable(false);
        hitbox.setPersistent(false);
        hitbox.setSilent(true);
        tagVisual(hitbox, stand);

        List<UUID> partIds = new ArrayList<>();
        for (ModelPart part : MODEL_PARTS) {
            BlockDisplay display = world.spawn(modelLocation(stand, part), BlockDisplay.class);
            display.setBlock(part.material().createBlockData());
            display.setGravity(false);
            display.setPersistent(false);
            display.setSilent(true);
            display.setViewRange(32.0F);
            display.setShadowRadius(0.0F);
            display.setShadowStrength(0.0F);
            display.setTeleportDuration(1);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
            display.setTransformation(centeredBox(part));
            tagVisual(display, stand);
            partIds.add(display.getUniqueId());
        }

        visuals.put(stand.getUniqueId(), new ScooterVisual(hitbox.getUniqueId(), partIds));
    }

    private void updateVisuals(ArmorStand stand) {
        ensureVisuals(stand);
        ScooterVisual visual = visuals.get(stand.getUniqueId());
        if (visual == null) {
            return;
        }

        Entity hitboxEntity = Bukkit.getEntity(visual.hitboxId());
        if (hitboxEntity instanceof Interaction hitbox) {
            Location hitboxLocation = stand.getLocation().clone();
            hitboxLocation.setYaw(stand.getLocation().getYaw());
            hitbox.teleport(hitboxLocation);
        }

        for (int i = 0; i < MODEL_PARTS.size() && i < visual.partIds().size(); i++) {
            Entity partEntity = Bukkit.getEntity(visual.partIds().get(i));
            if (partEntity instanceof BlockDisplay display) {
                display.teleport(modelLocation(stand, MODEL_PARTS.get(i)));
            }
        }
    }

    private Location modelLocation(ArmorStand stand, ModelPart part) {
        Location base = stand.getLocation();
        Location location = base.clone().add(rotateOffset(base.getYaw(), part.offsetX(), part.offsetY(), part.offsetZ()));
        location.setYaw(base.getYaw());
        location.setPitch(0.0F);
        return location;
    }

    private Vector rotateOffset(float yaw, double x, double y, double z) {
        double radians = Math.toRadians(yaw);
        Vector right = new Vector(Math.cos(radians), 0.0D, Math.sin(radians));
        Vector forward = new Vector(-Math.sin(radians), 0.0D, Math.cos(radians));
        return right.multiply(x).add(forward.multiply(z)).add(new Vector(0.0D, y, 0.0D));
    }

    private Transformation centeredBox(ModelPart part) {
        return new Transformation(
                new Vector3f(-part.scaleX() / 2.0F, -part.scaleY() / 2.0F, -part.scaleZ() / 2.0F),
                new Quaternionf(),
                new Vector3f(part.scaleX(), part.scaleY(), part.scaleZ()),
                new Quaternionf()
        );
    }

    private void removeAllVisuals() {
        for (UUID scooterId : new ArrayList<>(visuals.keySet())) {
            removeVisuals(scooterId);
        }
    }

    private void removeVisuals(UUID scooterId) {
        ScooterVisual visual = visuals.remove(scooterId);
        if (visual == null) {
            return;
        }

        Entity hitbox = Bukkit.getEntity(visual.hitboxId());
        if (hitbox != null) {
            hitbox.remove();
        }

        for (UUID partId : visual.partIds()) {
            Entity part = Bukkit.getEntity(partId);
            if (part != null) {
                part.remove();
            }
        }
    }

    private boolean isScooterVisual(Entity entity) {
        return entity.getPersistentDataContainer().has(scooterVisualKey, PersistentDataType.BYTE);
    }

    private boolean hasScooterEntityTag(Entity entity) {
        return entity.getPersistentDataContainer().has(scooterEntityKey, PersistentDataType.BYTE);
    }

    private void tagVisual(Entity entity, ArmorStand root) {
        PersistentDataContainer container = entity.getPersistentDataContainer();
        container.set(scooterVisualKey, PersistentDataType.BYTE, (byte) 1);
        container.set(scooterRootKey, PersistentDataType.STRING, root.getUniqueId().toString());
    }

    private int getStoredSpeed(ArmorStand stand) {
        Integer cachedSpeed = scooterSpeeds.get(stand.getUniqueId());
        if (cachedSpeed != null) {
            return cachedSpeed;
        }

        PersistentDataContainer container = stand.getPersistentDataContainer();
        Integer storedSpeed = container.get(scooterSpeedKey, PersistentDataType.INTEGER);
        if (storedSpeed == null || storedSpeed < MIN_SPEED || storedSpeed > MAX_SPEED) {
            return MIN_SPEED;
        }
        return storedSpeed;
    }

    private void setSpeed(ArmorStand stand, int speed) {
        int clampedSpeed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
        stand.getPersistentDataContainer().set(scooterSpeedKey, PersistentDataType.INTEGER, clampedSpeed);
        scooterSpeeds.put(stand.getUniqueId(), clampedSpeed);
        updateName(stand, clampedSpeed);
    }

    private void tagScooter(ArmorStand stand, int speed) {
        stand.getPersistentDataContainer().set(scooterEntityKey, PersistentDataType.BYTE, (byte) 1);
        setSpeed(stand, speed);
    }

    private void updateName(ArmorStand stand, int speed) {
        stand.setCustomName(ChatColor.AQUA + "Электросамокат " + ChatColor.GRAY + "[" + speed + " блок/с]");
    }

    private record ModelPart(
            Material material,
            float scaleX,
            float scaleY,
            float scaleZ,
            double offsetX,
            double offsetY,
            double offsetZ
    ) {
    }

    private record ScooterVisual(UUID hitboxId, List<UUID> partIds) {
        private boolean isValid() {
            if (Bukkit.getEntity(hitboxId) == null) {
                return false;
            }
            for (UUID partId : partIds) {
                if (Bukkit.getEntity(partId) == null) {
                    return false;
                }
            }
            return true;
        }
    }
}
