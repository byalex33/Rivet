package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class AutoBreeder implements Listener {
    private static final String CONFIG_PATH = "auto-breeders";
    private static final int RANGE = 8;
    private static final Map<EntityType, Material> ANIMALS = Map.ofEntries(
        Map.entry(EntityType.ARMADILLO, Material.SPIDER_EYE),
        Map.entry(EntityType.AXOLOTL, Material.TROPICAL_FISH_BUCKET),
        Map.entry(EntityType.BEE, Material.DANDELION),
        Map.entry(EntityType.CAMEL, Material.CACTUS),
        Map.entry(EntityType.CAT, Material.COD),
        Map.entry(EntityType.CHICKEN, Material.WHEAT_SEEDS),
        Map.entry(EntityType.COW, Material.WHEAT),
        Map.entry(EntityType.DONKEY, Material.GOLDEN_CARROT),
        Map.entry(EntityType.FOX, Material.SWEET_BERRIES),
        Map.entry(EntityType.FROG, Material.SLIME_BALL),
        Map.entry(EntityType.GOAT, Material.WHEAT),
        Map.entry(EntityType.HOGLIN, Material.CRIMSON_FUNGUS),
        Map.entry(EntityType.HORSE, Material.GOLDEN_CARROT),
        Map.entry(EntityType.LLAMA, Material.HAY_BLOCK),
        Map.entry(EntityType.MOOSHROOM, Material.WHEAT),
        Map.entry(EntityType.NAUTILUS, Material.PUFFERFISH),
        Map.entry(EntityType.OCELOT, Material.COD),
        Map.entry(EntityType.PANDA, Material.BAMBOO),
        Map.entry(EntityType.PIG, Material.CARROT),
        Map.entry(EntityType.RABBIT, Material.CARROT),
        Map.entry(EntityType.SHEEP, Material.WHEAT),
        Map.entry(EntityType.SNIFFER, Material.TORCHFLOWER_SEEDS),
        Map.entry(EntityType.STRIDER, Material.WARPED_FUNGUS),
        Map.entry(EntityType.TURTLE, Material.SEAGRASS),
        Map.entry(EntityType.WOLF, Material.BEEF)
    );

    private final RivetPlugin plugin;
    private final NamespacedKey itemKey;
    private final NamespacedKey animalKey;
    private final NamespacedKey hologramKey;
    private final Set<NamespacedKey> recipeKeys = new HashSet<>();
    private final Set<String> breeders = new HashSet<>();
    private final Map<String, Inventory> inventories = new HashMap<>();
    private final YamlConfiguration data;
    private final BukkitTask task;

    AutoBreeder(RivetPlugin plugin) {
        this.plugin = plugin;
        itemKey = new NamespacedKey(plugin, "auto_breeder");
        animalKey = new NamespacedKey(plugin, "auto_breeder_animal");
        hologramKey = new NamespacedKey(plugin, "auto_breeder_hologram");
        data = plugin.data("breeders");
        ConfigurationSection saved = data.getConfigurationSection(CONFIG_PATH);
        if (saved != null) {
            breeders.addAll(saved.getKeys(false));
            boolean migrated = false;
            for (String key : breeders) {
                if (saved.isInt(key)) {
                    int food = saved.getInt(key);
                    saved.set(key, null);
                    saved.set(key + ".food", food);
                    saved.set(key + ".animals-bred", 0);
                    migrated = true;
                }
                if (!saved.contains(key + ".animal")) {
                    saved.set(key + ".animal", EntityType.COW.name());
                    migrated = true;
                }
                if (!saved.contains(key + ".food") && saved.contains(key + ".wheat")) {
                    saved.set(key + ".food", saved.getInt(key + ".wheat"));
                    migrated = true;
                }
                if (!saved.contains(key + ".animals-bred") && saved.contains(key + ".cows-bred")) {
                    saved.set(key + ".animals-bred", saved.getInt(key + ".cows-bred"));
                    migrated = true;
                }
            }
            if (migrated) {
                save();
            }
        }
        registerRecipes();
        plugin.getServer().getOnlinePlayers().forEach(player -> player.discoverRecipes(recipeKeys));
        plugin.getServer().getScheduler().runTask(plugin, this::ensureLoadedHolograms);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::breed, 100, 100);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().discoverRecipes(recipeKeys);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        EntityType animal = animal(event.getItemInHand());
        if (animal == null) {
            return;
        }
        String key = key(event.getBlockPlaced());
        breeders.add(key);
        data.set(path(key) + ".animal", animal.name());
        data.set(path(key) + ".food", 0);
        data.set(path(key) + ".animals-bred", 0);
        save();
        ensureHologram(key);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
            || block == null || !isBreeder(block)) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().openInventory(inventory(key(block)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!isBreeder(event.getBlock())) {
            return;
        }
        event.setDropItems(false);
        remove(event.getBlock().getLocation(), event.getPlayer().getGameMode() != GameMode.CREATIVE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        explode(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        explode(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isBreeder)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isBreeder)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        List<String> loaded = breeders.stream().filter(key -> {
            Location location = location(key);
            return location != null && location.getWorld().equals(event.getWorld())
                && location.getBlockX() >> 4 == event.getChunk().getX()
                && location.getBlockZ() >> 4 == event.getChunk().getZ();
        }).toList();
        if (!loaded.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin,
                () -> loaded.forEach(this::ensureHologram));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BreederHolder holder)) {
            return;
        }

        boolean insertingIntoTop = event.getClickedInventory() == top
            && switch (event.getAction()) {
                case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR ->
                    event.getCursor().getType() != food(holder.key);
                case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> {
                    int slot = event.getHotbarButton();
                    ItemStack item = slot >= 0 ? event.getWhoClicked().getInventory().getItem(slot)
                        : event.getWhoClicked().getInventory().getItemInOffHand();
                    yield item != null && !item.getType().isAir()
                        && item.getType() != food(holder.key);
                }
                default -> false;
            };
        boolean shiftInserting = event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
            && event.getClickedInventory() != top && event.getCurrentItem() != null
            && event.getCurrentItem().getType() != food(holder.key);
        if (insertingIntoTop || shiftInserting) {
            event.setCancelled(true);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> persist(holder.key, top));
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BreederHolder holder)) {
            return;
        }
        if (event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize())
            && event.getOldCursor().getType() != food(holder.key)) {
            event.setCancelled(true);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> persist(holder.key, top));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof BreederHolder holder) {
            persist(holder.key, event.getInventory());
        }
    }

    void shutdown() {
        task.cancel();
        inventories.forEach(this::persist);
        recipeKeys.forEach(Bukkit::removeRecipe);
    }

    private void registerRecipes() {
        ANIMALS.forEach((animal, food) -> {
            NamespacedKey key = recipeKey(animal);
            recipeKeys.add(key);
            ShapedRecipe recipe = new ShapedRecipe(key, item(animal));
            recipe.shape("FEF", "RRR", "III");
            recipe.setIngredient('F', food);
            recipe.setIngredient('E', Material.valueOf(animal.name() + "_SPAWN_EGG"));
            recipe.setIngredient('R', Material.REDSTONE);
            recipe.setIngredient('I', Material.IRON_INGOT);
            if (Bukkit.getRecipe(key) != null) {
                Bukkit.removeRecipe(key);
            }
            if (!Bukkit.addRecipe(recipe)) {
                plugin.getLogger().warning("Could not register the " + displayName(animal)
                    + " auto breeder recipe.");
            }
        });
    }

    private NamespacedKey recipeKey(EntityType animal) {
        if (animal == EntityType.COW) {
            return new NamespacedKey(plugin, "auto_breeder_recipe");
        }
        return new NamespacedKey(plugin, "auto_breeder_" + animal.getKey().getKey());
    }

    private ItemStack item(EntityType animal) {
        Material food = ANIMALS.get(animal);
        ItemStack item = new ItemStack(Material.END_ROD);
        item.editMeta(meta -> {
            meta.displayName(Component.text(displayName(animal) + " Auto Breeder", NamedTextColor.GOLD));
            meta.lore(List.of(Component.text("Stores " + displayName(food)
                + " and breeds nearby " + displayName(animal) + ".", NamedTextColor.GRAY)));
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(animalKey, PersistentDataType.STRING, animal.name());
        });
        return item;
    }

    private EntityType animal(ItemStack item) {
        if (!item.hasItemMeta()
            || !item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE)) {
            return null;
        }
        String name = item.getItemMeta().getPersistentDataContainer()
            .get(animalKey, PersistentDataType.STRING);
        return name == null ? EntityType.COW : supportedAnimal(name);
    }

    private EntityType animal(String key) {
        EntityType animal = supportedAnimal(data.getString(path(key) + ".animal"));
        return animal == null ? EntityType.COW : animal;
    }

    private Material food(String key) {
        return ANIMALS.get(animal(key));
    }

    static EntityType supportedAnimal(String name) {
        try {
            EntityType animal = EntityType.valueOf(name);
            return ANIMALS.containsKey(animal) ? animal : null;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    static Material breedingFood(EntityType animal) {
        return ANIMALS.get(animal);
    }

    private static String displayName(Enum<?> value) {
        return java.util.Arrays.stream(value.name().split("_"))
            .map(word -> word.charAt(0) + word.substring(1).toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(" "));
    }

    private boolean isBreeder(Block block) {
        return block.getType() == Material.END_ROD && breeders.contains(key(block));
    }

    private Inventory inventory(String key) {
        return inventories.computeIfAbsent(key, ignored -> {
            BreederHolder holder = new BreederHolder(key);
            Inventory inventory = Bukkit.createInventory(holder, 9,
                Component.text(displayName(animal(key)) + " Auto Breeder"));
            holder.inventory = inventory;
            Material food = food(key);
            int amount = data.getInt(path(key) + ".food");
            while (amount > 0) {
                int stack = Math.min(amount, food.getMaxStackSize());
                inventory.addItem(new ItemStack(food, stack));
                amount -= stack;
            }
            return inventory;
        });
    }

    private void breed() {
        for (String key : List.copyOf(breeders)) {
            Location location = location(key);
            if (location == null) {
                continue;
            }
            Block block = location.getBlock();
            if (block.getType() != Material.END_ROD) {
                remove(location, false);
                continue;
            }

            EntityType animal = animal(key);
            Material food = ANIMALS.get(animal);
            Inventory inventory = inventory(key);
            if (food(inventory, food) < 2) {
                continue;
            }
            List<Animals> animals = location.getWorld().getNearbyEntities(location.clone().add(.5, .5, .5),
                    RANGE, RANGE, RANGE).stream()
                .filter(entity -> entity.getType() == animal)
                .map(Animals.class::cast)
                .filter(candidate -> candidate.isBreedItem(food))
                .filter(candidate -> readyToBreed(candidate.canBreed(), candidate.getLoveModeTicks()))
                .toList();
            Animals[] pair = pair(animals);
            if (pair == null) {
                continue;
            }

            consumeFood(inventory, food, 2);
            if (food == Material.TROPICAL_FISH_BUCKET) {
                for (int bucket = 0; bucket < pair.length; bucket++) {
                    location.getWorld().dropItemNaturally(location, new ItemStack(Material.WATER_BUCKET));
                }
            }
            pair[0].setLoveModeTicks(600);
            pair[1].setLoveModeTicks(600);
            activate(location, pair);
            data.set(path(key) + ".animals-bred", animalsBred(key) + pair.length);
            persist(key, inventory);
        }
    }

    private void activate(Location block, Animals[] animals) {
        Location center = block.clone().add(.5, .15, .5);
        block.getWorld().playSound(center, Sound.BLOCK_BEACON_POWER_SELECT, .7f, 1.5f);
        new BukkitRunnable() {
            private int step;

            @Override
            public void run() {
                if (step == 16) {
                    cancel();
                    return;
                }
                double angle = step * Math.PI / 4;
                for (int side : new int[] {-1, 1}) {
                    Location spiral = center.clone().add(Math.cos(angle) * .45 * side,
                        step * .08, Math.sin(angle) * .45 * side);
                    Color color = side < 0 ? Color.fromRGB(255, 209, 102)
                        : Color.fromRGB(85, 255, 170);
                    block.getWorld().spawnParticle(Particle.DUST, spiral, 1,
                        new Particle.DustOptions(color, .8f));
                }
                for (Animals animal : animals) {
                    Location target = animal.getLocation().add(0, .8, 0);
                    Location spark = center.clone().add(target.toVector().subtract(center.toVector())
                        .multiply((step + 1) / 16d));
                    block.getWorld().spawnParticle(Particle.END_ROD, spark, 1, 0, 0, 0, 0);
                }
                step++;
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    private static Animals[] pair(List<Animals> animals) {
        // ponytail: O(n²) is bounded by animals in one small pen; use spatial buckets only for giant farms.
        for (int first = 0; first < animals.size(); first++) {
            for (int second = first + 1; second < animals.size(); second++) {
                if (animals.get(first).getLocation()
                    .distanceSquared(animals.get(second).getLocation()) <= 64) {
                    return new Animals[] {animals.get(first), animals.get(second)};
                }
            }
        }
        return null;
    }

    private void explode(List<Block> blocks) {
        for (Block block : new ArrayList<>(blocks)) {
            if (!isBreeder(block)) {
                continue;
            }
            blocks.remove(block);
            Location location = block.getLocation();
            block.setType(Material.AIR, false);
            remove(location, true);
        }
    }

    private void remove(Location location, boolean dropBlock) {
        String key = key(location);
        Inventory inventory = inventories.remove(key);
        EntityType animal = animal(key);
        Material food = ANIMALS.get(animal);
        int amount = inventory == null
            ? data.getInt(path(key) + ".food") : food(inventory, food);
        breeders.remove(key);
        data.set(path(key), null);
        removeHologram(key, location);
        if (inventory != null) {
            List.copyOf(inventory.getViewers()).forEach(HumanEntity::closeInventory);
        }
        while (amount > 0) {
            int stack = Math.min(amount, food.getMaxStackSize());
            location.getWorld().dropItemNaturally(location, new ItemStack(food, stack));
            amount -= stack;
        }
        if (dropBlock) {
            location.getWorld().dropItemNaturally(location, item(animal));
        }
        save();
    }

    private void persist(String key, Inventory inventory) {
        if (!breeders.contains(key)) {
            return;
        }
        data.set(path(key) + ".food", food(inventory, food(key)));
        save();
        ensureHologram(key);
    }

    private void ensureLoadedHolograms() {
        breeders.forEach(key -> {
            Location location = location(key);
            if (location != null && location.getWorld().isChunkLoaded(
                location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                ensureHologram(key);
            }
        });
    }

    private void ensureHologram(String key) {
        Location location = location(key);
        if (location == null || !location.getWorld().isChunkLoaded(
            location.getBlockX() >> 4, location.getBlockZ() >> 4) || !isBreeder(location.getBlock())) {
            return;
        }
        List<Entity> existing = holograms(key, location);
        if (existing.size() == 1 && existing.getFirst() instanceof TextDisplay display) {
            display.text(hologram(animal(key), food(inventory(key), food(key)), animalsBred(key)));
            return;
        }
        existing.forEach(Entity::remove);
        location.getWorld().spawn(location.clone().add(.5, 1.6, .5), TextDisplay.class, display -> {
            display.text(hologram(animal(key), food(inventory(key), food(key)), animalsBred(key)));
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
            display.setShadowed(true);
            display.setPersistent(true);
            display.setInvulnerable(true);
            display.getPersistentDataContainer().set(hologramKey, PersistentDataType.STRING, key);
        });
    }

    private List<Entity> holograms(String key, Location location) {
        return new ArrayList<>(location.getWorld().getNearbyEntities(
            location.clone().add(.5, 1.6, .5), 1, 2, 1,
            entity -> key.equals(entity.getPersistentDataContainer()
                .get(hologramKey, PersistentDataType.STRING))));
    }

    private void removeHologram(String key, Location location) {
        if (location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            holograms(key, location).forEach(Entity::remove);
        }
    }

    private int animalsBred(String key) {
        return data.getInt(path(key) + ".animals-bred");
    }

    private void save() {
        try {
            plugin.saveData("breeders");
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/breeders.yml: " + exception.getMessage());
        }
    }

    static Component hologram(EntityType animal, int food, int animalsBred) {
        return Component.text(displayName(animal) + " Auto Breeder", NamedTextColor.GOLD)
            .append(Component.newline())
            .append(Component.text(displayName(ANIMALS.get(animal)) + ": ", NamedTextColor.YELLOW))
            .append(Component.text(Integer.toString(food), NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("Animals bred: ", NamedTextColor.GREEN))
            .append(Component.text(Integer.toString(animalsBred), NamedTextColor.WHITE));
    }

    private static int food(Inventory inventory, Material food) {
        int amount = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == food) {
                amount += item.getAmount();
            }
        }
        return amount;
    }

    private static void consumeFood(Inventory inventory, Material food, int amount) {
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType() != food) {
                continue;
            }
            int consumed = Math.min(item.getAmount(), amount);
            item.subtract(consumed);
            amount -= consumed;
            if (amount == 0) {
                return;
            }
        }
    }

    private static String key(Block block) {
        return key(block.getLocation());
    }

    private static String key(Location location) {
        return location.getWorld().getUID() + "," + location.getBlockX() + ","
            + location.getBlockY() + "," + location.getBlockZ();
    }

    private static String path(String key) {
        return CONFIG_PATH + "." + key;
    }

    private Location location(String key) {
        try {
            String[] parts = key.split(",", 4);
            World world = Bukkit.getWorld(UUID.fromString(parts[0]));
            return world == null ? null : new Location(world, Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException exception) {
            return null;
        }
    }

    static boolean readyToBreed(boolean canBreed, int loveModeTicks) {
        return canBreed && loveModeTicks == 0;
    }

    private static final class BreederHolder implements InventoryHolder {
        private final String key;
        private Inventory inventory;

        private BreederHolder(String key) {
            this.key = key;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
