package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class FilterModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final YamlConfiguration data;
    private final Map<UUID, FilterState> filters = new HashMap<>();
    private final Map<UUID, Long> lastFeedback = new HashMap<>();

    FilterModule(RivetPlugin plugin) {
        this.plugin = plugin;
        data = plugin.data("filters");
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players != null) {
            players.getKeys(false).forEach(value -> load(value, players.getConfigurationSection(value)));
        }
    }

    boolean command(Player player, String[] args) {
        if (args.length == 0) {
            open(player, 0);
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> addCommand(player, args);
            case "remove" -> openCommand(player, args);
            case "list" -> list(player, args);
            case "clear" -> clear(player, args);
            case "toggle" -> toggle(player, args);
            case "help" -> help(player, args);
            default -> {
                help(player, args);
                yield true;
            }
        };
    }

    List<String> completions(String[] args) {
        if (args.length == 1) {
            return List.of("add", "remove", "list", "clear", "toggle", "help");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            return java.util.Arrays.stream(Material.values())
                .filter(material -> material.isItem() && !material.isAir())
                .map(material -> material.name().toLowerCase(Locale.ROOT)).sorted().toList();
        }
        return List.of();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)
            || !blocksPickup(state(player.getUniqueId()).enabled(), blockedWorld(player),
                state(player.getUniqueId()).items(), event.getItem().getItemStack().getType())) {
            return;
        }
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        if (now - lastFeedback.getOrDefault(player.getUniqueId(), 0L) < 500) {
            return;
        }
        lastFeedback.put(player.getUniqueId(), now);
        var settings = plugin.settings("filter");
        if (settings.getBoolean("feedback.action-bar", true)) {
            plugin.messageActions().run(player, settings, "messages.blocked-pickup", "actionbar",
                "<white>Pickup blocked: <#f72a4c>%item%</#f72a4c>",
                Placeholder.unparsed("item", display(event.getItem().getItemStack().getType())));
        }
        String configuredSound = settings.getString("feedback.sound", "");
        Sound sound = ConfiguredEffect.resolveSound(configuredSound);
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 0.6f, 0.8f);
        } else if (configuredSound != null && !configuredSound.isBlank()) {
            plugin.getLogger().warning("Invalid filter feedback sound '" + configuredSound + "'.");
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof FilterHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
            || !player.getUniqueId().equals(holder.owner) || event.getRawSlot() < 0) {
            return;
        }
        int slot = event.getRawSlot();
        Material material = holder.items.get(slot);
        String control = holder.controls.get(slot);
        if (material != null) {
            if (remove(state(player.getUniqueId()).items(), material)) {
                if (save(player)) {
                    send(player, "<white>Removed <#f72a4c>" + display(material) + "</#f72a4c> from your filter.");
                } else {
                    state(player.getUniqueId()).items().add(material);
                    syncData(player.getUniqueId());
                }
            }
            open(player, holder.page);
        } else if ("previous-page".equals(control) && holder.page > 0) {
            open(player, holder.page - 1);
        } else if ("add-held-item".equals(control)) {
            addHeld(player);
            open(player, holder.page);
        } else if ("status".equals(control)) {
            toggle(player, new String[]{"toggle"});
            open(player, holder.page);
        } else if ("clear-all".equals(control)) {
            if (event.isShiftClick()) {
                clear(player, new String[]{"clear"});
                open(player, 0);
            } else {
                send(player, "<white>Shift-click the clear button to confirm.");
            }
        } else if ("next-page".equals(control)
            && (holder.page + 1) * holder.pageSize < sorted(player).size()) {
            open(player, holder.page + 1);
        }
        new RivetMenu(plugin, plugin.settings("filter"), "gui",
            plugin.settings("filter").contains("gui.title"))
            .click(player, material == null ? control : "filtered-item", event.getClick(),
                Placeholder.unparsed("item", material == null ? "" : display(material)));
    }

    void shutdown() {
        filters.clear();
        lastFeedback.clear();
    }

    private boolean addCommand(Player player, String[] args) {
        if (args.length == 1) {
            addHeld(player);
            return true;
        }
        if (args.length != 2) {
            send(player, "<white>Usage: /filter add [item]");
            return true;
        }
        Material material = Material.matchMaterial(args[1]);
        if (material == null || material.isAir() || !material.isItem()) {
            send(player, "<white>That is not a valid item material.");
            return true;
        }
        add(player, material);
        return true;
    }

    private void addHeld(Player player) {
        Material material = player.getInventory().getItemInMainHand().getType();
        if (material.isAir()) {
            send(player, "<white>Hold an item or use <#f72a4c>/filter add &lt;item&gt;</#f72a4c>.");
            return;
        }
        add(player, material);
    }

    private void add(Player player, Material material) {
        FilterState state = state(player.getUniqueId());
        int maximum = Math.max(1, plugin.settings("filter").getInt("maximum-size", 50));
        if (state.items().contains(material)) {
            if (state.enabled()) {
                send(player, "<white>That material is already filtered.</white>");
                return;
            }
            enable(state);
            if (save(player)) {
                send(player, "<white>That material was already filtered; item filtering is now enabled.</white>");
            } else {
                state.enabled = false;
                syncData(player.getUniqueId());
            }
            return;
        }
        if (!add(state.items(), material, maximum, player.hasPermission("rivet.filter.admin"))) {
            send(player, "<white>Your filter is full (<#f72a4c>" + maximum + "</#f72a4c> materials).");
            return;
        }
        boolean wasEnabled = state.enabled();
        enable(state);
        if (save(player)) {
            send(player, "<white>Added <#f72a4c>" + display(material) + "</#f72a4c> to your filter.");
        } else {
            state.items().remove(material);
            state.enabled = wasEnabled;
            syncData(player.getUniqueId());
        }
    }

    private boolean openCommand(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<white>Usage: /filter remove");
            return true;
        }
        open(player, 0);
        return true;
    }

    private boolean list(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<white>Usage: /filter list");
            return true;
        }
        List<Material> materials = sorted(player);
        plugin.messageActions().run(player, plugin.settings("filter"), "messages.list",
            "<#f72a4c>Filtered items:</#f72a4c> <#f72a4c>%items%</#f72a4c>",
            Placeholder.unparsed("items", materials.isEmpty() ? "none"
                : String.join(", ", materials.stream().map(FilterModule::display).toList())));
        return true;
    }

    private boolean clear(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<white>Usage: /filter clear");
            return true;
        }
        FilterState state = state(player.getUniqueId());
        if (state.items().isEmpty()) {
            send(player, "<white>Your filter is already empty.");
            return true;
        }
        Set<Material> old = EnumSet.copyOf(state.items());
        state.items().clear();
        if (!save(player)) {
            state.items().addAll(old);
            syncData(player.getUniqueId());
            return true;
        }
        send(player, "<white>Your item filter was cleared.");
        return true;
    }

    private boolean toggle(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<white>Usage: /filter toggle");
            return true;
        }
        FilterState state = state(player.getUniqueId());
        toggle(state);
        if (!save(player)) {
            toggle(state);
            syncData(player.getUniqueId());
            return true;
        }
        send(player, state.enabled ? "<white>Item filtering enabled.</white>"
            : "<white>Item filtering disabled.</white>");
        return true;
    }

    private boolean help(Player player, String[] args) {
        send(player, "<#f72a4c>/filter</#f72a4c> <white>GUI</white>  <#f72a4c>add [item]</#f72a4c>  <#f72a4c>remove</#f72a4c>  "
            + "<#f72a4c>list</#f72a4c>  <#f72a4c>clear</#f72a4c>  <#f72a4c>toggle</#f72a4c>");
        return true;
    }

    private void open(Player player, int requestedPage) {
        YamlConfiguration settings = plugin.settings("filter");
        boolean legacy = settings.contains("gui.title");
        RivetMenu menu = new RivetMenu(plugin, settings, "gui", legacy);
        int size = menu.size(54);
        List<Integer> contentSlots = menu.slots("filtered-item", size,
            java.util.Arrays.stream(RivetGui.CONTENT_SLOTS).boxed().toList());
        if (contentSlots.isEmpty()) {
            contentSlots = List.of(22);
        }
        int pageSize = contentSlots.size();
        List<Material> all = sorted(player);
        int lastPage = Math.max(0, (all.size() - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, lastPage));
        List<Material> pageItems = new ArrayList<>(all.subList(page * pageSize,
            Math.min(all.size(), (page + 1) * pageSize)));
        FilterHolder holder = new FilterHolder(player.getUniqueId(), page, pageSize);
        TagResolver pagePlaceholders = TagResolver.resolver(
            Placeholder.unparsed("page", Integer.toString(page + 1)),
            Placeholder.unparsed("pages", Integer.toString(lastPage + 1)));
        String legacyTitle = settings.getString("gui.title", "");
        Component title = legacy && !legacyTitle.equals(
            "<#f72a4c><bold>RIVET</bold></#f72a4c> <dark_gray>•</dark_gray> <white>Item Filter</white>")
                ? MM.deserialize(legacyTitle, pagePlaceholders)
                : menu.title("<white>Item Filter</white>", pagePlaceholders);
        holder.inventory = plugin.getServer().createInventory(holder, size, title);
        Set<String> handled = Set.of("filler", "filtered-item", "empty", "previous-page",
            "add-held-item", "status", "clear-all", "next-page");
        menu.place(holder.inventory, "filler", menu.item("filler",
            Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of(), false),
            List.of(), holder.controls);
        for (int index = 0; index < pageItems.size(); index++) {
            Material material = pageItems.get(index);
            ItemStack icon = menu.item("filtered-item", material,
                MM.deserialize("<white>" + titleCase(display(material)) + "</white>"),
                List.of(MM.deserialize("<dark_gray>Blocked from pickup</dark_gray>"),
                    Component.empty(), MM.deserialize("<#f72a4c>Click to remove</#f72a4c>")), false,
                Placeholder.unparsed("item", titleCase(display(material))),
                Placeholder.unparsed("material", material.name()));
            int slot = contentSlots.get(index);
            holder.inventory.setItem(slot, icon);
            holder.items.put(slot, material);
            holder.controls.put(slot, "filtered-item");
        }
        if (pageItems.isEmpty()) {
            menu.place(holder.inventory, "empty", menu.item("empty", Material.HOPPER,
                "<white>No filtered items</white>",
                List.of("<gray>Hold an item and use Add held item below</gray>")),
                List.of(22), holder.controls);
        }
        if (page > 0) {
            menu.place(holder.inventory, "previous-page", menu.item("previous-page", Material.ARROW,
                "<white>Previous page</white>", List.of()), List.of(45), holder.controls);
        }
        menu.place(holder.inventory, "add-held-item", menu.item("add-held-item", Material.HOPPER,
            "<white>Add held item</white>", List.of("<gray>Adds the item in your main hand</gray>")),
            List.of(47), holder.controls);
        boolean enabled = state(player.getUniqueId()).enabled();
        int maximum = Math.max(1, settings.getInt("maximum-size", 50));
        TagResolver statusPlaceholders = TagResolver.resolver(pagePlaceholders,
            Placeholder.unparsed("status", enabled ? "enabled" : "paused"),
            Placeholder.unparsed("used", Integer.toString(all.size())),
            Placeholder.unparsed("maximum", Integer.toString(maximum)));
        menu.place(holder.inventory, "status", menu.item("status",
            enabled ? Material.LIME_DYE : Material.GRAY_DYE,
            "<white>Filtering %status%</white>", List.of("<gray>Page %page% of %pages%</gray>",
                "<gray>%used% of %maximum% slots used</gray>", "<#f72a4c>Click to toggle</#f72a4c>"),
            statusPlaceholders), List.of(49), holder.controls);
        menu.place(holder.inventory, "clear-all", menu.item("clear-all", Material.BARRIER,
            "<white>Clear all filters</white>", List.of("<gray>Shift-click to confirm</gray>")),
            List.of(51), holder.controls);
        if (page < lastPage) {
            menu.place(holder.inventory, "next-page", menu.item("next-page", Material.ARROW,
                "<white>Next page</white>", List.of()), List.of(53), holder.controls);
        }
        menu.placeStaticItems(holder.inventory, handled, holder.controls, statusPlaceholders);
        player.openInventory(holder.inventory);
        menu.open(player, statusPlaceholders);
    }

    private static String titleCase(String value) {
        return java.util.Arrays.stream(value.split(" "))
            .map(word -> word.isEmpty() ? word
                : Character.toUpperCase(word.charAt(0)) + word.substring(1))
            .collect(java.util.stream.Collectors.joining(" "));
    }

    private boolean save(Player player) {
        syncData(player.getUniqueId());
        try {
            plugin.saveData("filters");
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/filters.yml: " + exception.getMessage());
            send(player, "<white>Your filter change could not be saved safely.");
            return false;
        }
    }

    private void syncData(UUID player) {
        FilterState state = state(player);
        String path = "players." + player;
        data.set(path + ".enabled", state.enabled());
        data.set(path + ".materials", state.items().stream().map(Material::name).sorted().toList());
    }

    private void load(String value, ConfigurationSection section) {
        if (section == null) {
            return;
        }
        try {
            UUID uuid = UUID.fromString(value);
            filters.put(uuid, readState(section));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Skipped invalid filter owner UUID: " + value);
        }
    }

    private FilterState state(UUID player) {
        return filters.computeIfAbsent(player,
            ignored -> new FilterState(EnumSet.noneOf(Material.class), true));
    }

    private List<Material> sorted(Player player) {
        return state(player.getUniqueId()).items().stream()
            .sorted(Comparator.comparing(Material::name)).toList();
    }

    private boolean blockedWorld(Player player) {
        return plugin.settings("filter").getStringList("blocked-worlds").stream()
            .anyMatch(world -> world.equalsIgnoreCase(player.getWorld().getName()));
    }

    static boolean add(Set<Material> items, Material material, int maximum, boolean bypass) {
        return (bypass || items.size() < maximum) && items.add(material);
    }

    static boolean remove(Set<Material> items, Material material) {
        return items.remove(material);
    }

    static boolean toggle(FilterState state) {
        state.enabled = !state.enabled;
        return state.enabled;
    }

    static boolean enable(FilterState state) {
        boolean changed = !state.enabled;
        state.enabled = true;
        return changed;
    }

    static boolean blocksPickup(boolean enabled, boolean blockedWorld,
                                Set<Material> items, Material material) {
        return enabled && !blockedWorld && items.contains(material);
    }

    static FilterState readState(ConfigurationSection section) {
        Set<Material> items = EnumSet.noneOf(Material.class);
        section.getStringList("materials").stream().map(Material::matchMaterial)
            .filter(java.util.Objects::nonNull).forEach(items::add);
        return new FilterState(items, section.getBoolean("enabled", true));
    }

    static String display(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static void send(Player player, String message) {
        player.sendMessage(MM.deserialize(message));
    }

    static final class FilterState {
        private final Set<Material> items;
        private boolean enabled;

        FilterState(Set<Material> items, boolean enabled) {
            this.items = items;
            this.enabled = enabled;
        }

        Set<Material> items() {
            return items;
        }

        boolean enabled() {
            return enabled;
        }
    }

    private static final class FilterHolder implements InventoryHolder {
        private final UUID owner;
        private final int page;
        private final int pageSize;
        private final Map<Integer, Material> items = new HashMap<>();
        private final Map<Integer, String> controls = new HashMap<>();
        private Inventory inventory;

        private FilterHolder(UUID owner, int page, int pageSize) {
            this.owner = owner;
            this.page = page;
            this.pageSize = pageSize;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
