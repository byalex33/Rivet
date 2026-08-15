package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

final class SnapshotModule implements Listener {
    private static final int LIST_PAGE_SIZE = RivetGui.CONTENT_SLOTS.length;
    private static final DateTimeFormatter EXACT_TIME = DateTimeFormatter
        .ofPattern("d MMM uuuu 'at' HH:mm z", Locale.UK).withZone(ZoneId.systemDefault());
    private final RivetPlugin plugin;
    private final YamlConfiguration configuration;
    private final SnapshotStorage storage;
    private final Set<UUID> restoresInProgress = new HashSet<>();
    private SnapshotSettings settings;

    SnapshotModule(RivetPlugin plugin) throws SQLException {
        this.plugin = plugin;
        configuration = plugin.settings("snapshots");
        settings = new SnapshotSettings(configuration);
        storage = new SnapshotStorage(plugin.getDataFolder().toPath().resolve("snapshots.db"),
            throwable -> plugin.getLogger().log(Level.SEVERE, "Snapshot storage failed", throwable));
        cleanup();
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,
            this::cleanup, 20L * 60 * 60, 20L * 60 * 60 * 24);
    }

    boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rivet.snapshots.view")) {
            message(sender, "messages.denied", "<white>%tag% You do not have permission to do that.</white>");
            return true;
        }
        if (!(sender instanceof Player viewer)) {
            message(sender, "messages.player-only", "<white>%tag% This command is only available to players.</white>");
            return true;
        }
        if (args.length != 1) {
            message(viewer, "messages.usage", "<white>%tag% Usage: /snapshot &lt;player&gt;</white>");
            return true;
        }
        Player online = plugin.getServer().getPlayerExact(args[0]);
        if (online != null && !viewer.canSee(online)) {
            notFound(viewer);
            return true;
        }
        OfflinePlayer cached = online == null
            ? plugin.getServer().getOfflinePlayerIfCached(args[0]) : online;
        if (cached != null && (cached.hasPlayedBefore() || cached.isOnline())) {
            openResolved(viewer, new SnapshotTarget(cached.getUniqueId(), cached.getName()), 0);
            return true;
        }
        message(viewer, "messages.loading", "<white>%tag% Loading snapshots...</white>");
        storage.findPlayer(args[0]).whenComplete((uuid, failure) -> onMain(() -> {
            if (!viewer.isOnline()) {
                return;
            }
            if (failure != null) {
                loadFailed(viewer, failure);
            } else if (uuid == null) {
                notFound(viewer);
            } else {
                openResolved(viewer, new SnapshotTarget(uuid, args[0]), 0);
            }
        }));
        return true;
    }

    List<String> completions(CommandSender sender, String[] args) {
        if (args.length != 1 || !(sender instanceof Player viewer)
            || !sender.hasPermission("rivet.snapshots.view")) {
            return List.of();
        }
        if (!sender.hasPermission("rivet.snapshots.others")) {
            return List.of(viewer.getName());
        }
        return plugin.getServer().getOnlinePlayers().stream().filter(viewer::canSee)
            .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    void reload() {
        settings = new SnapshotSettings(configuration);
        cleanup();
    }

    void shutdown() {
        restoresInProgress.clear();
        storage.close();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!settings.saveOnDeath()) {
            return;
        }
        Player player = event.getEntity();
        String cause = player.getLastDamageCause() == null ? null
            : player.getLastDamageCause().getCause().name();
        saveCaptured(capture(player, "DEATH", cause), player.getUniqueId(), player.getName(),
            settings.auditCreations());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder rawHolder = event.getView().getTopInventory().getHolder(false);
        if (!readOnlyHolder(rawHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer) || event.getRawSlot() < 0
            || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        int slot = event.getRawSlot();
        if (rawHolder instanceof SnapshotListHolder holder) {
            listClick(viewer, holder, slot);
        } else if (rawHolder instanceof SnapshotPreviewHolder holder) {
            previewClick(viewer, holder, slot);
        } else if (rawHolder instanceof SnapshotConfirmHolder holder) {
            confirmClick(viewer, holder, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (readOnlyHolder(event.getView().getTopInventory().getHolder(false))) {
            event.setCancelled(true);
        }
    }

    private void openResolved(Player viewer, SnapshotTarget target, int page) {
        if (!viewer.getUniqueId().equals(target.uuid())
            && !viewer.hasPermission("rivet.snapshots.others")) {
            message(viewer, "messages.denied-others",
                "<white>%tag% You cannot view another player's snapshots.</white>");
            return;
        }
        storage.list(target.uuid(), settings.maxPerPlayer()).whenComplete((records, failure) ->
            onMain(() -> {
                if (!viewer.isOnline()) {
                    return;
                }
                if (failure != null) {
                    loadFailed(viewer, failure);
                } else if (records.isEmpty()) {
                    message(viewer, "messages.none",
                        "<white>%tag% No snapshots were found for <#f72a4c>%player%</#f72a4c>.</white>",
                        Placeholder.unparsed("player", target.name()));
                } else {
                    openList(viewer, target, records, page);
                }
            }));
    }

    private void openList(Player viewer, SnapshotTarget target,
                          List<SnapshotRecord> records, int requestedPage) {
        int pages = Math.max(1, (records.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE);
        int page = Math.clamp(requestedPage, 0, pages - 1);
        SnapshotListHolder holder = new SnapshotListHolder(target, records, page);
        holder.inventory = plugin.getServer().createInventory(holder, 54,
            RivetGui.title(target.name() + "'s Snapshots"));
        RivetGui.frame(holder.inventory);
        int start = page * LIST_PAGE_SIZE;
        for (int index = start; index < Math.min(records.size(), start + LIST_PAGE_SIZE); index++) {
            SnapshotRecord record = records.get(index);
            holder.inventory.setItem(RivetGui.CONTENT_SLOTS[index - start], snapshotIcon(record));
        }
        if (page > 0) {
            holder.inventory.setItem(45, button(Material.ARROW, "Previous page"));
        }
        holder.inventory.setItem(49, item(Material.BOOK,
            Component.text("Page " + (page + 1) + "/" + pages, NamedTextColor.WHITE),
            List.of(Component.text(records.size() + " recent snapshots", NamedTextColor.GRAY))));
        if (page + 1 < pages) {
            holder.inventory.setItem(53, button(Material.ARROW, "Next page"));
        }
        viewer.openInventory(holder.inventory);
    }

    private void listClick(Player viewer, SnapshotListHolder holder, int slot) {
        if (slot == 45 && holder.page > 0) {
            openList(viewer, holder.target, holder.records, holder.page - 1);
            return;
        }
        if (slot == 53 && (holder.page + 1) * LIST_PAGE_SIZE < holder.records.size()) {
            openList(viewer, holder.target, holder.records, holder.page + 1);
            return;
        }
        int contentIndex = RivetGui.contentIndex(slot);
        int index = holder.page * LIST_PAGE_SIZE + contentIndex;
        if (contentIndex < 0 || index >= holder.records.size()) {
            return;
        }
        long id = holder.records.get(index).id();
        storage.load(id).whenComplete((record, failure) -> onMain(() -> {
            if (!viewer.isOnline()) {
                return;
            }
            if (failure != null || record == null) {
                loadFailed(viewer, failure);
            } else if (!record.playerUuid().equals(holder.target.uuid())) {
                notFound(viewer);
            } else {
                openPreview(viewer, holder.target, record);
            }
        }));
    }

    private void openPreview(Player viewer, SnapshotTarget target, SnapshotRecord record) {
        SnapshotPreviewHolder holder = new SnapshotPreviewHolder(target, record);
        holder.inventory = plugin.getServer().createInventory(holder, 54,
            RivetGui.title("Snapshot #" + record.id()));
        SnapshotState state = record.state();
        ItemStack[] inventory = state.inventory();
        for (int savedSlot = 9; savedSlot < Math.min(36, inventory.length); savedSlot++) {
            holder.inventory.setItem(savedSlot - 9, inventory[savedSlot]);
        }
        for (int savedSlot = 0; savedSlot < Math.min(9, inventory.length); savedSlot++) {
            holder.inventory.setItem(27 + savedSlot, inventory[savedSlot]);
        }
        ItemStack separator = RivetGui.pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 36; slot < 45; slot++) {
            holder.inventory.setItem(slot, separator);
        }
        ItemStack[] armour = state.armour();
        for (int index = 0; index < Math.min(4, armour.length); index++) {
            holder.inventory.setItem(45 + index, armour[index]);
        }
        holder.inventory.setItem(49, state.offhand());
        holder.inventory.setItem(50, button(Material.ARROW, "Back"));
        holder.inventory.setItem(51, stateInfo(record));
        holder.inventory.setItem(52, button(Material.ENDER_PEARL, "Teleport to location"));
        holder.inventory.setItem(53, button(Material.TOTEM_OF_UNDYING, "Restore snapshot"));
        viewer.openInventory(holder.inventory);
    }

    private void previewClick(Player viewer, SnapshotPreviewHolder holder, int slot) {
        if (slot == 50) {
            openResolved(viewer, holder.target, 0);
        } else if (slot == 52) {
            teleport(viewer, holder.record);
        } else if (slot == 53) {
            requestRestore(viewer, holder.target, holder.record);
        }
    }

    private void requestRestore(Player viewer, SnapshotTarget target, SnapshotRecord record) {
        if (!viewer.hasPermission("rivet.snapshots.restore")) {
            message(viewer, "messages.denied-restore",
                "<white>%tag% You do not have permission to restore snapshots.</white>");
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
        SnapshotConfirmHolder holder = new SnapshotConfirmHolder(target, record);
        holder.inventory = plugin.getServer().createInventory(holder, 27,
            RivetGui.title("Confirm Restore"));
        ItemStack background = RivetGui.pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < holder.inventory.getSize(); slot++) {
            holder.inventory.setItem(slot, background);
        }
        holder.inventory.setItem(11, item(Material.RED_CONCRETE,
            Component.text("Cancel", NamedTextColor.RED).decorate(TextDecoration.BOLD),
            List.of(Component.text("Return without changing anything", NamedTextColor.GRAY))));
        holder.inventory.setItem(13, item(Material.PAPER,
            Component.text("This replaces current state", NamedTextColor.YELLOW), List.of(
                Component.text("Inventory, armour, offhand, XP,", NamedTextColor.GRAY),
                Component.text("health, hunger and saturation", NamedTextColor.GRAY),
                Component.text("will be replaced exactly.", NamedTextColor.RED))));
        holder.inventory.setItem(15, item(Material.LIME_CONCRETE,
            Component.text("Confirm restore", NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD), List.of(
                Component.text(settings.createSafetySnapshot()
                    ? "A PRE_RESTORE safety snapshot will be created first."
                    : "Safety snapshots are disabled in settings.", NamedTextColor.GRAY))));
        viewer.openInventory(holder.inventory);
    }

    private void confirmClick(Player viewer, SnapshotConfirmHolder holder, int slot) {
        if (slot == 11) {
            openPreview(viewer, holder.target, holder.record);
        } else if (slot == 15) {
            beginRestore(viewer, holder.target, holder.record);
        }
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
        CapturedSnapshot safety = capture(online, "PRE_RESTORE", null);
        storage.save(safety, settings).whenComplete((saved, failure) -> onMain(() -> {
            if (!staff.isOnline()) {
                restoresInProgress.remove(target.uuid());
                return;
            }
            if (failure != null || saved == null) {
                restoresInProgress.remove(target.uuid());
                message(staff, "messages.safety-failed",
                    "<white>%tag% The safety snapshot failed, so nothing was restored.</white>");
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
            } else {
                finishRestore(staff, current, record);
            }
        }));
    }

    private void finishRestore(Player staff, Player target, SnapshotRecord record) {
        try {
            target.closeInventory();
            applyState(record.state(), new BukkitRestoreTarget(target));
            target.updateInventory();
            plugin.recordSnapshotAudit(AuditAction.SNAPSHOT_RESTORE, staff.getUniqueId(),
                staff.getName(), record);
            message(staff, "messages.restored",
                "<white>%tag% Restored snapshot <#f72a4c>#%id%</#f72a4c> to <#f72a4c>%player%</#f72a4c>.</white>",
                Placeholder.unparsed("id", Long.toString(record.id())),
                Placeholder.unparsed("player", target.getName()));
            if (!staff.equals(target)) {
                message(target, "messages.restored-target",
                    "<white>%tag% Your inventory and state were restored from snapshot <#f72a4c>#%id%</#f72a4c>.</white>",
                    Placeholder.unparsed("id", Long.toString(record.id())));
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not apply snapshot #" + record.id(), exception);
            message(staff, "messages.restore-failed",
                "<white>%tag% The snapshot could not be restored. Check the console.</white>");
        } finally {
            restoresInProgress.remove(target.getUniqueId());
        }
    }

    private void teleport(Player viewer, SnapshotRecord record) {
        if (!viewer.hasPermission("rivet.snapshots.teleport")) {
            message(viewer, "messages.denied-teleport",
                "<white>%tag% You do not have permission to teleport to snapshots.</white>");
            return;
        }
        World world = plugin.getServer().getWorld(record.world());
        if (world == null) {
            message(viewer, "messages.world-unavailable",
                "<white>%tag% The snapshot world is not loaded.</white>");
            return;
        }
        viewer.closeInventory();
        viewer.teleportAsync(new Location(world, record.x(), record.y(), record.z()));
    }

    private Player visibleTarget(Player viewer, UUID uuid) {
        Player target = plugin.getServer().getPlayer(uuid);
        return target != null && viewer.canSee(target) ? target : null;
    }

    private CapturedSnapshot capture(Player player, String reason, String deathCause) {
        Location location = player.getLocation();
        PlayerInventory inventory = player.getInventory();
        SnapshotState state = new SnapshotState(inventory.getStorageContents(),
            inventory.getArmorContents(), inventory.getItemInOffHand(), player.getLevel(),
            player.getExp(), restorableHealth(reason, player.getHealth()), player.getFoodLevel(),
            player.getSaturation());
        return new CapturedSnapshot(player.getUniqueId(), player.getName(), reason,
            System.currentTimeMillis(), location.getWorld().getName(), location.getX(),
            location.getY(), location.getZ(), deathCause, state);
    }

    private void saveCaptured(CapturedSnapshot captured, UUID actorUuid, String actorName,
                              boolean auditCreation) {
        storage.save(captured, settings).whenComplete((record, failure) -> {
            if (failure != null || record == null) {
                plugin.getLogger().log(Level.SEVERE, "Could not save " + captured.reason()
                    + " snapshot for " + captured.playerName(), failure);
            } else if (auditCreation) {
                onMain(() -> plugin.recordSnapshotAudit(AuditAction.SNAPSHOT_CREATE,
                    actorUuid, actorName, record));
            }
        });
    }

    private void cleanup() {
        storage.cleanup(settings).exceptionally(failure -> {
            plugin.getLogger().log(Level.WARNING, "Could not clean old snapshots", failure);
            return null;
        });
    }

    private ItemStack snapshotIcon(SnapshotRecord record) {
        Material material = record.reason().equals("DEATH")
            ? Material.SKELETON_SKULL : Material.TOTEM_OF_UNDYING;
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Reason: " + record.reason(), NamedTextColor.GRAY));
        lore.add(Component.text("Saved " + StatisticsModule.friendlyElapsed(
                System.currentTimeMillis() - record.timestamp()) + " ago", NamedTextColor.WHITE)
            .hoverEvent(HoverEvent.showText(Component.text(exactTime(record.timestamp()),
                NamedTextColor.WHITE))));
        if (record.deathCause() != null && !record.deathCause().isBlank()) {
            lore.add(Component.text("Death cause: " + friendly(record.deathCause()),
                NamedTextColor.GRAY));
        }
        lore.add(Component.text(record.world() + " • " + coordinates(record), NamedTextColor.GRAY));
        lore.add(Component.text("Snapshot ID: #" + record.id(), NamedTextColor.DARK_GRAY));
        lore.add(Component.empty());
        lore.add(Component.text("Click to preview", RivetPalette.SECONDARY));
        return item(material, Component.text("Snapshot #" + record.id(),
            RivetPalette.SECONDARY).decorate(TextDecoration.BOLD), lore);
    }

    private ItemStack stateInfo(SnapshotRecord record) {
        SnapshotState state = record.state();
        List<Component> lore = List.of(
            Component.text("Reason: " + record.reason(), NamedTextColor.GRAY),
            Component.text("Saved: " + exactTime(record.timestamp()), NamedTextColor.GRAY),
            Component.text("Health: " + trim(state.health()), NamedTextColor.WHITE),
            Component.text("Hunger: " + state.hunger(), NamedTextColor.WHITE),
            Component.text("Saturation: " + trim(state.saturation()), NamedTextColor.WHITE),
            Component.text("XP: level " + state.xpLevel() + " (" + Math.round(state.xpProgress() * 100)
                + "%)", NamedTextColor.WHITE),
            Component.text("Location: " + record.world() + " • " + coordinates(record),
                NamedTextColor.GRAY),
            Component.text("Equipment slots: boots, legs, chest, helmet, offhand",
                NamedTextColor.DARK_GRAY));
        return item(Material.COMPASS, Component.text("Saved player state",
            RivetPalette.SECONDARY), lore);
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
        if (failure != null) {
            plugin.getLogger().log(Level.WARNING, "Could not load snapshots", failure);
        }
        message(sender, "messages.load-failed",
            "<white>%tag% Snapshots could not be loaded. Check the console.</white>");
    }

    private void onMain(Runnable task) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    static boolean readOnlyHolder(InventoryHolder holder) {
        return holder instanceof SnapshotGuiHolder;
    }

    static RestoreStep restoreStep(boolean requireConfirmation, boolean confirmed,
                                   boolean createSafetySnapshot) {
        if (requireConfirmation && !confirmed) {
            return RestoreStep.CONFIRM;
        }
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

    enum RestoreStep {
        CONFIRM,
        SAFETY_SNAPSHOT,
        APPLY
    }

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

        private BukkitRestoreTarget(Player player) {
            this.player = player;
        }

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

    interface SnapshotGuiHolder extends InventoryHolder {
    }

    static final class SnapshotListHolder implements SnapshotGuiHolder {
        private final SnapshotTarget target;
        private final List<SnapshotRecord> records;
        private final int page;
        private Inventory inventory;

        SnapshotListHolder(SnapshotTarget target, List<SnapshotRecord> records, int page) {
            this.target = target;
            this.records = List.copyOf(records);
            this.page = page;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    static final class SnapshotPreviewHolder implements SnapshotGuiHolder {
        private final SnapshotTarget target;
        private final SnapshotRecord record;
        private Inventory inventory;

        SnapshotPreviewHolder(SnapshotTarget target, SnapshotRecord record) {
            this.target = target;
            this.record = record;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    static final class SnapshotConfirmHolder implements SnapshotGuiHolder {
        private final SnapshotTarget target;
        private final SnapshotRecord record;
        private Inventory inventory;

        SnapshotConfirmHolder(SnapshotTarget target, SnapshotRecord record) {
            this.target = target;
            this.record = record;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    record SnapshotTarget(UUID uuid, String name) {
        SnapshotTarget {
            name = name == null ? uuid.toString() : name;
        }
    }
}
