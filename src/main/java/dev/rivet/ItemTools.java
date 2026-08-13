package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ItemTools {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private static final MiniMessage FORMATTED = RivetMiniMessage.builder().tags(TagResolver.resolver(
        StandardTags.color(), StandardTags.decorations())).build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Map<Material, Compression> COMPRESSIONS = Map.ofEntries(
        Map.entry(Material.IRON_INGOT, new Compression(Material.IRON_BLOCK, 9)),
        Map.entry(Material.GOLD_INGOT, new Compression(Material.GOLD_BLOCK, 9)),
        Map.entry(Material.COPPER_INGOT, new Compression(Material.COPPER_BLOCK, 9)),
        Map.entry(Material.DIAMOND, new Compression(Material.DIAMOND_BLOCK, 9)),
        Map.entry(Material.EMERALD, new Compression(Material.EMERALD_BLOCK, 9)),
        Map.entry(Material.COAL, new Compression(Material.COAL_BLOCK, 9)),
        Map.entry(Material.REDSTONE, new Compression(Material.REDSTONE_BLOCK, 9)),
        Map.entry(Material.LAPIS_LAZULI, new Compression(Material.LAPIS_BLOCK, 9)),
        Map.entry(Material.RAW_IRON, new Compression(Material.RAW_IRON_BLOCK, 9)),
        Map.entry(Material.RAW_GOLD, new Compression(Material.RAW_GOLD_BLOCK, 9)),
        Map.entry(Material.RAW_COPPER, new Compression(Material.RAW_COPPER_BLOCK, 9)),
        Map.entry(Material.WHEAT, new Compression(Material.HAY_BLOCK, 9)),
        Map.entry(Material.DRIED_KELP, new Compression(Material.DRIED_KELP_BLOCK, 9)),
        Map.entry(Material.SLIME_BALL, new Compression(Material.SLIME_BLOCK, 9)),
        Map.entry(Material.BONE_MEAL, new Compression(Material.BONE_BLOCK, 9)));
    private final RivetPlugin plugin;
    private final YamlConfiguration settings;

    ItemTools(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("inventory");
    }

    boolean hat(Player player, String[] args) {
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<white>Usage: /hat"));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            message(player, "empty-hand", "<white>Hold an item first.</white>");
            return true;
        }
        ItemStack helmet = player.getInventory().getHelmet();
        player.getInventory().setHelmet(held);
        player.getInventory().setItemInMainHand(helmet == null ? new ItemStack(Material.AIR) : helmet);
        message(player, "hat-success", "<white>Equipped the item as your hat.</white>");
        return true;
    }

    boolean clear(Player actor, String[] args) {
        List<String> values = Arrays.stream(args).filter(value -> !value.equalsIgnoreCase("-s")).toList();
        boolean silent = values.size() != args.length;
        if (values.size() > 2) {
            actor.sendMessage(MM.deserialize("<white>Usage: /clear [player] [item[:amount][;plain]] [-s]"));
            return true;
        }
        Player target = actor;
        String item = null;
        if (values.size() == 1) {
            Player possible = plugin.getServer().getPlayerExact(values.get(0));
            if (possible != null && actor.canSee(possible)) {
                if (!actor.hasPermission("rivet.inventory.clear.others")) {
                    actor.sendMessage(MM.deserialize("<white>You cannot clear another player's inventory."));
                    return true;
                }
                target = possible;
            } else {
                item = values.get(0);
            }
        } else if (values.size() == 2) {
            if (!actor.hasPermission("rivet.inventory.clear.others")) {
                actor.sendMessage(MM.deserialize("<white>You cannot clear another player's inventory."));
                return true;
            }
            target = plugin.getServer().getPlayerExact(values.get(0));
            if (target == null || !actor.canSee(target)) {
                actor.sendMessage(MM.deserialize("<white>That player is not online.</white>"));
                return true;
            }
            item = values.get(1);
        }
        int removed;
        ClearItem parsed = null;
        if (item == null) {
            removed = Arrays.stream(target.getInventory().getContents())
                .filter(java.util.Objects::nonNull).mapToInt(ItemStack::getAmount).sum();
            target.getInventory().clear();
        } else {
            parsed = parseClearItem(item);
            if (parsed == null) {
                actor.sendMessage(MM.deserialize("<white>Use a valid item, optional positive amount, and optional <#f72a4c>;plain</#f72a4c>."));
                return true;
            }
            removed = remove(target, parsed);
        }
        if (!silent) {
            String material = parsed == null ? "items" : parsed.material().name().toLowerCase(Locale.ROOT);
            actor.sendMessage(MM.deserialize("<white>Removed <#f72a4c>%count% %item%</#f72a4c> from <#f72a4c>%player%</#f72a4c>.",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("count", Integer.toString(removed)),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("item", material),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", target.getName())));
        }
        return true;
    }

    boolean condense(Player player, String[] args) {
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<white>Usage: /condense"));
            return true;
        }
        ItemStack[] contents = Arrays.stream(player.getInventory().getStorageContents())
            .map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
        Map<Material, Integer> made = new LinkedHashMap<>();
        COMPRESSIONS.forEach((input, compression) -> {
            int available = Arrays.stream(contents).filter(java.util.Objects::nonNull)
                .filter(item -> item.getType() == input && plain(item)).mapToInt(ItemStack::getAmount).sum();
            int conversions = condensedAmount(available, compression.ratio());
            for (int count = conversions; count > 0; count--) {
                ItemStack[] candidate = Arrays.stream(contents)
                    .map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new);
                consume(candidate, input, count * compression.ratio());
                if (add(candidate, new ItemStack(compression.output(), count))) {
                    System.arraycopy(candidate, 0, contents, 0, contents.length);
                    made.put(compression.output(), count);
                    break;
                }
            }
        });
        if (made.isEmpty()) {
            player.sendMessage(MM.deserialize("<white>Nothing in your inventory can be condensed."));
            return true;
        }
        player.getInventory().setStorageContents(contents);
        String summary = String.join(", ", made.entrySet().stream()
            .map(entry -> entry.getValue() + " " + entry.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' '))
            .toList());
        player.sendMessage(MM.deserialize("<white>Condensed: <#f72a4c>%items%</#f72a4c>.",
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("items", summary)));
        return true;
    }

    boolean donate(Player sender, String[] args) {
        if (args.length < 1 || args.length > 2) {
            sender.sendMessage(MM.deserialize("<white>Usage: /donate &lt;player&gt; [amount]"));
            return true;
        }
        Player receiver = plugin.getServer().getPlayerExact(args[0]);
        if (receiver == null || !sender.canSee(receiver)) {
            sender.sendMessage(MM.deserialize("<white>That player is not online.</white>"));
            return true;
        }
        if (receiver.equals(sender)) {
            sender.sendMessage(MM.deserialize("<white>You cannot donate to yourself.</white>"));
            return true;
        }
        ItemStack held = sender.getInventory().getItemInMainHand();
        if (held.getType().isAir()) {
            sender.sendMessage(MM.deserialize("<white>Hold the item you want to donate.</white>"));
            return true;
        }
        int amount = args.length == 1 ? held.getAmount() : RivetPlugin.itemAmount(args[1]);
        if (!validDonateAmount(amount, held.getAmount())) {
            sender.sendMessage(MM.deserialize("<white>Amount must be between 1 and the held stack size.</white>"));
            return true;
        }
        ItemStack donated = held.clone();
        donated.setAmount(amount);
        receiver.getInventory().addItem(donated).values()
            .forEach(leftover -> receiver.getWorld().dropItemNaturally(receiver.getLocation(), leftover));
        if (amount == held.getAmount()) {
            sender.getInventory().setItemInMainHand(null);
        } else {
            held.setAmount(held.getAmount() - amount);
        }
        sender.sendMessage(MM.deserialize("<white>Donated <#f72a4c>%amount% %item%</#f72a4c> to <#f72a4c>%player%</#f72a4c>.",
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("amount", Integer.toString(amount)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("item", held.getType().name().toLowerCase(Locale.ROOT)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", receiver.getName())));
        receiver.sendMessage(MM.deserialize("<white>Received <#f72a4c>%amount% %item%</#f72a4c> from <#f72a4c>%player%</#f72a4c>.",
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("amount", Integer.toString(amount)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("item", donated.getType().name().toLowerCase(Locale.ROOT)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", sender.getName())));
        return true;
    }

    boolean giveAll(Player actor, String[] args) {
        if (args.length < 1 || args.length > 2) {
            actor.sendMessage(MM.deserialize("<white>Usage: /giveall &lt;item&gt; [amount]"));
            return true;
        }
        Material material = Material.matchMaterial(args[0]);
        int amount = args.length == 1 ? 1 : RivetPlugin.itemAmount(args[1]);
        if (material == null || material.isAir() || !material.isItem() || amount < 1) {
            actor.sendMessage(MM.deserialize("<white>Use a valid item and positive whole-number amount.</white>"));
            return true;
        }
        plugin.getServer().getOnlinePlayers().forEach(player ->
            player.getInventory().addItem(new ItemStack(material, amount)).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover)));
        actor.sendMessage(MM.deserialize("<white>Gave <#f72a4c>%amount% %item%</#f72a4c> to <#f72a4c>%count%</#f72a4c> player(s).",
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("amount", Integer.toString(amount)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("item", material.name().toLowerCase(Locale.ROOT)),
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("count",
                Integer.toString(plugin.getServer().getOnlinePlayers().size()))));
        return true;
    }

    List<String> completions(Player player, String command, String[] args) {
        if (command.equals("donate")) {
            return args.length == 1 ? visiblePlayers(player) : args.length == 2
                ? List.of("1", Integer.toString(Math.max(1,
                    player.getInventory().getItemInMainHand().getAmount()))) : List.of();
        }
        if (command.equals("clear")) {
            if (args.length == 1) {
                List<String> choices = new ArrayList<>(materials());
                if (player.hasPermission("rivet.inventory.clear.others")) {
                    choices.addAll(visiblePlayers(player));
                }
                choices.add("-s");
                return choices;
            }
            return args.length == 2 ? materials() : args.length == 3 ? List.of("-s") : List.of();
        }
        if (command.equals("giveall")) {
            return args.length == 1 ? materials() : args.length == 2 ? List.of("1", "16", "64") : List.of();
        }
        return List.of();
    }

    boolean repair(Player player, String[] args) {
        if (args.length == 0) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (!repair(item)) {
                message(player, "repair-invalid", "<white>Hold a damaged repairable item.");
                return true;
            }
            message(player, "repair-success", "<white>Repaired the item in your hand.");
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("all")
            || !player.hasPermission("rivet.repair.all")) {
            player.sendMessage(MM.deserialize("<white>Usage: /repair [all]"));
            return true;
        }
        int repaired = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (repair(item)) {
                repaired++;
            }
        }
        plugin.messageActions().run(player, settings, "messages.repair-all",
            "<white>Repaired <#f72a4c>%count%</#f72a4c> items.",
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                "count", Integer.toString(repaired)));
        return true;
    }

    boolean rename(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(MM.deserialize("<white>Usage: /rename <name|clear>"));
            return true;
        }
        ItemStack item = held(player);
        if (item == null) {
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            item.editMeta(meta -> meta.displayName(null));
            message(player, "rename-cleared", "<white>Item name cleared.");
            return true;
        }
        String input = String.join(" ", args);
        Component name = render(player, input, "rivet.rename.format");
        int maximum = Math.max(1, settings.getInt("item-editing.maximum-name-length", 50));
        if (name == null || !validText(PLAIN.serialize(name), maximum)) {
            invalid(player, maximum);
            return true;
        }
        item.editMeta(meta -> meta.displayName(name));
        message(player, "rename-success", "<white>Item renamed.");
        return true;
    }

    boolean lore(Player player, String[] args) {
        if (args.length == 0) {
            usage(player);
            return true;
        }
        ItemStack item = held(player);
        if (item == null) {
            return true;
        }
        List<Component> lore = item.lore() == null ? new ArrayList<>()
            : new ArrayList<>(item.lore());
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("clear") && args.length == 1) {
            item.lore(null);
            message(player, "lore-cleared", "<white>Item lore cleared.");
            return true;
        }
        if (action.equals("remove") && args.length == 2) {
            int line = positiveInt(args[1]);
            if (line < 1 || line > lore.size()) {
                message(player, "lore-line-invalid", "<white>That lore line does not exist.");
                return true;
            }
            lore.remove(line - 1);
        } else if ((action.equals("add") && args.length >= 2)
            || (action.equals("set") && args.length >= 3)) {
            int line = action.equals("set") ? positiveInt(args[1]) : lore.size() + 1;
            String input = String.join(" ", Arrays.copyOfRange(args, action.equals("set") ? 2 : 1,
                args.length));
            Component value = render(player, input, "rivet.lore.format");
            int maximumLength = Math.max(1,
                settings.getInt("item-editing.maximum-lore-line-length", 100));
            int maximumLines = Math.max(1, settings.getInt("item-editing.maximum-lore-lines", 10));
            if (value == null || !validText(PLAIN.serialize(value), maximumLength)
                || line < 1 || line > lore.size() + (action.equals("add") ? 1 : 0)
                || action.equals("add") && lore.size() >= maximumLines) {
                invalid(player, maximumLength);
                return true;
            }
            if (action.equals("add")) {
                lore.add(value);
            } else if (line <= lore.size()) {
                lore.set(line - 1, value);
            } else {
                message(player, "lore-line-invalid", "<white>That lore line does not exist.");
                return true;
            }
        } else {
            usage(player);
            return true;
        }
        item.lore(lore.isEmpty() ? null : lore);
        message(player, "lore-success", "<white>Item lore updated.");
        return true;
    }

    private ItemStack held(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            message(player, "empty-hand", "<white>Hold an item first.");
            return null;
        }
        if (settings.getStringList("item-editing.blocked-materials").stream()
            .anyMatch(material -> material.equalsIgnoreCase(item.getType().name()))) {
            message(player, "blocked-item", "<white>That material cannot be edited.");
            return null;
        }
        return item;
    }

    private Component render(Player player, String input, String permission) {
        try {
            return player.hasPermission(permission) ? FORMATTED.deserialize(input) : Component.text(input);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean repair(ItemStack item) {
        if (item == null || !(item.getItemMeta() instanceof Damageable damageable)
            || !repairEligible(true, damageable.getDamage())) {
            return false;
        }
        damageable.setDamage(0);
        item.setItemMeta(damageable);
        return true;
    }

    private void invalid(Player player, int maximum) {
        plugin.messageActions().run(player, settings, "messages.invalid-text",
            "<white>Text must be 1-%max% visible characters without control characters.",
            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed(
                "max", Integer.toString(maximum)));
    }

    private static int positiveInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private void usage(Player player) {
        player.sendMessage(MM.deserialize("<white>Usage: /lore <add|set|remove|clear> ..."));
    }

    private void message(Player player, String key, String fallback) {
        plugin.messageActions().run(player, settings, "messages." + key, fallback);
    }

    static ClearItem parseClearItem(String input) {
        String[] data = input.split(";", -1);
        if (data.length > 2 || data.length == 2 && !data[1].equalsIgnoreCase("plain")) {
            return null;
        }
        String materialPart = data[0];
        int amount = Integer.MAX_VALUE;
        Material material = Material.matchMaterial(materialPart);
        if (material == null) {
            int separator = materialPart.lastIndexOf(':');
            if (separator < 1) {
                return null;
            }
            material = Material.matchMaterial(materialPart.substring(0, separator));
            amount = RivetPlugin.itemAmount(materialPart.substring(separator + 1));
        }
        return material == null || material == Material.AIR || material == Material.CAVE_AIR
            || material == Material.VOID_AIR || amount < 1
            ? null : new ClearItem(material, amount, data.length == 2);
    }

    static int condensedAmount(int itemCount, int ratio) {
        return itemCount < 0 || ratio < 1 ? 0 : itemCount / ratio;
    }

    static boolean validDonateAmount(int requested, int available) {
        return requested > 0 && requested <= available;
    }

    private static int remove(Player player, ClearItem request) {
        int remaining = request.amount();
        int removed = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != request.material()
                || request.plainOnly() && !plain(item)) {
                continue;
            }
            int count = Math.min(remaining, item.getAmount());
            removed += count;
            remaining -= count;
            if (count == item.getAmount()) {
                player.getInventory().setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - count);
                player.getInventory().setItem(slot, item);
            }
        }
        return removed;
    }

    private static boolean plain(ItemStack item) {
        return condensable(item.hasItemMeta());
    }

    static boolean condensable(boolean hasItemMeta) {
        return !hasItemMeta;
    }

    private static void consume(ItemStack[] contents, Material material, int amount) {
        for (int slot = 0; slot < contents.length && amount > 0; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != material || !plain(item)) {
                continue;
            }
            int count = Math.min(amount, item.getAmount());
            amount -= count;
            if (count == item.getAmount()) {
                contents[slot] = null;
            } else {
                item.setAmount(item.getAmount() - count);
            }
        }
    }

    private static boolean add(ItemStack[] contents, ItemStack added) {
        int remaining = added.getAmount();
        for (ItemStack item : contents) {
            if (item != null && item.isSimilar(added) && item.getAmount() < item.getMaxStackSize()) {
                int count = Math.min(remaining, item.getMaxStackSize() - item.getAmount());
                item.setAmount(item.getAmount() + count);
                remaining -= count;
            }
        }
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            if (contents[slot] == null) {
                ItemStack stack = added.clone();
                stack.setAmount(Math.min(remaining, stack.getMaxStackSize()));
                contents[slot] = stack;
                remaining -= stack.getAmount();
            }
        }
        return remaining == 0;
    }

    private List<String> visiblePlayers(Player viewer) {
        return plugin.getServer().getOnlinePlayers().stream()
            .filter(player -> player != viewer && viewer.canSee(player)).map(Player::getName)
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static List<String> materials() {
        return Arrays.stream(Material.values()).filter(material -> material.isItem() && !material.isAir())
            .map(material -> material.name().toLowerCase(Locale.ROOT)).sorted().toList();
    }

    record ClearItem(Material material, int amount, boolean plainOnly) {
    }

    private record Compression(Material output, int ratio) {
    }

    static boolean repairEligible(boolean damageable, int damage) {
        return damageable && damage > 0;
    }

    static boolean validText(String text, int maximum) {
        return !text.isBlank() && text.length() <= maximum
            && text.chars().noneMatch(Character::isISOControl);
    }
}
