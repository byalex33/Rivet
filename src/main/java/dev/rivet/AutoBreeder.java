package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
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
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
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
    private final NamespacedKey playerHologramKey;
    private final Set<NamespacedKey> recipeKeys = new HashSet<>();
    private final Set<String> breeders = new HashSet<>();
    private final Map<String, Inventory> inventories = new HashMap<>();
    private final YamlConfiguration data;
    private final YamlConfiguration settings;
    private final BukkitTask task;

    AutoBreeder(RivetPlugin plugin) {
        this.plugin = plugin;
        itemKey = new NamespacedKey(plugin, "auto_breeder");
        animalKey = new NamespacedKey(plugin, "auto_breeder_animal");
        hologramKey = new NamespacedKey(plugin, "auto_breeder_hologram");
        playerHologramKey = new NamespacedKey(plugin, "hologram");
        data = plugin.data("breeders");
        settings = plugin.settings("breeders");
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
        plugin.guiActions().run(event.getPlayer(), settings.getStringList("gui.open_commands"));
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

    boolean command(CommandSender sender, String[] args) {
        Player target;
        EntityType animal;
        String amountArgument = null;

        EntityType selfAnimal = args.length > 0 ? supportedAnimal(args[0]) : null;
        if (sender instanceof Player player && selfAnimal != null && args.length <= 2) {
            target = player;
            animal = selfAnimal;
            if (args.length == 2) {
                amountArgument = args[1];
            }
        } else if (args.length >= 2 && args.length <= 3) {
            target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(text("messages.player-not-online",
                    "<white>That player is not online.</white>"));
                return true;
            }
            animal = supportedAnimal(args[1]);
            if (args.length == 3) {
                amountArgument = args[2];
            }
        } else {
            usage(sender);
            return true;
        }

        if (animal == null) {
            sender.sendMessage(text("messages.unsupported-animal",
                "<white>Choose a supported animal type.</white>"));
            return true;
        }
        int amount = amountArgument == null ? 1 : RivetPlugin.itemAmount(amountArgument);
        if (amount < 1 || amount > 2304) {
            sender.sendMessage(text("messages.invalid-amount",
                "<white>Amount must be a whole number from 1 to 2304.</white>"));
            return true;
        }

        int remaining = amount;
        while (remaining > 0) {
            ItemStack breeder = item(animal);
            int stackAmount = Math.min(remaining, breeder.getMaxStackSize());
            breeder.setAmount(stackAmount);
            target.getInventory().addItem(breeder).values().forEach(leftover ->
                target.getWorld().dropItemNaturally(target.getLocation(), leftover));
            remaining -= stackAmount;
        }

        String description = amount + " " + displayName(animal) + " Auto Breeder"
            + (amount == 1 ? "" : "s");
        sender.sendMessage(text("messages.given",
            "<white>Gave <#f72a4c>%description%</#f72a4c> to <#f72a4c>%player%</#f72a4c>.</white>",
            Placeholder.unparsed("description", description),
            Placeholder.unparsed("player", target.getName())));
        if (!sender.equals(target)) {
            target.sendMessage(text("messages.received",
                "<white>You received <#f72a4c>%description%</#f72a4c>.</white>",
                Placeholder.unparsed("description", description)));
        }
        return true;
    }

    List<String> completions(CommandSender sender, String[] args) {
        List<String> animals = ANIMALS.keySet().stream()
            .map(type -> type.name().toLowerCase(Locale.ROOT)).sorted().toList();
        if (args.length == 1) {
            List<String> choices = new ArrayList<>();
            if (sender instanceof Player) {
                choices.addAll(animals);
            }
            choices.addAll(plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList());
            return choices;
        }
        if (args.length == 2) {
            return sender instanceof Player && supportedAnimal(args[0]) != null
                ? List.of("1", "16", "32", "64") : animals;
        }
        if (args.length == 3 && supportedAnimal(args[1]) != null) {
            return List.of("1", "16", "32", "64");
        }
        return List.of();
    }

    boolean clearHolograms(CommandSender sender) {
        List<Entity> displays = plugin.getServer().getWorlds().stream()
            .flatMap(world -> world.getEntities().stream())
            .filter(entity -> entity instanceof Display && clearableHologram(
                entity.getPersistentDataContainer().get(hologramKey, PersistentDataType.STRING),
                entity.getPersistentDataContainer().get(playerHologramKey, PersistentDataType.STRING)))
            .toList();
        displays.forEach(Entity::remove);
        ensureLoadedHolograms();
        sender.sendMessage(MM.deserialize(
            "<white>Cleared <#f72a4c>%count%</#f72a4c> loaded auto-breeder hologram%plural% "
                + "and refreshed active breeders. <#f72a4c>/holo</#f72a4c> holograms were not changed.",
            Placeholder.unparsed("count", Integer.toString(displays.size())),
            Placeholder.unparsed("plural", displays.size() == 1 ? "" : "s")));
        return true;
    }

    static boolean clearableHologram(String breederMarker, String playerMarker) {
        return breederMarker != null && playerMarker == null;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(text("messages.usage", "<white>Usage: /givebreeder &lt;animal&gt; [amount] or "
            + "/givebreeder &lt;player&gt; &lt;animal&gt; [amount]</white>"));
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
            TagResolver[] placeholders = displayPlaceholders(animal, food);
            meta.displayName(text("display.item-name", "<#f72a4c>%animal% Auto Breeder</#f72a4c>",
                placeholders));
            List<String> lore = settings.getStringList("display.item-lore");
            if (lore.isEmpty()) {
                lore = List.of("<white>Stores %food% and breeds nearby %animal%.</white>");
            }
            meta.lore(lore.stream().map(line -> MM.deserialize(line, placeholders)).toList());
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
            EntityType animal = EntityType.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_'));
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
            EntityType animal = animal(key);
            Inventory inventory = Bukkit.createInventory(holder, 9,
                MM.deserialize(settings.getString("display.inventory-title", "%animal% Auto Breeder"),
                    Placeholder.unparsed("animal", displayName(animal))));
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
        String effect = "effects.activation";
        if (settings.getBoolean(effect + ".sound.enabled", true)) {
            block.getWorld().playSound(center,
                ConfiguredEffect.sound(plugin, settings, effect + ".sound.name",
                    Sound.BLOCK_BEACON_POWER_SELECT),
                (float) settings.getDouble(effect + ".sound.volume", .7),
                (float) settings.getDouble(effect + ".sound.pitch", 1.5));
        }
        Color first = ConfiguredEffect.color(plugin, settings,
            effect + ".particles.first-color", 0xFFD166);
        Color second = ConfiguredEffect.color(plugin, settings,
            effect + ".particles.second-color", 0x55FFAA);
        float dustSize = (float) Math.max(.01,
            settings.getDouble(effect + ".particles.dust-size", .8));
        new BukkitRunnable() {
            private int step;

            @Override
            public void run() {
                if (step == 16) {
                    cancel();
                    return;
                }
                if (!settings.getBoolean(effect + ".particles.enabled", true)) {
                    step++;
                    return;
                }
                double angle = step * Math.PI / 4;
                if (settings.getBoolean(effect + ".particles.spiral-enabled", true)) {
                    for (int side : new int[] {-1, 1}) {
                        Location spiral = center.clone().add(Math.cos(angle) * .45 * side,
                            step * .08, Math.sin(angle) * .45 * side);
                        block.getWorld().spawnParticle(Particle.DUST, spiral, 1,
                            new Particle.DustOptions(side < 0 ? first : second, dustSize));
                    }
                }
                if (settings.getBoolean(effect + ".particles.connection-enabled", true)) {
                    for (Animals animal : animals) {
                        Location target = animal.getLocation().add(0, .8, 0);
                        Location spark = center.clone().add(target.toVector().subtract(center.toVector())
                            .multiply((step + 1) / 16d));
                        block.getWorld().spawnParticle(Particle.END_ROD, spark, 1, 0, 0, 0, 0);
                    }
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
            display.text(configuredHologram(animal(key), food(inventory(key), food(key)), animalsBred(key)));
            return;
        }
        existing.forEach(Entity::remove);
        location.getWorld().spawn(location.clone().add(.5, 1.6, .5), TextDisplay.class, display -> {
            display.text(configuredHologram(animal(key), food(inventory(key), food(key)), animalsBred(key)));
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
        return Component.text(displayName(animal) + " Auto Breeder", RivetPalette.SECONDARY)
            .append(Component.newline())
            .append(Component.text(displayName(ANIMALS.get(animal)) + ": ", RivetPalette.PRIMARY))
            .append(Component.text(Integer.toString(food), RivetPalette.SECONDARY))
            .append(Component.newline())
            .append(Component.text("Animals bred: ", RivetPalette.PRIMARY))
            .append(Component.text(Integer.toString(animalsBred), RivetPalette.SECONDARY));
    }

    private Component configuredHologram(EntityType animal, int food, int animalsBred) {
        Material material = ANIMALS.get(animal);
        return text("display.hologram",
            "<#f72a4c>%animal% Auto Breeder</#f72a4c><newline><white>%food%: "
                + "<#f72a4c>%amount%</#f72a4c></white><newline><white>Animals bred: "
                + "<#f72a4c>%bred%</#f72a4c></white>",
            Placeholder.unparsed("animal", displayName(animal)),
            Placeholder.unparsed("food", displayName(material)),
            Placeholder.unparsed("amount", Integer.toString(food)),
            Placeholder.unparsed("bred", Integer.toString(animalsBred)));
    }

    private TagResolver[] displayPlaceholders(EntityType animal, Material food) {
        return new TagResolver[] {
            Placeholder.unparsed("animal", displayName(animal)),
            Placeholder.unparsed("food", displayName(food))
        };
    }

    private Component text(String path, String fallback, TagResolver... placeholders) {
        return MM.deserialize(settings.getString(path, fallback), placeholders);
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
