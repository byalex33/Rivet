package dev.rivet;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class GlowModule implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String RAINBOW = "rainbow";
    private static final int RAINBOW_CYCLE_TICKS = 120;
    private static final Map<String, NamedTextColor> COLORS = colors();
    private static final List<NamedTextColor> RAINBOW_COLORS = List.of(
        NamedTextColor.DARK_RED, NamedTextColor.RED, NamedTextColor.GOLD,
        NamedTextColor.YELLOW, NamedTextColor.GREEN, NamedTextColor.DARK_GREEN,
        NamedTextColor.DARK_AQUA, NamedTextColor.AQUA, NamedTextColor.BLUE,
        NamedTextColor.DARK_BLUE, NamedTextColor.DARK_PURPLE, NamedTextColor.LIGHT_PURPLE);

    private final RivetPlugin plugin;
    private final NamespacedKey legacyOutlineKey;
    private final YamlConfiguration data;
    private final Map<UUID, Assignment> assignments = new LinkedHashMap<>();
    private final BukkitTask animationTask;
    private int animationTick;

    GlowModule(RivetPlugin plugin) {
        this.plugin = plugin;
        legacyOutlineKey = new NamespacedKey(plugin, "glow_outline");
        data = plugin.data("glow");
        load();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.getServer().getWorlds().forEach(world ->
                removeLegacyOutlines(world.getEntities().toArray(Entity[]::new)));
            plugin.getServer().getOnlinePlayers().forEach(this::apply);
        });
        animationTask = plugin.getServer().getScheduler().runTaskTimer(
            plugin, this::animate, 1, 1);
    }

    boolean command(CommandSender sender, String[] args) {
        List<String> values = Arrays.stream(args)
            .filter(value -> !value.equalsIgnoreCase("-s")).toList();
        boolean silent = values.size() != args.length;
        if (values.size() == 1 && (values.get(0).equalsIgnoreCase("color")
            || values.get(0).equalsIgnoreCase("colors"))) {
            showColors(sender);
            return true;
        }
        if (values.size() == 3 && values.get(0).equalsIgnoreCase("add")) {
            return add(sender, values.get(1), values.get(2), silent);
        }
        if (values.size() == 2 && values.get(0).equalsIgnoreCase("remove")) {
            return remove(sender, values.get(1), silent);
        }
        send(sender, "<red>Usage: /glow add <player> <color> [-s] | /glow remove <player> [-s] | /glow color");
        return true;
    }

    List<String> completions(String[] args) {
        if (args.length == 1) {
            return List.of("add", "remove", "color");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            return onlinePlayerNames();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            return assignments.values().stream().map(Assignment::name)
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            List<String> values = new ArrayList<>(COLORS.keySet());
            values.add(RAINBOW);
            return values;
        }
        if ((args.length == 4 && args[0].equalsIgnoreCase("add"))
            || (args.length == 3 && args[0].equalsIgnoreCase("remove"))) {
            return List.of("-s");
        }
        return List.of();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Assignment assignment = assignments.get(player.getUniqueId());
        if (assignment == null) {
            return;
        }
        if (!assignment.name.equals(player.getName())) {
            removeFromGlowTeams(assignment.name);
            assignment.name = player.getName();
            save(null);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> apply(player));
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        removeLegacyOutlines(event.getChunk().getEntities());
    }

    void shutdown() {
        animationTask.cancel();
        plugin.getServer().getOnlinePlayers().forEach(player -> {
            Assignment assignment = assignments.get(player.getUniqueId());
            if (assignment != null) {
                clear(player, assignment);
            }
        });
    }

    private boolean add(CommandSender sender, String playerName, String requestedColor,
                        boolean silent) {
        Player target = plugin.getServer().getPlayerExact(playerName);
        if (target == null || sender instanceof Player viewer && !viewer.canSee(target)) {
            send(sender, "<red>That player is not online.");
            return true;
        }
        String color = requestedColor.toLowerCase(Locale.ROOT);
        if (!color.equals(RAINBOW) && !COLORS.containsKey(color)) {
            send(sender, "<red>Unknown glow color. Use <white>/glow color</white> to see the list.");
            return true;
        }

        Assignment previous = assignments.get(target.getUniqueId());
        Team currentTeam = mainScoreboard().getEntityTeam(target);
        String previousTeam = previous == null && currentTeam != null && !isGlowTeam(currentTeam)
            ? currentTeam.getName() : previous == null ? null : previous.previousTeam;
        Assignment assignment = new Assignment(target.getName(), color, previousTeam);
        assignments.put(target.getUniqueId(), assignment);
        if (!save(sender)) {
            if (previous == null) {
                assignments.remove(target.getUniqueId());
            } else {
                assignments.put(target.getUniqueId(), previous);
            }
            return true;
        }
        apply(target);
        if (!silent) {
            send(sender, "<green>Set <white>" + target.getName() + "</white>'s glow to <white>"
                + color.replace('_', ' ') + "</white>.");
        }
        return true;
    }

    private boolean remove(CommandSender sender, String playerName, boolean silent) {
        Map.Entry<UUID, Assignment> found = assignments.entrySet().stream()
            .filter(entry -> entry.getValue().name.equalsIgnoreCase(playerName)).findFirst().orElse(null);
        if (found == null) {
            send(sender, "<red>That player does not have a Rivet glow.");
            return true;
        }
        assignments.remove(found.getKey());
        if (!save(sender)) {
            assignments.put(found.getKey(), found.getValue());
            return true;
        }
        Player target = plugin.getServer().getPlayer(found.getKey());
        if (target == null) {
            removeFromGlowTeams(found.getValue().name);
        } else {
            clear(target, found.getValue());
        }
        if (!silent) {
            send(sender, "<green>Removed <white>" + found.getValue().name + "</white>'s glow.");
        }
        return true;
    }

    private void apply(Player player) {
        Assignment assignment = assignments.get(player.getUniqueId());
        if (assignment == null) {
            return;
        }
        removeFromGlowTeams(player.getName());
        glowTeam(assignment.color).addEntity(player);
        player.setGlowing(true);
    }

    private void clear(Player player, Assignment assignment) {
        removeFromGlowTeams(player.getName());
        player.setGlowing(false);
        if (assignment.previousTeam == null) {
            return;
        }
        Team previous = mainScoreboard().getTeam(assignment.previousTeam);
        if (previous != null) {
            previous.addEntity(player);
        }
    }

    private void animate() {
        animationTick = (animationTick + 1) % RAINBOW_CYCLE_TICKS;
        int paletteIndex = animationTick * RAINBOW_COLORS.size() / RAINBOW_CYCLE_TICKS;
        Team rainbowTeam = existingGlowTeam(RAINBOW);
        if (rainbowTeam != null) {
            rainbowTeam.color(RAINBOW_COLORS.get(paletteIndex));
        }

        Color color = rainbowColor(animationTick, RAINBOW_CYCLE_TICKS);
        Color nextColor = rainbowColor(animationTick + 1, RAINBOW_CYCLE_TICKS);
        float pulse = (float) (.75 + .2 * Math.sin(animationTick * Math.PI * 2 / 30d));
        Particle.DustTransition dust = new Particle.DustTransition(color, nextColor, pulse);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Assignment assignment = assignments.get(player.getUniqueId());
            if (assignment == null) {
                continue;
            }
            if (!player.isGlowing()) {
                player.setGlowing(true);
            }
            if (!assignment.color.equals(RAINBOW)) {
                continue;
            }
            player.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION,
                player.getLocation().add(0, 1, 0),
                2, .32, .65, .32, 0, dust);
        }
    }

    private void removeLegacyOutlines(Entity[] entities) {
        for (Entity entity : entities) {
            if (entity.getPersistentDataContainer().has(legacyOutlineKey, PersistentDataType.STRING)) {
                entity.remove();
            }
        }
    }

    private Team glowTeam(String color) {
        Team team = existingGlowTeam(color);
        if (team == null) {
            team = mainScoreboard().registerNewTeam(teamName(color));
        }
        team.color(color.equals(RAINBOW) ? RAINBOW_COLORS.get(0) : COLORS.get(color));
        return team;
    }

    private Team existingGlowTeam(String color) {
        return mainScoreboard().getTeam(teamName(color));
    }

    private void removeFromGlowTeams(String entry) {
        mainScoreboard().getTeams().stream().filter(GlowModule::isGlowTeam)
            .forEach(team -> team.removeEntry(entry));
    }

    private Scoreboard mainScoreboard() {
        return plugin.getServer().getScoreboardManager().getMainScoreboard();
    }

    private void load() {
        ConfigurationSection root = data.getConfigurationSection("players");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            try {
                UUID owner = UUID.fromString(key);
                String name = root.getString(key + ".name");
                String color = root.getString(key + ".color", "white").toLowerCase(Locale.ROOT);
                String previousTeam = root.getString(key + ".previous-team");
                if (name == null || name.isBlank()
                    || !color.equals(RAINBOW) && !COLORS.containsKey(color)) {
                    throw new IllegalArgumentException("invalid player glow");
                }
                assignments.put(owner, new Assignment(name, color, previousTeam));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipped invalid player glow entry: " + key);
            }
        }
    }

    private boolean save(CommandSender sender) {
        data.set("players", null);
        assignments.forEach((owner, assignment) -> {
            String path = "players." + owner;
            data.set(path + ".name", assignment.name);
            data.set(path + ".color", assignment.color);
            data.set(path + ".previous-team", assignment.previousTeam);
        });
        try {
            plugin.saveData("glow");
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/glow.yml: " + exception.getMessage());
            if (sender != null) {
                send(sender, "<red>The glow change could not be saved.");
            }
            return false;
        }
    }

    private void showColors(CommandSender sender) {
        send(sender, "<aqua>Glow colors:</aqua> <white>" + String.join("</white>, <white>", COLORS.keySet())
            + "</white>, <rainbow>rainbow</rainbow>");
    }

    private List<String> onlinePlayerNames() {
        return plugin.getServer().getOnlinePlayers().stream().map(Player::getName)
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    static Color rainbowColor(int tick, int cycleTicks) {
        float hue = Math.floorMod(tick, cycleTicks) / (float) cycleTicks;
        int sector = (int) (hue * 6);
        float fraction = hue * 6 - sector;
        int rising = Math.round(fraction * 255);
        int falling = 255 - rising;
        return switch (sector) {
            case 0 -> Color.fromRGB(255, rising, 0);
            case 1 -> Color.fromRGB(falling, 255, 0);
            case 2 -> Color.fromRGB(0, 255, rising);
            case 3 -> Color.fromRGB(0, falling, 255);
            case 4 -> Color.fromRGB(rising, 0, 255);
            default -> Color.fromRGB(255, 0, falling);
        };
    }

    private static Map<String, NamedTextColor> colors() {
        Map<String, NamedTextColor> colors = new LinkedHashMap<>();
        colors.put("black", NamedTextColor.BLACK);
        colors.put("dark_blue", NamedTextColor.DARK_BLUE);
        colors.put("dark_green", NamedTextColor.DARK_GREEN);
        colors.put("dark_aqua", NamedTextColor.DARK_AQUA);
        colors.put("dark_red", NamedTextColor.DARK_RED);
        colors.put("dark_purple", NamedTextColor.DARK_PURPLE);
        colors.put("gold", NamedTextColor.GOLD);
        colors.put("gray", NamedTextColor.GRAY);
        colors.put("dark_gray", NamedTextColor.DARK_GRAY);
        colors.put("blue", NamedTextColor.BLUE);
        colors.put("green", NamedTextColor.GREEN);
        colors.put("aqua", NamedTextColor.AQUA);
        colors.put("red", NamedTextColor.RED);
        colors.put("light_purple", NamedTextColor.LIGHT_PURPLE);
        colors.put("yellow", NamedTextColor.YELLOW);
        colors.put("white", NamedTextColor.WHITE);
        return Collections.unmodifiableMap(colors);
    }

    private static String teamName(String color) {
        return "rvt_glow_" + (color.equals(RAINBOW) ? "rainbow"
            : Integer.toHexString(new ArrayList<>(COLORS.keySet()).indexOf(color)));
    }

    private static boolean isGlowTeam(Team team) {
        return team.getName().startsWith("rvt_glow_");
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(MM.deserialize(message));
    }

    private static final class Assignment {
        private String name;
        private final String color;
        private final String previousTeam;

        private Assignment(String name, String color, String previousTeam) {
            this.name = name;
            this.color = color;
            this.previousTeam = previousTeam;
        }

        private String name() {
            return name;
        }
    }
}
