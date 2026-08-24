package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class PollModule implements Listener {
    private static final int PAGE_SIZE = RivetGui.CONTENT_SLOTS.length;
    private static final int MAX_DESCRIPTION_LENGTH = 240;
    private final RivetPlugin plugin;
    private final YamlConfiguration data;
    private final Map<String, Poll> polls = new LinkedHashMap<>();

    PollModule(RivetPlugin plugin) {
        this.plugin = plugin;
        data = plugin.data("polls");
        ConfigurationSection configured = data.getConfigurationSection("polls");
        if (configured != null) {
            configured.getKeys(false).forEach(id -> load(id, configured.getConfigurationSection(id)));
        }
    }

    boolean command(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("create")) {
            return create(sender, args);
        }
        if (args.length != 0) {
            send(sender, "<white>Usage: /poll or /poll create &lt;name&gt; &lt;description&gt;");
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "<white>This command is only available to players.");
            return true;
        }
        open(player, 0);
        return true;
    }

    List<String> completions(CommandSender sender, String[] args) {
        return args.length == 1 && sender.hasPermission("rivet.poll.create")
            ? List.of("create") : List.of();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long delay = Math.max(0, plugin.settings("polls").getLong("reminder-delay-ticks", 40));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && hasUnvoted(polls.values(), player.getUniqueId())) {
                plugin.messageActions().run(player, plugin.settings("polls"), "messages.reminder",
                    "<white>You haven't voted on a poll yet. "
                        + "<click:run_command:'/poll'><#f72a4c>Click me to open the poll GUI.</#f72a4c></click></white>");
            }
        }, delay);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder rawHolder = event.getView().getTopInventory().getHolder(false);
        if (!(rawHolder instanceof PollHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
            || !player.getUniqueId().equals(holder.owner()) || event.getRawSlot() < 0
            || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        if (holder instanceof PollListHolder list) {
            clickList(player, list, event.getRawSlot());
        } else if (holder instanceof VoteHolder vote) {
            if (event.getRawSlot() == 11) {
                vote(player, vote.pollId, true);
            } else if (event.getRawSlot() == 15) {
                vote(player, vote.pollId, false);
            } else if (event.getRawSlot() == 22) {
                open(player, vote.returnPage);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof PollHolder) {
            event.setCancelled(true);
        }
    }

    private boolean create(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rivet.poll.create")) {
            send(sender, "<white>You do not have permission to create polls.");
            return true;
        }
        if (args.length < 3 || !validName(args[1])) {
            send(sender, "<white>Usage: /poll create &lt;name&gt; &lt;description&gt; "
                + "(name: 1-32 letters, numbers, underscores, or hyphens)</white>");
            return true;
        }
        String description = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).strip();
        if (description.isEmpty() || description.length() > MAX_DESCRIPTION_LENGTH) {
            send(sender, "<white>The poll description must be between 1 and "
                + MAX_DESCRIPTION_LENGTH + " characters.</white>");
            return true;
        }
        if (polls.values().stream().anyMatch(poll -> poll.name.equalsIgnoreCase(args[1]))) {
            send(sender, "<white>A poll named <#f72a4c>" + args[1] + "</#f72a4c> already exists.");
            return true;
        }

        String id = UUID.randomUUID().toString();
        Poll poll = new Poll(id, args[1], description, Instant.now().toEpochMilli(), new HashMap<>());
        polls.put(id, poll);
        sync(poll);
        if (!save(sender, "The poll could not be created safely.")) {
            polls.remove(id);
            data.set("polls." + id, null);
            return true;
        }
        send(sender, "<white>Created poll <#f72a4c>" + args[1]
            + "</#f72a4c>. Players can vote with <#f72a4c>/poll</#f72a4c>.</white>");
        return true;
    }

    private void clickList(Player player, PollListHolder holder, int slot) {
        int contentIndex = RivetGui.contentIndex(slot);
        if (contentIndex >= 0 && contentIndex < holder.pollIds.size()) {
            Poll poll = polls.get(holder.pollIds.get(contentIndex));
            if (poll == null) {
                open(player, holder.page);
            } else if (poll.votes.containsKey(player.getUniqueId())) {
                send(player, "<white>You already voted <#f72a4c>"
                    + (poll.votes.get(player.getUniqueId()) ? "Yes" : "No")
                    + "</#f72a4c> on that poll.</white>");
            } else {
                openVote(player, poll, holder.page);
            }
        } else if (slot == 45 && holder.page > 0) {
            open(player, holder.page - 1);
        } else if (slot == 53 && (holder.page + 1) * PAGE_SIZE < sorted().size()) {
            open(player, holder.page + 1);
        }
    }

    private void vote(Player player, String pollId, boolean choice) {
        Poll poll = polls.get(pollId);
        if (poll == null) {
            send(player, "<white>That poll no longer exists.</white>");
            player.closeInventory();
            return;
        }
        if (poll.votes.putIfAbsent(player.getUniqueId(), choice) != null) {
            send(player, "<white>You have already voted on that poll.</white>");
            open(player, 0);
            return;
        }
        data.set("polls." + poll.id + ".votes." + player.getUniqueId(), choice ? "yes" : "no");
        if (!save(player, "Your vote could not be saved safely.")) {
            poll.votes.remove(player.getUniqueId());
            data.set("polls." + poll.id + ".votes." + player.getUniqueId(), null);
            return;
        }
        send(player, "<white>Your <#f72a4c>" + (choice ? "Yes" : "No")
            + "</#f72a4c> vote was recorded for <#f72a4c>" + poll.name + "</#f72a4c>.</white>");
        open(player, 0);
    }

    private void open(Player player, int requestedPage) {
        List<Poll> all = sorted();
        int lastPage = Math.max(0, (all.size() - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, lastPage));
        List<Poll> shown = all.subList(page * PAGE_SIZE, Math.min(all.size(), (page + 1) * PAGE_SIZE));
        PollListHolder holder = new PollListHolder(player.getUniqueId(), page,
            shown.stream().map(Poll::id).toList());
        holder.inventory = plugin.getServer().createInventory(holder, 54, RivetGui.title("Polls"));
        RivetGui.frame(holder.inventory);

        for (int index = 0; index < shown.size(); index++) {
            Poll poll = shown.get(index);
            Boolean vote = poll.votes.get(player.getUniqueId());
            List<Component> lore = new ArrayList<>();
            wrap(poll.description, 38).forEach(line -> lore.add(Component.text(line, NamedTextColor.GRAY)));
            lore.add(Component.empty());
            lore.add(results(poll));
            lore.add(vote == null
                ? Component.text("Click to vote", RivetPalette.SECONDARY)
                : Component.text("Your vote: " + (vote ? "Yes" : "No"), RivetPalette.SECONDARY));
            Material icon = vote == null ? Material.WRITABLE_BOOK
                : vote ? Material.LIME_DYE : Material.RED_DYE;
            holder.inventory.setItem(RivetGui.CONTENT_SLOTS[index],
                RivetGui.item(icon, Component.text(poll.name, NamedTextColor.WHITE), lore));
        }
        if (all.isEmpty()) {
            holder.inventory.setItem(22, RivetGui.button(Material.PAPER, "No polls yet",
                "An administrator can create one with /poll create"));
        }
        if (page > 0) {
            holder.inventory.setItem(45, RivetGui.button(Material.ARROW, "Previous page"));
        }
        holder.inventory.setItem(49, RivetGui.button(Material.CLOCK, "Polls",
            "Page " + (page + 1) + " of " + (lastPage + 1),
            unvotedCount(all, player.getUniqueId()) + " awaiting your vote"));
        if (page < lastPage) {
            holder.inventory.setItem(53, RivetGui.button(Material.ARROW, "Next page"));
        }
        player.openInventory(holder.inventory);
    }

    private void openVote(Player player, Poll poll, int returnPage) {
        VoteHolder holder = new VoteHolder(player.getUniqueId(), poll.id, returnPage);
        holder.inventory = plugin.getServer().createInventory(holder, 27, RivetGui.title("Vote"));
        for (int slot = 0; slot < holder.inventory.getSize(); slot++) {
            holder.inventory.setItem(slot, RivetGui.pane(Material.GRAY_STAINED_GLASS_PANE));
        }
        holder.inventory.setItem(11, RivetGui.button(Material.LIME_CONCRETE, "Yes",
            "Click to vote Yes"));
        List<Component> lore = new ArrayList<>();
        wrap(poll.description, 38).forEach(line -> lore.add(Component.text(line, NamedTextColor.GRAY)));
        lore.add(Component.empty());
        lore.add(results(poll));
        holder.inventory.setItem(13, RivetGui.item(Material.PAPER,
            Component.text(poll.name, NamedTextColor.WHITE), lore));
        holder.inventory.setItem(15, RivetGui.button(Material.RED_CONCRETE, "No",
            "Click to vote No"));
        holder.inventory.setItem(22, RivetGui.button(Material.ARROW, "Back to polls"));
        player.openInventory(holder.inventory);
    }

    private List<Poll> sorted() {
        return polls.values().stream().sorted(Comparator.comparingLong(Poll::createdAt).reversed())
            .toList();
    }

    private void load(String id, ConfigurationSection section) {
        if (section == null) {
            return;
        }
        String name = section.getString("name", "");
        String description = section.getString("description", "");
        if (!validName(name) || description.isBlank() || description.length() > MAX_DESCRIPTION_LENGTH) {
            plugin.getLogger().warning("Skipped invalid poll data for '" + id + "'.");
            return;
        }
        Map<UUID, Boolean> votes = new HashMap<>();
        ConfigurationSection configuredVotes = section.getConfigurationSection("votes");
        if (configuredVotes != null) {
            configuredVotes.getKeys(false).forEach(value -> loadVote(id, value,
                configuredVotes.getString(value), votes));
        }
        polls.put(id, new Poll(id, name, description, section.getLong("created-at", 0), votes));
    }

    private void loadVote(String pollId, String value, String choice, Map<UUID, Boolean> votes) {
        try {
            UUID player = UUID.fromString(value);
            if ("yes".equalsIgnoreCase(choice)) {
                votes.put(player, true);
            } else if ("no".equalsIgnoreCase(choice)) {
                votes.put(player, false);
            } else {
                plugin.getLogger().warning("Skipped invalid vote in poll '" + pollId + "'.");
            }
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Skipped invalid voter UUID in poll '" + pollId + "'.");
        }
    }

    private void sync(Poll poll) {
        String path = "polls." + poll.id;
        data.set(path + ".name", poll.name);
        data.set(path + ".description", poll.description);
        data.set(path + ".created-at", poll.createdAt);
        poll.votes.forEach((player, choice) ->
            data.set(path + ".votes." + player, choice ? "yes" : "no"));
    }

    private boolean save(CommandSender sender, String failure) {
        try {
            plugin.saveData("polls");
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/polls.yml: " + exception.getMessage());
            send(sender, "<white>" + failure + "</white>");
            return false;
        }
    }

    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,32}");
    }

    static boolean hasUnvoted(Iterable<Poll> polls, UUID player) {
        for (Poll poll : polls) {
            if (!poll.votes.containsKey(player)) {
                return true;
            }
        }
        return false;
    }

    static int unvotedCount(Iterable<Poll> polls, UUID player) {
        int count = 0;
        for (Poll poll : polls) {
            if (!poll.votes.containsKey(player)) {
                count++;
            }
        }
        return count;
    }

    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.strip().split("\\s+")) {
            if (!line.isEmpty() && line.length() + word.length() + 1 > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    String placeholder(String params) {
        return pollVotePlaceholder(polls.values(), params);
    }

    static String pollVotePlaceholder(Iterable<Poll> polls, String params) {
        if (params == null) {
            return null;
        }
        String lowered = params.toLowerCase(Locale.ROOT);
        boolean yes;
        String suffix;
        if (lowered.startsWith("poll_") && lowered.endsWith("_yes")) {
            yes = true;
            suffix = "_yes";
        } else if (lowered.startsWith("poll_") && lowered.endsWith("_no")) {
            yes = false;
            suffix = "_no";
        } else {
            return null;
        }
        String name = params.substring("poll_".length(), params.length() - suffix.length());
        for (Poll poll : polls) {
            if (poll.name.equalsIgnoreCase(name)) {
                return Long.toString(poll.votes.values().stream()
                    .filter(choice -> choice == yes).count());
            }
        }
        return null;
    }

    private Component results(Poll poll) {
        String format = plugin.settings("polls").getString("result-format",
            "<dark_gray>Yes: %yes%  •  No: %no%</dark_gray>");
        return RivetMiniMessage.miniMessage().deserialize(format, resultTags(poll));
    }

    private static TagResolver resultTags(Poll poll) {
        long yes = poll.votes.values().stream().filter(Boolean::booleanValue).count();
        return TagResolver.resolver(
            Placeholder.unparsed("yes", Long.toString(yes)),
            Placeholder.unparsed("no", Long.toString(poll.votes.size() - yes)));
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(RivetMiniMessage.miniMessage().deserialize(message));
    }

    static final class Poll {
        private final String id;
        private final String name;
        private final String description;
        private final long createdAt;
        private final Map<UUID, Boolean> votes;

        Poll(String id, String name, String description, long createdAt, Map<UUID, Boolean> votes) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.createdAt = createdAt;
            this.votes = votes;
        }

        String id() {
            return id;
        }

        long createdAt() {
            return createdAt;
        }

        Map<UUID, Boolean> votes() {
            return votes;
        }
    }

    private interface PollHolder extends InventoryHolder {
        UUID owner();
    }

    private static final class PollListHolder implements PollHolder {
        private final UUID owner;
        private final int page;
        private final List<String> pollIds;
        private Inventory inventory;

        private PollListHolder(UUID owner, int page, List<String> pollIds) {
            this.owner = owner;
            this.page = page;
            this.pollIds = pollIds;
        }

        @Override
        public UUID owner() {
            return owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class VoteHolder implements PollHolder {
        private final UUID owner;
        private final String pollId;
        private final int returnPage;
        private Inventory inventory;

        private VoteHolder(UUID owner, String pollId, int returnPage) {
            this.owner = owner;
            this.pollId = pollId;
            this.returnPage = returnPage;
        }

        @Override
        public UUID owner() {
            return owner;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
