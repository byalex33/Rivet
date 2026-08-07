package dev.rivet;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
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
import org.bukkit.inventory.meta.ItemMeta;

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
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PAGE_SIZE = 45;
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
            player.sendActionBar(MM.deserialize(settings.getString("messages.blocked-pickup",
                    "<yellow>Pickup blocked: <white><item></white>"),
                Placeholder.unparsed("item", display(event.getItem().getItemStack().getType()))));
        }
        String configuredSound = settings.getString("feedback.sound", "");
        NamespacedKey key = configuredSound == null || configuredSound.isBlank() ? null
            : NamespacedKey.fromString(configuredSound.toLowerCase(Locale.ROOT));
        Sound sound = key == null ? null : Registry.SOUNDS.get(key);
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 0.6f, 0.8f);
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
        if (slot < holder.items.size()) {
            Material material = holder.items.get(slot);
            if (remove(state(player.getUniqueId()).items(), material)) {
                if (save(player)) {
                    send(player, "<green>Removed <white>" + display(material) + "</white> from your filter.");
                } else {
                    state(player.getUniqueId()).items().add(material);
                    syncData(player.getUniqueId());
                }
            }
            open(player, holder.page);
        } else if (slot == 45 && holder.page > 0) {
            open(player, holder.page - 1);
        } else if (slot == 47) {
            addHeld(player);
            open(player, holder.page);
        } else if (slot == 49) {
            toggle(player, new String[]{"toggle"});
            open(player, holder.page);
        } else if (slot == 51) {
            if (event.isShiftClick()) {
                clear(player, new String[]{"clear"});
                open(player, 0);
            } else {
                send(player, "<yellow>Shift-click the clear button to confirm.");
            }
        } else if (slot == 53 && (holder.page + 1) * PAGE_SIZE < sorted(player).size()) {
            open(player, holder.page + 1);
        }
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
            send(player, "<red>Usage: /filter add [item]");
            return true;
        }
        Material material = Material.matchMaterial(args[1]);
        if (material == null || material.isAir() || !material.isItem()) {
            send(player, "<red>That is not a valid item material.");
            return true;
        }
        add(player, material);
        return true;
    }

    private void addHeld(Player player) {
        Material material = player.getInventory().getItemInMainHand().getType();
        if (material.isAir()) {
            send(player, "<red>Hold an item or use <white>/filter add &lt;item&gt;</white>.");
            return;
        }
        add(player, material);
    }

    private void add(Player player, Material material) {
        FilterState state = state(player.getUniqueId());
        int maximum = Math.max(1, plugin.settings("filter").getInt("maximum-size", 50));
        if (state.items().contains(material)) {
            send(player, "<yellow>That material is already filtered.");
            return;
        }
        if (!add(state.items(), material, maximum, player.hasPermission("rivet.filter.admin"))) {
            send(player, "<red>Your filter is full (<white>" + maximum + "</white> materials).");
            return;
        }
        if (save(player)) {
            send(player, "<green>Added <white>" + display(material) + "</white> to your filter.");
        } else {
            state.items().remove(material);
            syncData(player.getUniqueId());
        }
    }

    private boolean openCommand(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<red>Usage: /filter remove");
            return true;
        }
        open(player, 0);
        return true;
    }

    private boolean list(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<red>Usage: /filter list");
            return true;
        }
        List<Material> materials = sorted(player);
        player.sendMessage(MM.deserialize(plugin.settings("filter").getString("messages.list",
                "<gold>Filtered items:</gold> <white><items></white>"),
            Placeholder.unparsed("items", materials.isEmpty() ? "none"
                : String.join(", ", materials.stream().map(FilterModule::display).toList()))));
        return true;
    }

    private boolean clear(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<red>Usage: /filter clear");
            return true;
        }
        FilterState state = state(player.getUniqueId());
        if (state.items().isEmpty()) {
            send(player, "<gray>Your filter is already empty.");
            return true;
        }
        Set<Material> old = EnumSet.copyOf(state.items());
        state.items().clear();
        if (!save(player)) {
            state.items().addAll(old);
            syncData(player.getUniqueId());
            return true;
        }
        send(player, "<green>Your item filter was cleared.");
        return true;
    }

    private boolean toggle(Player player, String[] args) {
        if (args.length != 1) {
            send(player, "<red>Usage: /filter toggle");
            return true;
        }
        FilterState state = state(player.getUniqueId());
        toggle(state);
        if (!save(player)) {
            toggle(state);
            syncData(player.getUniqueId());
            return true;
        }
        send(player, state.enabled ? "<green>Item filtering enabled.</green>"
            : "<yellow>Item filtering disabled.</yellow>");
        return true;
    }

    private boolean help(Player player, String[] args) {
        send(player, "<gold>/filter</gold> <gray>GUI</gray>  <gold>add [item]</gold>  <gold>remove</gold>  "
            + "<gold>list</gold>  <gold>clear</gold>  <gold>toggle</gold>");
        return true;
    }

    private void open(Player player, int requestedPage) {
        List<Material> all = sorted(player);
        int lastPage = Math.max(0, (all.size() - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, lastPage));
        List<Material> pageItems = new ArrayList<>(all.subList(page * PAGE_SIZE,
            Math.min(all.size(), (page + 1) * PAGE_SIZE)));
        FilterHolder holder = new FilterHolder(player.getUniqueId(), page, pageItems);
        holder.inventory = plugin.getServer().createInventory(holder, 54,
            MM.deserialize(plugin.settings("filter").getString("gui.title", "<dark_green>Item Filter")));
        for (int slot = 0; slot < pageItems.size(); slot++) {
            ItemStack icon = new ItemStack(pageItems.get(slot));
            icon.editMeta(meta -> meta.lore(List.of(MM.deserialize("<red>Click to remove"))));
            holder.inventory.setItem(slot, icon);
        }
        holder.inventory.setItem(45, button(Material.ARROW, "<yellow>Previous page"));
        holder.inventory.setItem(47, button(Material.HOPPER, "<green>Add held item"));
        holder.inventory.setItem(49, button(state(player.getUniqueId()).enabled()
            ? Material.LIME_DYE : Material.GRAY_DYE, state(player.getUniqueId()).enabled()
            ? "<green>Filtering enabled" : "<yellow>Filtering disabled"));
        holder.inventory.setItem(51, button(Material.BARRIER, "<red>Shift-click to clear"));
        holder.inventory.setItem(53, button(Material.ARROW, "<yellow>Next page"));
        player.openInventory(holder.inventory);
    }

    private static ItemStack button(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MM.deserialize(name));
        item.setItemMeta(meta);
        return item;
    }

    private boolean save(Player player) {
        syncData(player.getUniqueId());
        try {
            plugin.saveData("filters");
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/filters.yml: " + exception.getMessage());
            send(player, "<red>Your filter change could not be saved safely.");
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
        private final List<Material> items;
        private Inventory inventory;

        private FilterHolder(UUID owner, int page, List<Material> items) {
            this.owner = owner;
            this.page = page;
            this.items = items;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
