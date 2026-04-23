package ru.saita.electrosamiki;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    public static final int MIN_SPEED = 0;
    public static final int MAX_SPEED = 8;

    private static final long CLICK_COOLDOWN_MS = 250L;
    private static final double TICKS_PER_SECOND = 20.0D;
    private static final int SPEED_STEP = 1;
    private static final double ACCELERATION_PER_TICK = 0.24D;
    private static final double BRAKE_PER_TICK = 0.42D;
    private static final double OBSTACLE_BRAKE_PER_TICK = 1.0D;
    private static final double IDLE_SPEED_EPSILON = 0.03D;
    private static final double MAX_MOVE_STEP = 0.18D;
    private static final float TURN_DEGREES_PER_TICK = 9.0F;
    private static final double[][] SPACE_CHECK_OFFSETS = {
            {0.0D, 0.0D},
            {0.34D, 0.42D},
            {-0.34D, 0.42D},
            {0.34D, -0.42D},
            {-0.34D, -0.42D},
            {0.0D, 0.68D},
            {0.0D, -0.68D}
    };
    private static final double[][] GROUND_CHECK_OFFSETS = {
            {0.0D, 0.0D},
            {0.0D, 0.58D},
            {0.0D, -0.58D}
    };

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
    private final Map<UUID, UUID> riderScooters = new HashMap<>();
    private final Map<UUID, UUID> scooterRiders = new HashMap<>();
    private final Map<UUID, RideState> rideStates = new HashMap<>();
    private final Map<UUID, Long> lastSpeedClicks = new HashMap<>();
    private final Set<UUID> activelyMoving = new HashSet<>();
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
        riderScooters.clear();
        scooterRiders.clear();
        rideStates.clear();
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
                ChatColor.GRAY + "ПКМ во время езды: газ",
                ChatColor.GRAY + "ЛКМ во время езды: тормоз",
                ChatColor.GRAY + "Shift: слезть"
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
        return riderScooters.containsKey(player.getUniqueId());
    }

    public boolean isActivelyMoving(ArmorStand stand) {
        return activelyMoving.contains(stand.getUniqueId());
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

    public boolean isOccupied(ArmorStand stand) {
        return scooterRiders.containsKey(stand.getUniqueId()) || !stand.getPassengers().isEmpty();
    }

    public void startRide(Player player, ArmorStand stand) {
        stopRide(player);
        UUID previousRiderId = scooterRiders.get(stand.getUniqueId());
        if (previousRiderId != null) {
            Player previousRider = Bukkit.getPlayer(previousRiderId);
            if (previousRider != null) {
                stopRide(previousRider);
            } else {
                riderScooters.remove(previousRiderId);
            }
        }

        riderScooters.put(player.getUniqueId(), stand.getUniqueId());
        scooterRiders.put(stand.getUniqueId(), player.getUniqueId());
        rideStates.put(stand.getUniqueId(), new RideState(0.0D, MIN_SPEED, stand.getLocation().getYaw()));
        resetSpeed(stand);
        stand.eject();
        player.setFallDistance(0.0F);
        if (!stand.addPassenger(player)) {
            player.teleport(seatLocation(stand, player));
            stand.addPassenger(player);
        }
    }

    public void stopRide(Player player) {
        UUID scooterId = riderScooters.remove(player.getUniqueId());
        if (scooterId == null) {
            return;
        }

        scooterRiders.remove(scooterId);
        rideStates.remove(scooterId);
        lastSpeedClicks.remove(player.getUniqueId());
        Entity entity = Bukkit.getEntity(scooterId);
        if (entity instanceof ArmorStand stand && isScooter(stand)) {
            resetSpeed(stand);
            stand.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
            stand.eject();
            player.setFallDistance(0.0F);
            player.teleport(exitLocation(stand, player));
        }
    }

    public void increaseSpeed(Player player) {
        adjustSpeed(player, SPEED_STEP);
    }

    public void decreaseSpeed(Player player) {
        adjustSpeed(player, -SPEED_STEP);
    }

    private void adjustSpeed(Player player, int delta) {
        UUID scooterId = riderScooters.get(player.getUniqueId());
        if (scooterId == null) {
            return;
        }

        Entity entity = Bukkit.getEntity(scooterId);
        if (!(entity instanceof ArmorStand stand) || !isScooter(stand)) {
            riderScooters.remove(player.getUniqueId());
            scooterRiders.remove(scooterId);
            rideStates.remove(scooterId);
            lastSpeedClicks.remove(player.getUniqueId());
            return;
        }

        long now = System.currentTimeMillis();
        Long lastClick = lastSpeedClicks.get(player.getUniqueId());
        if (lastClick != null && now - lastClick < CLICK_COOLDOWN_MS) {
            return;
        }
        lastSpeedClicks.put(player.getUniqueId(), now);

        RideState state = rideStates.computeIfAbsent(
                stand.getUniqueId(),
                id -> new RideState(0.0D, getStoredSpeed(stand), stand.getLocation().getYaw())
        );
        int nextSpeed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, state.targetSpeed + delta));
        if (nextSpeed == state.targetSpeed) {
            return;
        }
        state.targetSpeed = nextSpeed;
        setSpeed(stand, nextSpeed);
        player.sendMessage(ChatColor.AQUA + "Скорость электросамоката: " + nextSpeed + " блок/с.");
    }

    public void removeScooter(ArmorStand stand, boolean dropItem) {
        Location dropLocation = stand.getLocation();
        UUID riderId = scooterRiders.remove(stand.getUniqueId());
        if (riderId != null) {
            riderScooters.remove(riderId);
        }
        rideStates.remove(stand.getUniqueId());
        stand.eject();
        stand.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        if (riderId != null) {
            Player rider = Bukkit.getPlayer(riderId);
            if (rider != null) {
                rider.setFallDistance(0.0F);
                rider.teleport(exitLocation(stand, rider));
            }
        }
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

            boolean refreshVisuals = !hasValidVisuals(stand);
            Player rider = findRider(stand);
            if (rider != null) {
                RideState state = rideStates.computeIfAbsent(
                        stand.getUniqueId(),
                        id -> new RideState(0.0D, getStoredSpeed(stand), stand.getLocation().getYaw())
                );
                refreshVisuals = moveScooter(stand, rider, state) || refreshVisuals;
            } else {
                stand.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
                rideStates.remove(stand.getUniqueId());
                if (getStoredSpeed(stand) != MIN_SPEED) {
                    resetSpeed(stand);
                }
            }
            if (refreshVisuals) {
                updateVisuals(stand);
            }
        }
    }

    private boolean moveScooter(ArmorStand stand, Player rider, RideState state) {
        activelyMoving.add(stand.getUniqueId());
        try {
            state.yaw = approachYaw(state.yaw, rider.getLocation().getYaw(), TURN_DEGREES_PER_TICK);
            state.currentSpeed = approach(
                    state.currentSpeed,
                    state.targetSpeed,
                    state.targetSpeed > state.currentSpeed ? ACCELERATION_PER_TICK : BRAKE_PER_TICK
            );
            if (state.currentSpeed < IDLE_SPEED_EPSILON) {
                state.currentSpeed = 0.0D;
            }

            Location current = stand.getLocation();
            current.setYaw(state.yaw);
            current.setPitch(0.0F);

            if (state.currentSpeed <= 0.0D) {
                stand.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
                return rotateScooter(stand, current);
            }

            Vector direction = forwardVector(state.yaw);
            Location next = traceRideLocation(current, direction, state.currentSpeed / TICKS_PER_SECOND);

            if (next == null) {
                state.targetSpeed = MIN_SPEED;
                state.currentSpeed = Math.max(0.0D, state.currentSpeed - OBSTACLE_BRAKE_PER_TICK);
                setSpeed(stand, MIN_SPEED);
                stand.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
                return rotateScooter(stand, current);
            }

            next.setYaw(state.yaw);
            next.setPitch(0.0F);
            stand.teleport(next);
            stand.setFallDistance(0.0F);
            rider.setFallDistance(0.0F);
            return true;
        } finally {
            activelyMoving.remove(stand.getUniqueId());
            if (!stand.getPassengers().contains(rider)) {
                stand.addPassenger(rider);
            }
        }
    }

    private Location traceRideLocation(Location start, Vector direction, double distance) {
        int steps = Math.max(1, (int) Math.ceil(distance / MAX_MOVE_STEP));
        double stepDistance = distance / steps;
        Location current = start.clone();
        for (int i = 0; i < steps; i++) {
            Location desired = current.clone().add(direction.clone().multiply(stepDistance));
            desired.setYaw(start.getYaw());
            desired.setPitch(0.0F);
            Location next = findRideLocation(current, desired);
            if (next == null) {
                return i == 0 ? null : current;
            }
            current = next;
        }
        return current;
    }

    private Location findRideLocation(Location current, Location desired) {
        double[] yOffsets = {0.0D, 0.25D, 0.5D, 0.75D, 1.0D, -0.25D, -0.5D, -0.75D, -1.0D};
        for (double yOffset : yOffsets) {
            Location candidate = desired.clone();
            candidate.setY(current.getY() + yOffset);
            candidate.setYaw(desired.getYaw());
            candidate.setPitch(0.0F);
            if (!isLoaded(candidate)) {
                return null;
            }
            if (hasRideSpace(candidate) && hasGround(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean hasRideSpace(Location location) {
        for (double[] offset : SPACE_CHECK_OFFSETS) {
            Location check = location.clone().add(rotateOffset(location.getYaw(), offset[0], 0.0D, offset[1]));
            if (!check.getBlock().isPassable()
                    || !check.clone().add(0.0D, 1.0D, 0.0D).getBlock().isPassable()) {
                return false;
            }
        }
        return true;
    }

    private boolean hasGround(Location location) {
        for (double[] offset : GROUND_CHECK_OFFSETS) {
            Location check = location.clone().add(rotateOffset(location.getYaw(), offset[0], -0.12D, offset[1]));
            if (!check.getBlock().isPassable()) {
                return true;
            }
        }
        return false;
    }

    private boolean isLoaded(Location location) {
        World world = location.getWorld();
        return world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private boolean rotateScooter(ArmorStand stand, Location rotated) {
        if (Math.abs(wrapDegrees(rotated.getYaw() - stand.getLocation().getYaw())) > 0.1F) {
            stand.teleport(rotated);
            return true;
        }
        return false;
    }

    private Vector forwardVector(float yaw) {
        double radians = Math.toRadians(yaw);
        return new Vector(-Math.sin(radians), 0.0D, Math.cos(radians));
    }

    private double approach(double current, double target, double maxStep) {
        if (current < target) {
            return Math.min(target, current + maxStep);
        }
        return Math.max(target, current - maxStep);
    }

    private float approachYaw(float current, float target, float maxStep) {
        float delta = wrapDegrees(target - current);
        if (Math.abs(delta) <= maxStep) {
            return normalizeYaw(target);
        }
        return normalizeYaw((float) (current + Math.copySign(maxStep, delta)));
    }

    private float normalizeYaw(float yaw) {
        return wrapDegrees(yaw);
    }

    private float wrapDegrees(float angle) {
        angle %= 360.0F;
        if (angle >= 180.0F) {
            angle -= 360.0F;
        }
        if (angle < -180.0F) {
            angle += 360.0F;
        }
        return angle;
    }

    private Player findRider(ArmorStand stand) {
        UUID riderId = scooterRiders.get(stand.getUniqueId());
        if (riderId == null) {
            return null;
        }

        Player rider = Bukkit.getPlayer(riderId);
        if (rider == null || !rider.isOnline() || !rider.getWorld().equals(stand.getWorld())) {
            scooterRiders.remove(stand.getUniqueId());
            riderScooters.remove(riderId);
            rideStates.remove(stand.getUniqueId());
            return null;
        }
        if (!stand.getPassengers().contains(rider)) {
            stand.addPassenger(rider);
        }
        return rider;
    }

    private Location seatLocation(ArmorStand stand, Player rider) {
        Location seat = stand.getLocation().clone().add(0.0D, 0.25D, 0.0D);
        seat.setYaw(rider.getLocation().getYaw());
        seat.setPitch(rider.getLocation().getPitch());
        return seat;
    }

    private Location exitLocation(ArmorStand stand, Player rider) {
        Location exit = stand.getLocation().clone().add(rotateOffset(stand.getLocation().getYaw(), 0.85D, 0.0D, 0.0D));
        exit.setYaw(rider.getLocation().getYaw());
        exit.setPitch(rider.getLocation().getPitch());
        return exit;
    }

    private boolean hasValidVisuals(ArmorStand stand) {
        ScooterVisual visual = visuals.get(stand.getUniqueId());
        return visual != null && visual.isValid();
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

    private static final class RideState {
        private double currentSpeed;
        private int targetSpeed;
        private float yaw;

        private RideState(double currentSpeed, int targetSpeed, float yaw) {
            this.currentSpeed = currentSpeed;
            this.targetSpeed = targetSpeed;
            this.yaw = yaw;
        }
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
