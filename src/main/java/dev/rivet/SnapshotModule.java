package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

final class SnapshotModule implements Listener {
    static final List<String> BACKUP_REASONS = List.of("ENDER_CHEST", "DEATH", "JOIN", "QUIT",
        "WORLD_CHANGE", "GAMEMODE_CHANGE", "CONTAINER_CLOSE", "MANUAL", "AUTOMATIC");
    private static final int PAGE_SIZE = RivetGui.CONTENT_SLOTS.length;
    private static final DateTimeFormatter EXACT_TIME = DateTimeFormatter
        .ofPattern("d MMM uuuu 'at' HH:mm z", Locale.UK).withZone(ZoneId.systemDefault());
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String LEGACY_USAGE =
        "[message] <white>%tag% Usage: /snapshot &lt;player&gt;</white>";
    private static final String CURRENT_USAGE = "[message] <white>%tag% Usage: /snapshot "
        + "&lt;view &lt;player&gt;|save &lt;player&gt;|saveall|search &lt;item&gt;|cleanup&gt;</white>";
    private static final Set<String> CONTAINER_TYPES = Set.of("CHEST", "BARREL", "SHULKER_BOX",
        "HOPPER", "DISPENSER", "DROPPER");

    private final RivetPlugin plugin;
    private final YamlConfiguration configuration;
    private final SnapshotStorage storage;
    private final Set<UUID> restoresInProgress = new HashSet<>();
    private SnapshotSettings settings;
    private BukkitTask automaticTask;

    SnapshotModule(RivetPlugin plugin) throws SQLException {
        this.plugin = plugin;
        configuration = plugin.settings("snapshots");
        migrateLegacyConfiguration(configuration);
        settings = new SnapshotSettings(configuration);
        storage = new SnapshotStorage(plugin.getDataFolder().toPath().resolve("snapshots.db"),
            throwable -> plugin.getLogger().log(Level.SEVERE, "Snapshot storage failed", throwable));
        cleanup(false, null);
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,
            () -> cleanup(false, null), 20L * 60 * 60, 20L * 60 * 60 * 24);
        startAutomaticBackups();
    }

    boolean command(CommandSender sender, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "view" -> {
                if (args.length != 2) usage(sender); else browse(sender, args[1]);
                yield true;
            }
            case "save" -> {
                if (args.length != 2) usage(sender); else manualSave(sender, args[1]);
                yield true;
            }
            case "saveall" -> {
                if (args.length != 1) usage(sender); else manualSaveAll(sender);
                yield true;
            }
            case "cleanup" -> {
                if (args.length != 1) usage(sender);
                else if (require(sender, "rivet.snapshots.cleanup")) cleanup(true, sender);
                yield true;
            }
            case "search" -> {
                if (args.length < 2) usage(sender);
                else search(sender, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                yield true;
            }
            default -> {
                if (args.length == 1) browse(sender, args[0]); else usage(sender);
                yield true;
            }
        };
    }

    List<String> completions(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>(List.of("view", "save", "saveall", "search", "cleanup"));
            if (sender instanceof Player viewer && sender.hasPermission("rivet.snapshots.view")) {
                plugin.getServer().getOnlinePlayers().stream().filter(viewer::canSee)
                    .map(Player::getName).forEach(choices::add);
            }
            return choices;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("view")
            || args[0].equalsIgnoreCase("save"))) {
            return plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        return List.of();
    }

    void reload() {
        migrateLegacyConfiguration(configuration);
        settings = new SnapshotSettings(configuration);
        cleanup(false, null);
        startAutomaticBackups();
    }

    void shutdown() {
        restoresInProgress.clear();
        if (automaticTask != null) {
            automaticTask.cancel();
            automaticTask = null;
        }
        storage.close();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!settings.saveOnDeath()) return;
        Player player = event.getEntity();
        String cause = player.getLastDamageCause() == null ? null
            : player.getLastDamageCause().getCause().name();
        if (player.getKiller() != null) {
            cause = (cause == null ? "PLAYER" : cause) + " (" + player.getKiller().getName() + ")";
        }
        savePlayer(player, "DEATH", cause, player.getUniqueId(), player.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (settings.enabled("JOIN")) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (event.getPlayer().isOnline()) {
                    savePlayer(event.getPlayer(), "JOIN", null, event.getPlayer().getUniqueId(),
                        event.getPlayer().getName());
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (settings.enabled("QUIT")) {
            savePlayer(event.getPlayer(), "QUIT", null, event.getPlayer().getUniqueId(),
                event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (settings.enabled("WORLD_CHANGE")) {
            savePlayer(event.getPlayer(), "WORLD_CHANGE", event.getFrom().getName() + " -> "
                + event.getPlayer().getWorld().getName(), event.getPlayer().getUniqueId(),
                event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (settings.enabled("GAMEMODE_CHANGE")) {
            savePlayer(event.getPlayer(), "GAMEMODE_CHANGE", event.getPlayer().getGameMode().name()
                + " -> " + event.getNewGameMode().name(), event.getPlayer().getUniqueId(),
                event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)
            || event.getInventory().getHolder(false) instanceof SnapshotGuiHolder) return;
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST
            && settings.enabled("ENDER_CHEST")) {
            saveCapturedIfUseful(captureEnderChest(player, "ENDER_CHEST", null), player,
                player.getUniqueId(), player.getName());
        } else if (event.getInventory().getLocation() != null
            && CONTAINER_TYPES.contains(event.getInventory().getType().name())
            && settings.enabled("CONTAINER_CLOSE")) {
            savePlayer(player, "CONTAINER_CLOSE", event.getInventory().getType().name(),
                player.getUniqueId(), player.getName());
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder rawHolder = event.getView().getTopInventory().getHolder(false);
        if (!readOnlyHolder(rawHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer) || event.getRawSlot() < 0
            || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        int slot = event.getRawSlot();
        if (rawHolder instanceof SnapshotCategoryHolder holder) categoryClick(viewer, holder, slot, event.getClick());
        else if (rawHolder instanceof SnapshotListHolder holder) listClick(viewer, holder, slot, event.getClick());
        else if (rawHolder instanceof SnapshotPreviewHolder holder) previewClick(viewer, holder, slot, event.getClick());
        else if (rawHolder instanceof SnapshotConfirmHolder holder) confirmClick(viewer, holder, slot, event.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (readOnlyHolder(event.getView().getTopInventory().getHolder(false))) event.setCancelled(true);
    }

    private void browse(CommandSender sender, String name) {
        if (!require(sender, "rivet.snapshots.view") || !(sender instanceof Player viewer)) {
            if (!(sender instanceof Player)) message(sender, "messages.player-only",
                "<white>%tag% This command is only available to players.</white>");
            return;
        }
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null && !viewer.canSee(online)) {
            notFound(viewer);
            return;
        }
        OfflinePlayer cached = online == null ? plugin.getServer().getOfflinePlayerIfCached(name) : online;
        if (cached != null && (cached.hasPlayedBefore() || cached.isOnline())) {
            openResolved(viewer, new SnapshotTarget(cached.getUniqueId(), cached.getName()));
            return;
        }
        message(viewer, "messages.loading", "<white>%tag% Loading backups...</white>");
        storage.findPlayer(name).whenComplete((uuid, failure) -> onMain(() -> {
            if (!viewer.isOnline()) return;
            if (failure != null) loadFailed(viewer, failure);
            else if (uuid == null) notFound(viewer);
            else openResolved(viewer, new SnapshotTarget(uuid, name));
        }));
    }

    private void search(CommandSender sender, String query) {
        if (!require(sender, "rivet.snapshots.search") || !(sender instanceof Player viewer)) {
            if (!(sender instanceof Player)) message(sender, "messages.player-only",
                "<white>%tag% This command is only available to players.</white>");
            return;
        }
        message(viewer, "messages.searching",
            "<white>%tag% Searching inventory backups for <#f72a4c>%query%</#f72a4c>...</white>",
            Placeholder.unparsed("query", query));
        storage.search(query, settings.searchMaximumMatches(), settings.searchTimeoutSeconds())
            .whenComplete((records, failure) -> onMain(() -> {
                if (!viewer.isOnline()) return;
                if (failure != null) loadFailed(viewer, failure);
                else if (records.isEmpty()) message(viewer, "messages.search-none",
                    "<white>%tag% No backups contained <#f72a4c>%query%</#f72a4c>.</white>",
                    Placeholder.unparsed("query", query));
                else openCategories(viewer, null, "Search: " + query, records, 0);
            }));
    }

    private void manualSave(CommandSender sender, String name) {
        if (!require(sender, "rivet.snapshots.create")) return;
        Player target = plugin.getServer().getPlayerExact(name);
        if (target == null || sender instanceof Player viewer && !viewer.canSee(target)) {
            message(sender, "messages.target-offline",
                "<white>%tag% The target player must be online and visible.</white>");
            return;
        }
        if (!savePlayer(target, "MANUAL", "by: " + sender.getName(), actorUuid(sender, target),
            sender.getName())) {
            message(sender, "messages.empty",
                "<white>%tag% No backup was made because that inventory is empty or excluded.</white>");
            return;
        }
        message(sender, "messages.manual-backup",
            "<white>%tag% Created a manual backup for <#f72a4c>%player%</#f72a4c>.</white>",
            Placeholder.unparsed("player", target.getName()));
    }

    private void manualSaveAll(CommandSender sender) {
        if (!require(sender, "rivet.snapshots.create")) return;
        int saved = 0;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (savePlayer(player, "MANUAL", "by: " + sender.getName(), actorUuid(sender, player),
                sender.getName())) saved++;
        }
        message(sender, "messages.manual-backup-all",
            "<white>%tag% Created manual backups for <#f72a4c>%count%</#f72a4c> players.</white>",
            Placeholder.unparsed("count", Integer.toString(saved)));
    }

    private void openResolved(Player viewer, SnapshotTarget target) {
        if (!viewer.getUniqueId().equals(target.uuid())
            && !viewer.hasPermission("rivet.snapshots.others")) {
            message(viewer, "messages.denied-others",
                "<white>%tag% You cannot view another player's backups.</white>");
            return;
        }
        storage.list(target.uuid(), settings.maxPerPlayer()).whenComplete((records, failure) ->
            onMain(() -> {
                if (!viewer.isOnline()) return;
                if (failure != null) loadFailed(viewer, failure);
                else if (records.isEmpty()) message(viewer, "messages.none",
                    "<white>%tag% No backups were found for <#f72a4c>%player%</#f72a4c>.</white>",
                    Placeholder.unparsed("player", target.name()));
                else openCategories(viewer, target, target.name(), records, 0);
            }));
    }

    private void openCategories(Player viewer, SnapshotTarget target, String label,
                                List<SnapshotRecord> records, int requestedPage) {
        RivetMenu menu = menu("categories");
        int size = menu.size(54);
        List<Integer> contentSlots = menu.slots("category", size,
            java.util.Arrays.stream(RivetGui.CONTENT_SLOTS).boxed().toList());
        if (contentSlots.isEmpty()) contentSlots = List.of(Math.min(22, size - 1));
        int pageSize = contentSlots.size();
        Map<String, List<SnapshotRecord>> categories = categories(records, settings.allCategory());
        List<String> names = List.copyOf(categories.keySet());
        int pages = Math.max(1, (names.size() + pageSize - 1) / pageSize);
        int page = Math.clamp(requestedPage, 0, pages - 1);
        SnapshotCategoryHolder holder = new SnapshotCategoryHolder(target, label, records,
            categories, names, page, pageSize);
        TagResolver pageTags = TagResolver.resolver(Placeholder.unparsed("player", label),
            Placeholder.unparsed("page", Integer.toString(page + 1)),
            Placeholder.unparsed("pages", Integer.toString(pages)),
            Placeholder.unparsed("count", Integer.toString(names.size())));
        holder.inventory = plugin.getServer().createInventory(holder, size,
            menu.title("<white>Restore &gt; %player%</white>", pageTags));
        menu.place(holder.inventory, "filler", menu.item("filler",
            Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of(), false),
            List.of(), holder.controls);
        int start = page * pageSize;
        for (int index = start; index < Math.min(names.size(), start + pageSize); index++) {
            String category = names.get(index);
            int slot = contentSlots.get(index - start);
            TagResolver tags = TagResolver.resolver(pageTags,
                Placeholder.unparsed("name", friendly(category)),
                Placeholder.unparsed("amount", Integer.toString(categories.get(category).size())));
            holder.inventory.setItem(slot, menu.item("category",
                categoryIcon(category, categories.get(category).size()), tags));
            holder.selections.put(slot, category);
            holder.controls.put(slot, "category");
        }
        placeNavigation(menu, holder.inventory, holder.controls, page, pages,
            names.size() + " categories", pageTags, true);
        menu.placeStaticItems(holder.inventory,
            Set.of("filler", "category", "previous-page", "page-info", "next-page"),
            holder.controls, pageTags);
        viewer.openInventory(holder.inventory);
        menu.open(viewer, pageTags);
    }

    private void categoryClick(Player viewer, SnapshotCategoryHolder holder, int slot,
                               org.bukkit.event.inventory.ClickType click) {
        String control = holder.controls.get(slot);
        menu("categories").click(viewer, control, click);
        if ("previous-page".equals(control) && holder.page > 0) {
            openCategories(viewer, holder.target, holder.label, holder.records, holder.page - 1);
            return;
        }
        if ("next-page".equals(control)
            && (holder.page + 1) * holder.pageSize < holder.names.size()) {
            openCategories(viewer, holder.target, holder.label, holder.records, holder.page + 1);
            return;
        }
        String category = holder.selections.get(slot);
        if (category == null) {
            return;
        }
        openList(viewer, new SnapshotListContext(holder.target, holder.label, holder.records,
            holder.page, category, holder.categories.get(category), 0));
    }

    private void openList(Player viewer, SnapshotListContext context) {
        RivetMenu menu = menu("list");
        int size = menu.size(54);
        List<Integer> contentSlots = menu.slots("snapshot", size,
            java.util.Arrays.stream(RivetGui.CONTENT_SLOTS).boxed().toList());
        if (contentSlots.isEmpty()) contentSlots = List.of(Math.min(22, size - 1));
        int pageSize = contentSlots.size();
        int pages = Math.max(1, (context.records().size() + pageSize - 1) / pageSize);
        int page = Math.clamp(context.page(), 0, pages - 1);
        SnapshotListContext shown = context.withPage(page);
        SnapshotListHolder holder = new SnapshotListHolder(shown, pageSize);
        TagResolver pageTags = TagResolver.resolver(
            Placeholder.unparsed("name", friendly(context.category())),
            Placeholder.unparsed("page", Integer.toString(page + 1)),
            Placeholder.unparsed("pages", Integer.toString(pages)),
            Placeholder.unparsed("count", Integer.toString(context.records().size())));
        holder.inventory = plugin.getServer().createInventory(holder, size,
            menu.title("<white>Restore &gt; %name%</white>", pageTags));
        menu.place(holder.inventory, "filler", menu.item("filler",
            Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of(), false),
            List.of(), holder.controls);
        int start = page * pageSize;
        for (int index = start; index < Math.min(context.records().size(), start + pageSize); index++) {
            SnapshotRecord record = context.records().get(index);
            int slot = contentSlots.get(index - start);
            holder.inventory.setItem(slot, menu.item("snapshot", snapshotIcon(record),
                Placeholder.unparsed("id", Long.toString(record.id())),
                Placeholder.unparsed("reason", friendly(record.reason()))));
            holder.selections.put(slot, record);
            holder.controls.put(slot, "snapshot");
        }
        placeNavigation(menu, holder.inventory, holder.controls, page, pages,
            context.records().size() + " backups", pageTags, false);
        menu.place(holder.inventory, "back", menu.item("back", Material.BARRIER,
            "<red>Back to categories</red>", List.of("<gray>Page %page%/%pages%</gray>",
                "<gray>%count% backups</gray>"), pageTags), List.of(49), holder.controls);
        menu.placeStaticItems(holder.inventory,
            Set.of("filler", "snapshot", "previous-page", "back", "next-page"),
            holder.controls, pageTags);
        viewer.openInventory(holder.inventory);
        menu.open(viewer, pageTags);
    }

    private void listClick(Player viewer, SnapshotListHolder holder, int slot,
                           org.bukkit.event.inventory.ClickType click) {
        SnapshotListContext context = holder.context;
        String control = holder.controls.get(slot);
        menu("list").click(viewer, control, click);
        if ("previous-page".equals(control) && context.page() > 0) {
            openList(viewer, context.withPage(context.page() - 1));
            return;
        }
        if ("back".equals(control)) {
            openCategories(viewer, context.target(), context.label(), context.allRecords(),
                context.categoryPage());
            return;
        }
        if ("next-page".equals(control)
            && (context.page() + 1) * holder.pageSize < context.records().size()) {
            openList(viewer, context.withPage(context.page() + 1));
            return;
        }
        SnapshotRecord selected = holder.selections.get(slot);
        if (selected == null) return;
        if (selected.state() != null) {
            openPreview(viewer, selected, context);
            return;
        }
        storage.load(selected.id()).whenComplete((record, failure) -> onMain(() -> {
            if (!viewer.isOnline()) return;
            if (failure != null || record == null) loadFailed(viewer, failure);
            else openPreview(viewer, record, context);
        }));
    }

    private void openPreview(Player viewer, SnapshotRecord record, SnapshotListContext context) {
        SnapshotTarget target = new SnapshotTarget(record.playerUuid(), record.playerName());
        SnapshotPreviewHolder holder = new SnapshotPreviewHolder(target, record, context);
        boolean ender = isEnderChest(record.reason());
        RivetMenu menu = menu(ender ? "ender-preview" : "player-preview");
        TagResolver tags = TagResolver.resolver(
            Placeholder.unparsed("player", record.playerName()),
            Placeholder.unparsed("id", Long.toString(record.id())),
            Placeholder.unparsed("reason", friendly(record.reason())));
        holder.inventory = plugin.getServer().createInventory(holder, 54,
            menu.title("<white>Restore &gt; %player%</white>", tags));
        SnapshotState state = record.state();
        if (ender) {
            ItemStack[] contents = state.inventory();
            for (int slot = 0; slot < Math.min(27, contents.length); slot++) {
                holder.inventory.setItem(slot, contents[slot]);
            }
            menu.place(holder.inventory, "filler", menu.item("filler",
                Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of(), false),
                java.util.stream.IntStream.range(27, 54).boxed().toList(), holder.controls);
            placePreviewControl(menu, holder, "back", Material.BARRIER, "<white>Back</white>", 45, tags);
            menu.place(holder.inventory, "details", menu.item("details", stateInfo(record), tags),
                List.of(47), holder.controls);
            placePreviewControl(menu, holder, "teleport", Material.ENDER_PEARL,
                "<white>Teleport to location</white>", 49, tags);
            placePreviewControl(menu, holder, "export", Material.SHULKER_BOX,
                "<white>Export as shulker boxes</white>", 51, tags);
            placePreviewControl(menu, holder, "restore", Material.ENDER_CHEST,
                "<white>Replace ender chest</white>", 53, tags);
        } else {
            ItemStack[] inventory = state.inventory();
            for (int savedSlot = 9; savedSlot < Math.min(36, inventory.length); savedSlot++) {
                holder.inventory.setItem(savedSlot - 9, inventory[savedSlot]);
            }
            for (int savedSlot = 0; savedSlot < Math.min(9, inventory.length); savedSlot++) {
                holder.inventory.setItem(27 + savedSlot, inventory[savedSlot]);
            }
            menu.place(holder.inventory, "filler", menu.item("filler",
                Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of(), false),
                java.util.stream.IntStream.range(36, 45).boxed().toList(), holder.controls);
            placePreviewControl(menu, holder, "export", Material.SHULKER_BOX,
                "<white>Export as shulker boxes</white>", 44, tags);
            ItemStack[] armour = state.armour();
            for (int index = 0; index < Math.min(4, armour.length); index++) {
                holder.inventory.setItem(45 + index, armour[index]);
            }
            holder.inventory.setItem(49, state.offhand());
            placePreviewControl(menu, holder, "back", Material.BARRIER, "<white>Back</white>", 50, tags);
            menu.place(holder.inventory, "details", menu.item("details", stateInfo(record), tags),
                List.of(51), holder.controls);
            placePreviewControl(menu, holder, "teleport", Material.ENDER_PEARL,
                "<white>Teleport to location</white>", 52, tags);
            placePreviewControl(menu, holder, "restore", Material.PISTON,
                "<white>Replace player inventory</white>", 53, tags);
        }
        menu.placeStaticItems(holder.inventory,
            Set.of("filler", "back", "details", "teleport", "export", "restore"),
            holder.controls, tags);
        viewer.openInventory(holder.inventory);
        menu.open(viewer, tags);
    }

    private void placePreviewControl(RivetMenu menu, SnapshotPreviewHolder holder, String key,
                                     Material material, String name, int slot,
                                     TagResolver placeholders) {
        menu.place(holder.inventory, key, menu.item(key, material, name, List.of(), placeholders),
            List.of(slot), holder.controls);
    }

    private void previewClick(Player viewer, SnapshotPreviewHolder holder, int slot,
                              org.bukkit.event.inventory.ClickType click) {
        boolean ender = isEnderChest(holder.record.reason());
        String control = holder.controls.get(slot);
        menu(ender ? "ender-preview" : "player-preview").click(viewer, control, click,
            Placeholder.unparsed("player", holder.record.playerName()),
            Placeholder.unparsed("id", Long.toString(holder.record.id())));
        if ("back".equals(control)) openList(viewer, holder.context);
        else if ("teleport".equals(control)) teleport(viewer, holder.record);
        else if ("export".equals(control)) export(viewer, holder.record);
        else if ("restore".equals(control)) requestRestore(viewer, holder.target, holder.record, holder.context);
    }

    private void requestRestore(Player viewer, SnapshotTarget target, SnapshotRecord record,
                                SnapshotListContext context) {
        if (!viewer.hasPermission("rivet.snapshots.restore")) {
            message(viewer, "messages.denied-restore",
                "<white>%tag% You do not have permission to restore backups.</white>");
            return;
        }
        Player online = visibleTarget(viewer, target.uuid());
        if (online == null) {
            message(viewer, "messages.target-offline",
                "<white>%tag% The target player must be online and visible.</white>");
            return;
        }
        if (!settings.requireConfirmation()) {
            beginRestore(viewer, target, record);
            return;
        }
        SnapshotConfirmHolder holder = new SnapshotConfirmHolder(target, record, context);
        RivetMenu menu = menu("confirm");
        int size = menu.size(27);
        TagResolver tags = TagResolver.resolver(
            Placeholder.unparsed("player", record.playerName()),
            Placeholder.unparsed("id", Long.toString(record.id())),
            Placeholder.unparsed("reason", friendly(record.reason())));
        holder.inventory = plugin.getServer().createInventory(holder, size,
            menu.title("<white>Confirm Restore</white>", tags));
        menu.place(holder.inventory, "filler", menu.item("filler",
            Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of(), false),
            java.util.stream.IntStream.range(0, size).boxed().toList(), holder.controls);
        menu.place(holder.inventory, "cancel", menu.item("cancel", Material.RED_CONCRETE,
            Component.text("Cancel", NamedTextColor.RED).decorate(TextDecoration.BOLD),
            List.of(Component.text("Return without changing anything", NamedTextColor.GRAY)), false, tags),
            List.of(Math.min(11, size - 1)), holder.controls);
        menu.place(holder.inventory, "warning", menu.item("warning", Material.PAPER,
            Component.text("This replaces current items", NamedTextColor.YELLOW), List.of(
                Component.text(isEnderChest(record.reason()) ? "The current ender chest"
                    : "Inventory, equipment and player state", NamedTextColor.GRAY),
                Component.text("will be replaced exactly.", NamedTextColor.RED)), false, tags),
            List.of(Math.min(13, size - 1)), holder.controls);
        menu.place(holder.inventory, "confirm", menu.item("confirm", Material.LIME_CONCRETE,
            Component.text("Confirm restore", NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
            List.of(Component.text(settings.createSafetySnapshot()
                ? "A safety backup will be created first."
                : "Safety backups are disabled in settings.", NamedTextColor.GRAY)), false, tags),
            List.of(Math.min(15, size - 1)), holder.controls);
        menu.placeStaticItems(holder.inventory,
            Set.of("filler", "cancel", "warning", "confirm"), holder.controls, tags);
        viewer.openInventory(holder.inventory);
        menu.open(viewer, tags);
    }

    private void confirmClick(Player viewer, SnapshotConfirmHolder holder, int slot,
                              org.bukkit.event.inventory.ClickType click) {
        String control = holder.controls.get(slot);
        menu("confirm").click(viewer, control, click,
            Placeholder.unparsed("player", holder.record.playerName()),
            Placeholder.unparsed("id", Long.toString(holder.record.id())));
        if ("cancel".equals(control)) openPreview(viewer, holder.record, holder.context);
        else if ("confirm".equals(control)) beginRestore(viewer, holder.target, holder.record);
    }

    private void beginRestore(Player staff, SnapshotTarget target, SnapshotRecord record) {
        Player online = visibleTarget(staff, target.uuid());
        if (online == null) {
            message(staff, "messages.target-offline",
                "<white>%tag% The target player must be online and visible.</white>");
            return;
        }
        if (!restoresInProgress.add(target.uuid())) {
            message(staff, "messages.busy",
                "<white>%tag% A restore is already running for that player.</white>");
            return;
        }
        staff.closeInventory();
        if (!settings.createSafetySnapshot()) {
            finishRestore(staff, online, record);
            return;
        }
        CapturedSnapshot safety = isEnderChest(record.reason())
            ? captureEnderChest(online, "PRE_RESTORE_ENDER_CHEST", null)
            : capturePlayer(online, "PRE_RESTORE", null);
        storage.save(safety, settings).whenComplete((saved, failure) -> onMain(() -> {
            if (!staff.isOnline()) {
                restoresInProgress.remove(target.uuid());
                return;
            }
            if (failure != null || saved == null) {
                restoresInProgress.remove(target.uuid());
                message(staff, "messages.safety-failed",
                    "<white>%tag% The safety backup failed, so nothing was restored.</white>");
                return;
            }
            if (settings.auditCreations()) {
                plugin.recordSnapshotAudit(AuditAction.SNAPSHOT_CREATE, staff.getUniqueId(),
                    staff.getName(), saved);
            }
            Player current = visibleTarget(staff, target.uuid());
            if (current == null) {
                restoresInProgress.remove(target.uuid());
                message(staff, "messages.target-offline",
                    "<white>%tag% The target player went offline before restore.</white>");
            } else finishRestore(staff, current, record);
        }));
    }

    private void finishRestore(Player staff, Player target, SnapshotRecord record) {
        try {
            target.closeInventory();
            if (isEnderChest(record.reason())) {
                target.getEnderChest().clear();
                target.getEnderChest().setStorageContents(record.state().inventory());
            } else applyState(record.state(), new BukkitRestoreTarget(target));
            target.updateInventory();
            plugin.recordSnapshotAudit(AuditAction.SNAPSHOT_RESTORE, staff.getUniqueId(),
                staff.getName(), record);
            message(staff, "messages.restored",
                "<white>%tag% Restored backup <#f72a4c>#%id%</#f72a4c> to <#f72a4c>%player%</#f72a4c>.</white>",
                Placeholder.unparsed("id", Long.toString(record.id())),
                Placeholder.unparsed("player", target.getName()));
            if (!staff.equals(target)) message(target, "messages.restored-target",
                "<white>%tag% Your items were restored from backup <#f72a4c>#%id%</#f72a4c>.</white>",
                Placeholder.unparsed("id", Long.toString(record.id())));
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not apply backup #" + record.id(), exception);
            message(staff, "messages.restore-failed",
                "<white>%tag% The backup could not be restored. Check the console.</white>");
        } finally {
            restoresInProgress.remove(target.getUniqueId());
        }
    }

    private void export(Player viewer, SnapshotRecord record) {
        if (!viewer.hasPermission("rivet.snapshots.export")) {
            message(viewer, "messages.denied-export",
                "<white>%tag% You do not have permission to export backups.</white>");
            return;
        }
        List<ItemStack> exported = shulkerExport(record, viewer.getName());
        long free = java.util.Arrays.stream(viewer.getInventory().getStorageContents())
            .filter(SnapshotModule::isEmpty).count();
        if (free < exported.size()) {
            message(viewer, "messages.export-space",
                "<white>%tag% You need <#f72a4c>%slots%</#f72a4c> empty inventory slots.</white>",
                Placeholder.unparsed("slots", Integer.toString(exported.size())));
            return;
        }
        exported.forEach(item -> viewer.getInventory().addItem(item));
        message(viewer, "messages.exported",
            "<white>%tag% Exported backup <#f72a4c>#%id%</#f72a4c> into <#f72a4c>%count%</#f72a4c> inventory slots.</white>",
            Placeholder.unparsed("id", Long.toString(record.id())),
            Placeholder.unparsed("count", Integer.toString(exported.size())));
    }

    private List<ItemStack> shulkerExport(SnapshotRecord record, String staff) {
        List<ItemStack> loose = new ArrayList<>();
        List<ItemStack> direct = new ArrayList<>();
        for (ItemStack item : savedItems(record)) {
            if (isEmpty(item)) continue;
            if (LaggModule.isShulkerBox(item.getType())) direct.add(item.clone());
            else loose.add(item.clone());
        }
        for (int start = 0; start < loose.size(); start += 27) {
            ItemStack box = new ItemStack(Material.SHULKER_BOX);
            BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
            meta.displayName(Component.text("Inventory backup", RivetPalette.SECONDARY)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("Player: " + record.playerName(), NamedTextColor.GRAY),
                Component.text("Date: " + exactTime(record.timestamp()), NamedTextColor.GRAY),
                Component.text("Location: " + record.world() + " • " + coordinates(record),
                    NamedTextColor.GRAY), Component.text("Given to: " + staff, NamedTextColor.GRAY)));
            ShulkerBox shulker = (ShulkerBox) meta.getBlockState();
            for (int index = start; index < Math.min(start + 27, loose.size()); index++) {
                shulker.getInventory().setItem(index - start, loose.get(index));
            }
            meta.setBlockState(shulker);
            box.setItemMeta(meta);
            direct.add(box);
        }
        return List.copyOf(direct);
    }

    private boolean savePlayer(Player player, String reason, String cause, UUID actorUuid,
                               String actorName) {
        if (!settings.enabled(reason) || player.hasPermission("rivet.snapshots.dontsave")) return false;
        return saveCapturedIfUseful(capturePlayer(player, reason, cause), player, actorUuid, actorName);
    }

    private boolean saveCapturedIfUseful(CapturedSnapshot captured, Player player, UUID actorUuid,
                                         String actorName) {
        if (player.hasPermission("rivet.snapshots.dontsave") || !hasItems(captured.state())
            || settings.maxPerPlayer() == 0 || settings.limit(captured.reason()) == 0) return false;
        saveCaptured(captured, actorUuid, actorName, settings.auditCreations());
        return true;
    }

    private CapturedSnapshot capturePlayer(Player player, String reason, String cause) {
        Location location = player.getLocation();
        PlayerInventory inventory = player.getInventory();
        SnapshotState state = new SnapshotState(inventory.getStorageContents(),
            inventory.getArmorContents(), inventory.getItemInOffHand(), player.getLevel(),
            player.getExp(), restorableHealth(reason, player.getHealth()), player.getFoodLevel(),
            player.getSaturation());
        return captured(player, reason, cause, location, state);
    }

    private CapturedSnapshot captureEnderChest(Player player, String reason, String cause) {
        Location location = player.getLocation();
        SnapshotState state = new SnapshotState(player.getEnderChest().getStorageContents(),
            new ItemStack[4], null, player.getLevel(), player.getExp(), player.getHealth(),
            player.getFoodLevel(), player.getSaturation());
        return captured(player, reason, cause, location, state);
    }

    private static CapturedSnapshot captured(Player player, String reason, String cause,
                                             Location location, SnapshotState state) {
        return new CapturedSnapshot(player.getUniqueId(), player.getName(), reason,
            System.currentTimeMillis(), location.getWorld().getName(), location.getX(),
            location.getY(), location.getZ(), cause, state);
    }

    private void saveCaptured(CapturedSnapshot captured, UUID actorUuid, String actorName,
                              boolean auditCreation) {
        storage.save(captured, settings).whenComplete((record, failure) -> {
            if (failure != null || record == null) {
                plugin.getLogger().log(Level.SEVERE, "Could not save " + captured.reason()
                    + " backup for " + captured.playerName(), failure);
            } else if (auditCreation) onMain(() -> plugin.recordSnapshotAudit(
                AuditAction.SNAPSHOT_CREATE, actorUuid, actorName, record));
        });
    }

    private void startAutomaticBackups() {
        if (automaticTask != null) {
            automaticTask.cancel();
            automaticTask = null;
        }
        if (!settings.automaticBackups() || !settings.enabled("AUTOMATIC")) return;
        long ticks = settings.automaticIntervalSeconds() * 20L;
        automaticTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                savePlayer(player, "AUTOMATIC", null, player.getUniqueId(), player.getName());
            }
        }, ticks, ticks);
    }

    private void cleanup(boolean report, CommandSender sender) {
        storage.cleanup(settings).whenComplete((result, failure) -> onMain(() -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, "Could not clean old backups", failure);
                if (report && sender != null) message(sender, "messages.cleanup-failed",
                    "<white>%tag% Backup cleanup failed. Check the console.</white>");
            } else if (report && sender != null) message(sender, "messages.cleaned-up",
                "<white>%tag% Removed <#f72a4c>%count%</#f72a4c> old backups.</white>",
                Placeholder.unparsed("count", Integer.toString(
                    result.expiredSnapshots() + result.excessSnapshots())));
        }));
    }

    private void teleport(Player viewer, SnapshotRecord record) {
        if (!viewer.hasPermission("rivet.snapshots.teleport")) {
            message(viewer, "messages.denied-teleport",
                "<white>%tag% You do not have permission to teleport to backups.</white>");
            return;
        }
        World world = plugin.getServer().getWorld(record.world());
        if (world == null) {
            message(viewer, "messages.world-unavailable",
                "<white>%tag% The backup world is not loaded.</white>");
            return;
        }
        viewer.closeInventory();
        viewer.teleportAsync(new Location(world, record.x(), record.y(), record.z()));
    }

    private Player visibleTarget(Player viewer, UUID uuid) {
        Player target = plugin.getServer().getPlayer(uuid);
        return target != null && viewer.canSee(target) ? target : null;
    }

    private ItemStack categoryIcon(String category, int count) {
        Material material = switch (category) {
            case "ALL" -> Material.NETHER_STAR;
            case "ENDER_CHEST" -> Material.ENDER_CHEST;
            case "DEATH" -> Material.TOTEM_OF_UNDYING;
            case "JOIN" -> Material.LIME_WOOL;
            case "QUIT" -> Material.RED_WOOL;
            case "WORLD_CHANGE" -> Material.END_PORTAL_FRAME;
            case "GAMEMODE_CHANGE" -> Material.GOLDEN_APPLE;
            case "CONTAINER_CLOSE" -> Material.CHEST;
            case "MANUAL" -> Material.LEVER;
            case "AUTOMATIC" -> Material.CLOCK;
            default -> Material.PAPER;
        };
        return item(material, Component.text(friendly(category), RivetPalette.SECONDARY)
            .decorate(TextDecoration.BOLD), List.of(
                Component.text(count + " backup" + (count == 1 ? "" : "s"), NamedTextColor.GRAY),
                Component.empty(), Component.text("Click to view", RivetPalette.SECONDARY)));
    }

    private ItemStack snapshotIcon(SnapshotRecord record) {
        Material material = switch (record.reason()) {
            case "DEATH" -> Material.TOTEM_OF_UNDYING;
            case "ENDER_CHEST", "PRE_RESTORE_ENDER_CHEST" -> Material.ENDER_CHEST;
            default -> Material.BARREL;
        };
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Player: " + record.playerName(), NamedTextColor.GRAY));
        lore.add(Component.text("Category: " + friendly(record.reason()), NamedTextColor.GRAY));
        lore.add(Component.text("Saved " + StatisticsModule.friendlyElapsed(
                System.currentTimeMillis() - record.timestamp()) + " ago", NamedTextColor.WHITE)
            .hoverEvent(HoverEvent.showText(Component.text(exactTime(record.timestamp()),
                NamedTextColor.WHITE))));
        if (record.deathCause() != null && !record.deathCause().isBlank()) {
            lore.add(Component.text("Info: " + record.deathCause(), NamedTextColor.GRAY));
        }
        lore.add(Component.text(record.world() + " • " + coordinates(record), NamedTextColor.GRAY));
        lore.add(Component.text("Backup ID: #" + record.id(), NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("Click to check items", RivetPalette.SECONDARY));
        return item(material, Component.text("Backup • " + exactTime(record.timestamp()),
            RivetPalette.SECONDARY).decorate(TextDecoration.BOLD), lore);
    }

    private ItemStack stateInfo(SnapshotRecord record) {
        List<Component> lore = new ArrayList<>(List.of(
            Component.text("Player: " + record.playerName(), NamedTextColor.WHITE),
            Component.text("Category: " + friendly(record.reason()), NamedTextColor.GRAY),
            Component.text("Saved: " + exactTime(record.timestamp()), NamedTextColor.GRAY),
            Component.text("Location: " + record.world() + " • " + coordinates(record),
                NamedTextColor.GRAY)));
        if (!isEnderChest(record.reason())) {
            SnapshotState state = record.state();
            lore.add(Component.text("Health: " + trim(state.health()), NamedTextColor.WHITE));
            lore.add(Component.text("Hunger: " + state.hunger(), NamedTextColor.WHITE));
            lore.add(Component.text("XP: level " + state.xpLevel(), NamedTextColor.WHITE));
        }
        return item(Material.COMPASS, Component.text("Backup details", RivetPalette.SECONDARY), lore);
    }

    private void placeNavigation(RivetMenu menu, Inventory inventory, Map<Integer, String> controls,
                                 int page, int pages, String detail, TagResolver placeholders,
                                 boolean includePageInfo) {
        if (page > 0) menu.place(inventory, "previous-page",
            menu.item("previous-page", Material.ARROW, "<white>Previous page</white>", List.of()),
            List.of(45), controls);
        if (includePageInfo) menu.place(inventory, "page-info", menu.item("page-info", Material.BOOK,
            "<white>Page %page%/%pages%</white>", List.of("<gray>" + detail + "</gray>"),
            placeholders), List.of(49), controls);
        if (page + 1 < pages) menu.place(inventory, "next-page",
            menu.item("next-page", Material.ARROW, "<white>Next page</white>", List.of()),
            List.of(53), controls);
    }

    private RivetMenu menu(String name) {
        return new RivetMenu(plugin, configuration, "gui." + name);
    }

    private static Map<String, List<SnapshotRecord>> categories(List<SnapshotRecord> records,
                                                                 boolean includeAll) {
        Map<String, List<SnapshotRecord>> grouped = new LinkedHashMap<>();
        if (includeAll) grouped.put("ALL", List.copyOf(records));
        for (String reason : BACKUP_REASONS) {
            List<SnapshotRecord> matching = records.stream()
                .filter(record -> record.reason().equals(reason)).toList();
            if (!matching.isEmpty()) grouped.put(reason, matching);
        }
        records.stream().map(SnapshotRecord::reason).distinct()
            .filter(reason -> !grouped.containsKey(reason)).sorted()
            .forEach(reason -> grouped.put(reason, records.stream()
                .filter(record -> record.reason().equals(reason)).toList()));
        return java.util.Collections.unmodifiableMap(grouped);
    }

    static boolean matchesSearch(SnapshotState state, String query) {
        String filter = query.toLowerCase(Locale.ROOT);
        for (ItemStack item : allStateItems(state)) {
            if (matchesItem(item, filter, 0)) return true;
        }
        return false;
    }

    static boolean migrateLegacyConfiguration(YamlConfiguration configuration) {
        if (!configuration.getStringList("messages.usage.actions").equals(List.of(LEGACY_USAGE))) {
            return false;
        }
        configuration.set("messages.usage.actions", List.of(CURRENT_USAGE));
        return true;
    }

    private static boolean matchesItem(ItemStack item, String filter, int depth) {
        if (isEmpty(item)) return false;
        if (item.getType().name().toLowerCase(Locale.ROOT).contains(filter)) return true;
        ItemMeta meta;
        try {
            meta = item.getItemMeta();
        } catch (RuntimeException malformedItem) {
            return false;
        }
        if (meta == null) return false;
        if (contains(meta.displayName(), filter) || contains(meta.itemName(), filter)
            || meta.lore() != null && meta.lore().stream().anyMatch(line -> contains(line, filter))) {
            return true;
        }
        for (Map.Entry<Enchantment, Integer> enchantment : meta.getEnchants().entrySet()) {
            if ((enchantment.getKey().getKey() + " " + enchantment.getValue())
                .toLowerCase(Locale.ROOT).contains(filter)) return true;
        }
        if (meta instanceof EnchantmentStorageMeta stored) {
            for (Map.Entry<Enchantment, Integer> enchantment : stored.getStoredEnchants().entrySet()) {
                if ((enchantment.getKey().getKey() + " " + enchantment.getValue())
                    .toLowerCase(Locale.ROOT).contains(filter)) return true;
            }
        }
        if (depth < 4 && meta instanceof BlockStateMeta blockMeta
            && blockMeta.getBlockState() instanceof ShulkerBox shulker) {
            for (ItemStack nested : shulker.getInventory().getContents()) {
                if (matchesItem(nested, filter, depth + 1)) return true;
            }
        }
        return false;
    }

    private static boolean contains(Component component, String filter) {
        return component != null && PLAIN.serialize(component).toLowerCase(Locale.ROOT).contains(filter);
    }

    private static List<ItemStack> allStateItems(SnapshotState state) {
        List<ItemStack> items = new ArrayList<>();
        java.util.Collections.addAll(items, state.inventory());
        java.util.Collections.addAll(items, state.armour());
        items.add(state.offhand());
        return items;
    }

    private static List<ItemStack> savedItems(SnapshotRecord record) {
        return isEnderChest(record.reason())
            ? java.util.Arrays.asList(record.state().inventory()) : allStateItems(record.state());
    }

    private static boolean hasItems(SnapshotState state) {
        return allStateItems(state).stream().anyMatch(item -> !isEmpty(item));
    }

    private static boolean isEmpty(ItemStack item) {
        if (item == null) return true;
        String material = item.getType().name();
        return material.equals("AIR") || material.equals("CAVE_AIR") || material.equals("VOID_AIR");
    }

    private static boolean isEnderChest(String reason) {
        return reason.equals("ENDER_CHEST") || reason.equals("PRE_RESTORE_ENDER_CHEST");
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        message(sender, "messages.denied", "<white>%tag% You do not have permission to do that.</white>");
        return false;
    }

    private void usage(CommandSender sender) {
        message(sender, "messages.usage", "<white>%tag% Usage: /snapshot "
            + "&lt;view &lt;player&gt;|save &lt;player&gt;|saveall|search &lt;item&gt;|cleanup&gt;</white>");
    }

    private static UUID actorUuid(CommandSender sender, Player fallback) {
        return sender instanceof Player player ? player.getUniqueId() : fallback.getUniqueId();
    }

    private static int pages(int size) {
        return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private static void fill(Inventory inventory, int start, int end, ItemStack item) {
        for (int slot = start; slot < end; slot++) inventory.setItem(slot, item);
    }

    private static ItemStack button(Material material, String name) {
        return item(material, Component.text(name, NamedTextColor.WHITE), List.of());
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static String coordinates(SnapshotRecord record) {
        return (long) Math.floor(record.x()) + ", " + (long) Math.floor(record.y()) + ", "
            + (long) Math.floor(record.z());
    }

    private static String exactTime(long timestamp) {
        return EXACT_TIME.format(Instant.ofEpochMilli(timestamp));
    }

    private static String friendly(String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value)
            : String.format(Locale.ROOT, "%.1f", value);
    }

    private void message(CommandSender recipient, String path, String fallback,
                         TagResolver... placeholders) {
        TagResolver[] all = new TagResolver[placeholders.length + 1];
        all[0] = Placeholder.component("tag", RivetMessages.tag(plugin));
        System.arraycopy(placeholders, 0, all, 1, placeholders.length);
        plugin.messageActions().run(recipient, configuration, path, fallback, all);
    }

    private void notFound(CommandSender sender) {
        message(sender, "messages.not-found", "<white>%tag% That player could not be found.</white>");
    }

    private void loadFailed(CommandSender sender, Throwable failure) {
        if (failure != null) plugin.getLogger().log(Level.WARNING, "Could not load backups", failure);
        message(sender, "messages.load-failed",
            "<white>%tag% Backups could not be loaded. Check the console.</white>");
    }

    private void onMain(Runnable task) {
        if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, task);
    }

    static boolean readOnlyHolder(InventoryHolder holder) {
        return holder instanceof SnapshotGuiHolder;
    }

    static RestoreStep restoreStep(boolean requireConfirmation, boolean confirmed,
                                   boolean createSafetySnapshot) {
        if (requireConfirmation && !confirmed) return RestoreStep.CONFIRM;
        return createSafetySnapshot ? RestoreStep.SAFETY_SNAPSHOT : RestoreStep.APPLY;
    }

    static void applyState(SnapshotState state, RestoreTarget target) {
        target.clearInventory();
        target.setInventory(state.inventory());
        target.setArmour(state.armour());
        target.setOffhand(state.offhand());
        target.setXp(state.xpLevel(), Math.clamp(state.xpProgress(), 0F, 1F));
        target.setHealth(Math.clamp(state.health(), Math.min(0.5D, target.maxHealth()),
            target.maxHealth()));
        target.setFood(Math.clamp(state.hunger(), 0, 20),
            Math.clamp(state.saturation(), 0F, 20F));
    }

    static double restorableHealth(String reason, double health) {
        return "DEATH".equals(reason) && health <= 0D ? 1D : health;
    }

    enum RestoreStep { CONFIRM, SAFETY_SNAPSHOT, APPLY }

    interface RestoreTarget {
        void clearInventory();
        void setInventory(ItemStack[] contents);
        void setArmour(ItemStack[] armour);
        void setOffhand(ItemStack item);
        void setXp(int level, float progress);
        void setHealth(double health);
        void setFood(int hunger, float saturation);
        double maxHealth();
    }

    private static final class BukkitRestoreTarget implements RestoreTarget {
        private final Player player;
        private BukkitRestoreTarget(Player player) { this.player = player; }
        @Override public void clearInventory() { player.getInventory().clear(); }
        @Override public void setInventory(ItemStack[] contents) { player.getInventory().setStorageContents(contents); }
        @Override public void setArmour(ItemStack[] armour) { player.getInventory().setArmorContents(armour); }
        @Override public void setOffhand(ItemStack item) { player.getInventory().setItemInOffHand(item); }
        @Override public void setXp(int level, float progress) { player.setLevel(level); player.setExp(progress); }
        @Override public void setHealth(double health) { player.setHealth(health); }
        @Override public void setFood(int hunger, float saturation) { player.setFoodLevel(hunger); player.setSaturation(saturation); }
        @Override public double maxHealth() {
            var attribute = player.getAttribute(Attribute.MAX_HEALTH);
            return attribute == null ? 20D : attribute.getValue();
        }
    }

    interface SnapshotGuiHolder extends InventoryHolder { }

    static final class SnapshotCategoryHolder implements SnapshotGuiHolder {
        private final SnapshotTarget target;
        private final String label;
        private final List<SnapshotRecord> records;
        private final Map<String, List<SnapshotRecord>> categories;
        private final List<String> names;
        private final int page;
        private final int pageSize;
        private final Map<Integer, String> selections = new java.util.HashMap<>();
        private final Map<Integer, String> controls = new java.util.HashMap<>();
        private Inventory inventory;
        SnapshotCategoryHolder(SnapshotTarget target, String label, List<SnapshotRecord> records,
                               Map<String, List<SnapshotRecord>> categories, List<String> names,
                               int page, int pageSize) {
            this.target = target;
            this.label = label;
            this.records = List.copyOf(records);
            this.categories = categories;
            this.names = names;
            this.page = page;
            this.pageSize = pageSize;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    static final class SnapshotListHolder implements SnapshotGuiHolder {
        private final SnapshotListContext context;
        private final int pageSize;
        private final Map<Integer, SnapshotRecord> selections = new java.util.HashMap<>();
        private final Map<Integer, String> controls = new java.util.HashMap<>();
        private Inventory inventory;
        SnapshotListHolder(SnapshotListContext context, int pageSize) {
            this.context = context;
            this.pageSize = pageSize;
        }
        SnapshotListHolder(SnapshotTarget target, List<SnapshotRecord> records, int page) {
            this(new SnapshotListContext(target, target.name(), records, 0, "ALL", records, page),
                PAGE_SIZE);
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    static final class SnapshotPreviewHolder implements SnapshotGuiHolder {
        private final SnapshotTarget target;
        private final SnapshotRecord record;
        private final SnapshotListContext context;
        private final Map<Integer, String> controls = new java.util.HashMap<>();
        private Inventory inventory;
        SnapshotPreviewHolder(SnapshotTarget target, SnapshotRecord record,
                              SnapshotListContext context) {
            this.target = target;
            this.record = record;
            this.context = context;
        }
        SnapshotPreviewHolder(SnapshotTarget target, SnapshotRecord record) {
            this(target, record, new SnapshotListContext(target, target.name(), List.of(record),
                0, "ALL", List.of(record), 0));
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    static final class SnapshotConfirmHolder implements SnapshotGuiHolder {
        private final SnapshotTarget target;
        private final SnapshotRecord record;
        private final SnapshotListContext context;
        private final Map<Integer, String> controls = new java.util.HashMap<>();
        private Inventory inventory;
        SnapshotConfirmHolder(SnapshotTarget target, SnapshotRecord record,
                              SnapshotListContext context) {
            this.target = target;
            this.record = record;
            this.context = context;
        }
        SnapshotConfirmHolder(SnapshotTarget target, SnapshotRecord record) {
            this(target, record, new SnapshotListContext(target, target.name(), List.of(record),
                0, "ALL", List.of(record), 0));
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    record SnapshotListContext(SnapshotTarget target, String label,
                               List<SnapshotRecord> allRecords, int categoryPage,
                               String category, List<SnapshotRecord> records, int page) {
        SnapshotListContext {
            allRecords = List.copyOf(allRecords);
            records = List.copyOf(records);
        }
        SnapshotListContext withPage(int nextPage) {
            return new SnapshotListContext(target, label, allRecords, categoryPage,
                category, records, nextPage);
        }
    }

    record SnapshotTarget(UUID uuid, String name) {
        SnapshotTarget { name = name == null ? uuid.toString() : name; }
    }
}
