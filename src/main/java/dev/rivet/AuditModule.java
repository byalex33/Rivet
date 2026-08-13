package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

final class AuditModule implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();
    private static final Set<String> PRIVATE_OR_CHAT_COMMANDS = Set.of(
        "msg", "message", "tell", "w", "whisper", "r", "reply", "me", "say",
        "teammsg", "tm", "tellraw", "pm", "dm", "m", "emsg", "etell",
        "ewhisper", "er", "mail");
    private static final Set<AuditAction> BLOCK_ACTIONS = java.util.Arrays.stream(AuditAction.values())
        .filter(AuditAction::blockHistory).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<AuditAction> CONTAINER_ACTIONS = Set.of(
        AuditAction.CONTAINER_ADD, AuditAction.CONTAINER_REMOVE);

    private final RivetPlugin plugin;
    private final YamlConfiguration configuration;
    private final AuditStorage storage;
    private final Set<UUID> inspectors = new HashSet<>();
    private final Set<String> ignoredBlockBreakChecks = new HashSet<>();
    private final Map<String, SearchState> searches = new HashMap<>();
    private final Map<String, PendingContainer> pendingContainers = new HashMap<>();
    private AuditSettings settings;

    AuditModule(RivetPlugin plugin) throws SQLException {
        this.plugin = plugin;
        configuration = plugin.settings("logs");
        settings = new AuditSettings(configuration);
        storage = new AuditStorage(plugin.getDataFolder().toPath().resolve("logs.db"),
            throwable -> plugin.getLogger().log(Level.SEVERE, "Audit storage failed", throwable));
        purgeExpired();
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,
            this::purgeExpired, 20L * 60 * 60, 20L * 60 * 60 * 24);
    }

    boolean command(CommandSender sender, String[] args) {
        if (args.length == 0) {
            tagged(sender, "<white>Usage: /log <inspect|lookup|page|reload></white>");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "inspect" -> inspect(sender, args);
            case "lookup" -> lookup(sender, args);
            case "page" -> page(sender, args);
            case "reload" -> reloadCommand(sender, args);
            case "tp" -> teleport(sender, args);
            default -> {
                tagged(sender, "<white>Usage: /log <inspect|lookup|page|reload></white>");
                yield true;
            }
        };
    }

    List<String> completions(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>();
            if (sender.hasPermission("rivet.logs.inspect")) {
                choices.add("inspect");
            }
            if (sender.hasPermission("rivet.logs.lookup")) {
                choices.addAll(List.of("lookup", "page"));
            }
            if (sender.hasPermission("rivet.logs.reload")) {
                choices.add("reload");
            }
            return choices;
        }
        if (args[0].equalsIgnoreCase("lookup")) {
            String previous = args.length > 2 ? args[args.length - 2] : "";
            if (!previous.regionMatches(true, 0, "radius:", 0, 7)) {
                List<String> values = new ArrayList<>(List.of("30m", "1h", "6h", "1d", "radius:10", "radius:50"));
                values.addAll(plugin.getServer().getOnlinePlayers().stream()
                    .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList());
                return values;
            }
        }
        if (args[0].equalsIgnoreCase("page") && args.length == 2) {
            return List.of("1", "2", "3");
        }
        return List.of();
    }

    void reload() {
        settings = new AuditSettings(configuration);
        purgeExpired();
    }

    void reloadFromDisk() throws IOException, InvalidConfigurationException {
        YamlConfiguration next = new YamlConfiguration();
        next.load(plugin.settingsFile("logs"));
        configuration.loadFromString(next.saveToString());
        reload();
    }

    void shutdown() {
        inspectors.clear();
        ignoredBlockBreakChecks.clear();
        searches.clear();
        pendingContainers.clear();
        storage.close();
    }

    void recordSyntheticBlockBreak(Player player, Block block, String beforeData) {
        record(player, AuditAction.BLOCK_BREAK, block, block.getType().name(), 1,
            beforeData, "minecraft:air", "source=rivet");
    }

    void ignoreBlockBreakCheck(Player player, Block block) {
        ignoredBlockBreakChecks.add(blockKey(player, block));
    }

    void finishBlockBreakCheck(Player player, Block block) {
        ignoredBlockBreakChecks.remove(blockKey(player, block));
    }

    private boolean inspect(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rivet.logs.inspect")) {
            denied(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            tagged(sender, "<white>Inspector mode is only available to players.</white>");
            return true;
        }
        if (args.length != 1) {
            tagged(sender, "<white>Usage: /log inspect</white>");
            return true;
        }
        if (!inspectors.add(player.getUniqueId())) {
            inspectors.remove(player.getUniqueId());
            tagged(player, "<white>Inspector mode <#f72a4c>disabled</#f72a4c>.</white>");
        } else {
            tagged(player, "<white>Inspector mode <#f72a4c>enabled</#f72a4c>. Click a block or container.</white>");
        }
        return true;
    }

    private boolean lookup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rivet.logs.lookup")) {
            denied(sender);
            return true;
        }
        ParsedLookup parsed = parseLookup(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
        if (parsed == null) {
            tagged(sender, "<white>Usage: /log lookup [player] [time] [radius:&lt;radius&gt;]</white>");
            return true;
        }
        AuditQuery query = new AuditQuery(System.currentTimeMillis() - parsed.time().toMillis(),
            parsed.player(), parsed.world(), parsed.x(), parsed.y(), parsed.z(), parsed.radius(),
            Set.of(), sender.hasPermission("rivet.logs.commands"), settings.pageSize(), 0);
        SearchState state = new SearchState(query, parsed, null, settings.pageSize());
        searches.put(senderKey(sender), state);
        showPage(sender, state, 1);
        return true;
    }

    private boolean page(CommandSender sender, String[] args) {
        SearchState state = searches.get(senderKey(sender));
        boolean inspectorPage = state != null && state.inspector() != null;
        if (!sender.hasPermission("rivet.logs.lookup")
            && !(inspectorPage && sender.hasPermission("rivet.logs.inspect"))) {
            denied(sender);
            return true;
        }
        int requested;
        try {
            requested = args.length == 2 ? Integer.parseInt(args[1]) : -1;
        } catch (NumberFormatException exception) {
            requested = -1;
        }
        if (state == null) {
            tagged(sender, "<white>Run <#f72a4c>/log lookup</#f72a4c> before choosing a page.</white>");
        } else if (requested < 1) {
            tagged(sender, "<white>Usage: /log page &lt;number&gt;</white>");
        } else {
            showPage(sender, state, requested);
        }
        return true;
    }

    private boolean reloadCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rivet.logs.reload")) {
            denied(sender);
            return true;
        }
        if (args.length != 1) {
            tagged(sender, "<white>Usage: /log reload</white>");
            return true;
        }
        try {
            reloadFromDisk();
            tagged(sender, "<white>Audit settings reloaded from <#f72a4c>settings/logs.yml</#f72a4c>.</white>");
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("Could not reload settings/logs.yml: " + exception.getMessage());
            tagged(sender, "<white>Could not reload settings/logs.yml. Check the console.</white>");
        }
        return true;
    }

    private boolean teleport(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || !sender.hasPermission("rivet.logs.teleport")) {
            denied(sender);
            return true;
        }
        if (args.length != 5) {
            tagged(sender, "<white>That audit location is no longer valid.</white>");
            return true;
        }
        try {
            String worldName = new String(Base64.getUrlDecoder().decode(args[1]), StandardCharsets.UTF_8);
            World world = plugin.getServer().getWorld(worldName);
            int x = Integer.parseInt(args[2]);
            int y = Integer.parseInt(args[3]);
            int z = Integer.parseInt(args[4]);
            if (world == null) {
                tagged(sender, "<white>That world is not currently loaded.</white>");
                return true;
            }
            player.teleport(new Location(world, x + .5, y + 1, z + .5,
                player.getYaw(), player.getPitch()));
            tagged(sender, "<white>Teleported to the selected audit location.</white>");
        } catch (IllegalArgumentException exception) {
            tagged(sender, "<white>That audit location is no longer valid.</white>");
        }
        return true;
    }

    private void showPage(CommandSender sender, SearchState state, int requested) {
        if (state.inspector() != null) {
            showInspectorPage(sender, state, requested);
            return;
        }
        int pageSize = state.pageSize();
        CompletableFuture<Integer> count = storage.count(state.query());
        count.thenCompose(total -> {
            int pages = Math.max(1, (total + pageSize - 1) / pageSize);
            int page = Math.max(1, Math.min(requested, pages));
            return storage.query(state.query().page(page, pageSize))
                .thenApply(entries -> new Page(entries, page, pages));
        }).whenComplete((page, throwable) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (throwable != null) {
                queryFailed(sender, throwable);
                return;
            }
            ParsedLookup parsed = state.parsed();
            String title = parsed.player() == null ? "Recent Logs" : "Logs for " + parsed.player();
            String context = "Last " + AuditMessages.timeLabel(parsed.time());
            if (parsed.radius() != null) {
                context += " • radius " + parsed.radius();
            }
            sender.sendMessage(AuditMessages.lookup(RivetMessages.tag(plugin), title, context,
                page.entries(), page.page(), page.pages(), pageSize,
                sender.hasPermission("rivet.logs.teleport")));
        }));
    }

    private void showInspectorPage(CommandSender sender, SearchState state, int requested) {
        int pageSize = state.pageSize();
        storage.count(state.query()).thenCompose(total -> {
            int pages = Math.max(1, (total + pageSize - 1) / pageSize);
            int page = Math.max(1, Math.min(requested, pages));
            return storage.query(state.query().page(page, pageSize))
                .thenApply(entries -> new Page(entries, page, pages));
        }).whenComplete((page, throwable) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (throwable != null) {
                queryFailed(sender, throwable);
                return;
            }
            InspectorContext context = state.inspector();
            sender.sendMessage(AuditMessages.history(RivetMessages.tag(plugin), context.title(),
                context.target(), context.world(), context.x(), context.y(), context.z(),
                page.entries(), page.page(), page.pages(),
                sender.hasPermission("rivet.logs.teleport")));
        }));
    }

    private ParsedLookup parseLookup(CommandSender sender, String[] args) {
        String playerName = null;
        Duration time = settings.defaultTime();
        boolean timeSet = false;
        Integer radius = null;
        for (String argument : args) {
            if (argument.regionMatches(true, 0, "radius:", 0, 7)) {
                if (radius != null) {
                    return null;
                }
                try {
                    radius = Integer.parseInt(argument.substring(7));
                } catch (NumberFormatException exception) {
                    return null;
                }
                if (radius < 0 || radius > 1_000) {
                    return null;
                }
                continue;
            }
            Duration parsedTime = AuditSettings.parseTime(argument);
            if (parsedTime != null) {
                if (timeSet) {
                    return null;
                }
                time = parsedTime;
                timeSet = true;
            } else if (playerName == null && argument.matches("[A-Za-z0-9_]{1,16}")) {
                playerName = argument;
            } else {
                return null;
            }
        }
        if (args.length == 0 && sender instanceof Player) {
            radius = settings.defaultRadius();
        }
        if (radius != null) {
            if (!(sender instanceof Player player)) {
                return null;
            }
            Location location = player.getLocation();
            return new ParsedLookup(playerName, time, radius, location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
        return new ParsedLookup(playerName, time, null, null, null, null, null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInspect(PlayerInteractEvent event) {
        if (!inspectors.contains(event.getPlayer().getUniqueId())
            || event.getClickedBlock() == null
            || event.getHand() != EquipmentSlot.HAND
            || event.getAction() != Action.LEFT_CLICK_BLOCK
            && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        Block block = event.getClickedBlock();
        boolean container = block.getState(false) instanceof Container;
        Set<AuditAction> actions = container ? CONTAINER_ACTIONS : BLOCK_ACTIONS;
        AuditQuery query = new AuditQuery(System.currentTimeMillis()
            - settings.defaultTime().toMillis(), null, block.getWorld().getName(),
            block.getX(), block.getY(), block.getZ(), 0, actions,
            event.getPlayer().hasPermission("rivet.logs.commands"), settings.inspectorLimit(), 0);
        InspectorContext context = new InspectorContext(
            container ? "Container History" : "Block History", block.getType().name(),
            block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        SearchState state = new SearchState(query, null, context, settings.inspectorLimit());
        searches.put(senderKey(event.getPlayer()), state);
        showInspectorPage(event.getPlayer(), state, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        record(event.getPlayer(), AuditAction.BLOCK_PLACE, event.getBlockPlaced(),
            event.getBlockPlaced().getType().name(), 1,
            event.getBlockReplacedState().getBlockData().getAsString(),
            event.getBlockPlaced().getBlockData().getAsString(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (ignoredBlockBreakChecks.remove(blockKey(event.getPlayer(), event.getBlock()))) {
            return;
        }
        record(event.getPlayer(), AuditAction.BLOCK_BREAK, event.getBlock(),
            event.getBlock().getType().name(), 1,
            event.getBlock().getBlockData().getAsString(), "minecraft:air", null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack item = event.getItem().getItemStack();
        int amount = Math.max(0, item.getAmount() - event.getRemaining());
        if (amount > 0) {
            record(player, AuditAction.ITEM_PICKUP, event.getItem().getLocation(),
                item.getType().name(), amount, itemData(item), null, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        record(event.getPlayer(), AuditAction.ITEM_DROP, item.getLocation(),
            item.getItemStack().getType().name(), item.getItemStack().getAmount(), null,
            itemData(item.getItemStack()), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event instanceof PlayerDeathEvent) {
            return;
        }
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        String metadata = "cause=" + event.getDamageSource().getDamageType().getKey();
        Entity causing = event.getDamageSource().getCausingEntity();
        if (causing != null) {
            metadata += ";causing_entity=" + causing.getType().name()
                + ";causing_uuid=" + causing.getUniqueId();
        }
        if (killer != null) {
            ItemStack weapon = killer.getInventory().getItemInMainHand();
            if (!weapon.getType().isAir()) {
                metadata += ";weapon=" + itemData(weapon);
            }
        }
        record(killer, AuditAction.ENTITY_KILL, entity.getLocation(), entity.getType().name(),
            1, null, null, metadata);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        String message = event.deathMessage() == null ? "" : PLAIN.serialize(event.deathMessage());
        String killer = player.getKiller() == null ? "" : player.getKiller().getUniqueId().toString();
        record(player, AuditAction.PLAYER_DEATH, player.getLocation(), "PLAYER", 1,
            null, null, "message=" + encode(message) + ";killer=" + killer
                + ";cause=" + event.getDamageSource().getDamageType().getKey());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Block block = event.getBlock();
        String before = "";
        if (block.getState(false) instanceof Sign sign) {
            before = signLines(sign, event.getSide());
        }
        String after = event.lines().stream().map(GSON::serialize)
            .map(AuditModule::encode).collect(java.util.stream.Collectors.joining(","));
        record(event.getPlayer(), AuditAction.SIGN_EDIT, block, block.getType().name(), 1,
            block.getBlockData().getAsString(), block.getBlockData().getAsString(),
            "side=" + event.getSide().name() + ";before=" + before + ";after=" + after);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        AuditAction action = event.getEntity() instanceof Creeper
            ? AuditAction.CREEPER_DAMAGE : AuditAction.EXPLOSION;
        String cause = event.getEntityType().name();
        for (Block block : event.blockList()) {
            record(null, action, block, block.getType().name(), 1,
                block.getBlockData().getAsString(), "minecraft:air", "cause=" + cause);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            record(null, AuditAction.EXPLOSION, block, block.getType().name(), 1,
                block.getBlockData().getAsString(), "minecraft:air",
                "cause=BLOCK;source=" + event.getBlock().getType().name());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        Block block = event.getBlock();
        record(null, AuditAction.FIRE_DAMAGE, block, block.getType().name(), 1,
            block.getBlockData().getAsString(), "minecraft:air", "cause=BURN");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Block block = event.getBlock();
        record(event.getPlayer(), AuditAction.FIRE_DAMAGE, block, Material.FIRE.name(), 1,
            block.getBlockData().getAsString(), "minecraft:fire",
            "cause=" + event.getCause().name());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteraction(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
            || event.getClickedBlock() == null || !usefulInteraction(event.getClickedBlock().getType())) {
            return;
        }
        Block block = event.getClickedBlock();
        String before = block.getBlockData().getAsString();
        String target = block.getType().name();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String after = block.getBlockData().getAsString();
            if (!before.equals(after)) {
                record(event.getPlayer(), AuditAction.BLOCK_INTERACT, block,
                    target, 1, before, after, "interaction=RIGHT_CLICK");
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleContainerComparison(player, event.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleContainerComparison(player, event.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        logAutomatedContainer(event.getSource(), event.getItem(), AuditAction.CONTAINER_REMOVE,
            "transfer=hopper");
        logAutomatedContainer(event.getDestination(), event.getItem(), AuditAction.CONTAINER_ADD,
            "transfer=hopper");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        logAutomatedContainer(event.getInventory(), event.getItem().getItemStack(),
            AuditAction.CONTAINER_ADD, "transfer=world-item");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!settings.enabled(AuditAction.COMMAND) || privateOrChatCommand(event.getMessage())) {
            return;
        }
        String command = event.getMessage().substring(1).split("\\s+", 2)[0];
        record(event.getPlayer(), AuditAction.COMMAND, event.getPlayer().getLocation(),
            "/" + command, 1, null, null, "command=" + encode(event.getMessage()));
    }

    private void scheduleContainerComparison(Player player, Inventory inventory) {
        Location location = inventory.getLocation();
        if (location == null) {
            return;
        }
        String key = player.getUniqueId() + ":" + location.getWorld().getUID() + ":"
            + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
        pendingContainers.computeIfAbsent(key,
            ignored -> new PendingContainer(player, inventory, itemSnapshot(inventory)));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            PendingContainer pending = pendingContainers.remove(key);
            if (pending != null) {
                compareContainer(pending.player(), pending.inventory(), pending.before(),
                    itemSnapshot(pending.inventory()));
            }
        });
    }

    private void compareContainer(Player player, Inventory inventory,
                                  Map<String, ItemAmount> before, Map<String, ItemAmount> after) {
        Location location = inventory.getLocation();
        if (location == null) {
            return;
        }
        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            int previous = before.containsKey(key) ? before.get(key).amount() : 0;
            int current = after.containsKey(key) ? after.get(key).amount() : 0;
            if (previous == current) {
                continue;
            }
            ItemAmount item = current > previous ? after.get(key) : before.get(key);
            AuditAction action = current > previous
                ? AuditAction.CONTAINER_ADD : AuditAction.CONTAINER_REMOVE;
            record(player, action, location, item.material(), Math.abs(current - previous),
                action == AuditAction.CONTAINER_REMOVE ? key : null,
                action == AuditAction.CONTAINER_ADD ? key : null, "item=" + key);
        }
    }

    private void logAutomatedContainer(Inventory inventory, ItemStack item,
                                       AuditAction action, String metadata) {
        Location location = inventory.getLocation();
        if (location == null) {
            return;
        }
        String data = itemData(item);
        record(null, action, location, item.getType().name(), item.getAmount(),
            action == AuditAction.CONTAINER_REMOVE ? data : null,
            action == AuditAction.CONTAINER_ADD ? data : null, metadata + ";item=" + data);
    }

    private void record(Player player, AuditAction action, Block block, String target,
                        Integer amount, String before, String after, String metadata) {
        record(player, action, block.getLocation(), target, amount, before, after, metadata);
    }

    private void record(Player player, AuditAction action, Location location, String target,
                        Integer amount, String before, String after, String metadata) {
        if (!settings.enabled(action) || settings.excluded(location.getWorld().getName(), target)) {
            return;
        }
        storage.append(new AuditEntry(System.currentTimeMillis(),
            player == null ? null : player.getUniqueId(), player == null ? null : player.getName(),
            action, location.getWorld().getName(), location.getBlockX(), location.getBlockY(),
            location.getBlockZ(), target, amount, before, after, metadata));
    }

    private void purgeExpired() {
        long cutoff = System.currentTimeMillis() - Duration.ofDays(settings.retentionDays()).toMillis();
        storage.purgeBefore(cutoff).exceptionally(throwable -> {
            plugin.getLogger().log(Level.WARNING, "Could not purge expired audit records", throwable);
            return 0;
        });
    }

    private void queryFailed(CommandSender sender, Throwable throwable) {
        plugin.getLogger().log(Level.WARNING, "Could not query audit records", throwable);
        tagged(sender, "<white>Could not read the audit log. Check the console.</white>");
    }

    private void tagged(CommandSender sender, String message) {
        sender.sendMessage(RivetMessages.tagged(plugin, message));
    }

    private void denied(CommandSender sender) {
        tagged(sender, "<white>You do not have permission to do that.</white>");
    }

    static boolean privateOrChatCommand(String message) {
        if (message == null || !message.startsWith("/")) {
            return false;
        }
        String root = message.substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int namespace = root.indexOf(':');
        if (namespace >= 0) {
            root = root.substring(namespace + 1);
        }
        return PRIVATE_OR_CHAT_COMMANDS.contains(root);
    }

    static boolean usefulInteraction(Material material) {
        String name = material.name();
        return name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR")
            || name.endsWith("_FENCE_GATE") || name.endsWith("_BUTTON")
            || material == Material.LEVER || material == Material.REPEATER
            || material == Material.COMPARATOR || material == Material.NOTE_BLOCK
            || material == Material.BELL || material == Material.CAKE
            || material == Material.DAYLIGHT_DETECTOR;
    }

    static Map<String, ItemAmount> itemSnapshot(Inventory inventory) {
        Map<String, ItemAmount> snapshot = new LinkedHashMap<>();
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
                continue;
            }
            String data = itemData(item);
            snapshot.merge(data, new ItemAmount(item.getType().name(), item.getAmount()),
                (first, second) -> new ItemAmount(first.material(),
                    first.amount() + second.amount()));
        }
        return Map.copyOf(snapshot);
    }

    private static String itemData(ItemStack item) {
        ItemStack one = item.clone();
        one.setAmount(1);
        return Base64.getEncoder().encodeToString(one.serializeAsBytes());
    }

    private static String signLines(Sign sign, Side side) {
        return sign.getSide(side).lines().stream().map(GSON::serialize)
            .map(AuditModule::encode).collect(java.util.stream.Collectors.joining(","));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String senderKey(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId().toString() : "console";
    }

    private static String blockKey(Player player, Block block) {
        return player.getUniqueId() + ":" + block.getWorld().getUID() + ":"
            + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private record ParsedLookup(String player, Duration time, Integer radius, String world,
                                Integer x, Integer y, Integer z) {
    }

    private static final class SearchState {
        private final AuditQuery query;
        private final ParsedLookup parsed;
        private final InspectorContext inspector;
        private final int pageSize;

        private SearchState(AuditQuery query, ParsedLookup parsed, InspectorContext inspector,
                            int pageSize) {
            this.query = query;
            this.parsed = parsed;
            this.inspector = inspector;
            this.pageSize = pageSize;
        }

        private AuditQuery query() {
            return query;
        }

        private ParsedLookup parsed() {
            return parsed;
        }

        private InspectorContext inspector() {
            return inspector;
        }

        private int pageSize() {
            return pageSize;
        }
    }

    private record InspectorContext(String title, String target, String world,
                                    int x, int y, int z) {
    }

    record ItemAmount(String material, int amount) {
    }

    private record PendingContainer(Player player, Inventory inventory,
                                    Map<String, ItemAmount> before) {
    }

    private record Page(List<AuditEntry> entries, int page, int pages) {
    }
}
