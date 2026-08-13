package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class UtilitiesModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private final RivetPlugin plugin;
    private final Set<UUID> riders = new java.util.HashSet<>();

    UtilitiesModule(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    boolean command(Player player, String command, String[] args) {
        if (command.equals("jump")) {
            return jump(player, args);
        }
        if (command.equals("list")) {
            return list(player, args);
        }
        if (command.equals("nv")) {
            return nightVision(player, args);
        }
        if (command.equals("ping")) {
            return ping(player, args);
        }
        if (command.equals("ride")) {
            return ride(player, args);
        }
        if (args.length != 0) {
            player.sendMessage(MM.deserialize("<white>Usage: /" + command));
            return true;
        }
        if (!plugin.settings("utilities").getBoolean("interfaces." + command, true)) {
            player.sendMessage(MM.deserialize("<white>That portable utility is disabled."));
            return true;
        }
        switch (command) {
            case "craft" -> player.openWorkbench(null, true);
            case "anvil" -> player.openAnvil(null, true);
            case "smithing" -> player.openSmithingTable(null, true);
            case "stonecutter" -> player.openStonecutter(null, true);
            case "grindstone" -> player.openGrindstone(null, true);
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean jump(Player player, String[] args) {
        if (args.length != 0) {
            send(player, "<white>Usage: /jump");
            return true;
        }
        int range = boundedRange("jump.maximum-range", 50);
        Block target = player.getTargetBlockExact(range, FluidCollisionMode.NEVER);
        if (target == null) {
            send(player, "<white>No block is in range.");
            return true;
        }
        var destination = SafeLocations.nearTarget(target, player.getLocation());
        if (destination == null || !player.teleport(destination)) {
            send(player, "<white>No safe standing position was found near that block.");
            return true;
        }
        send(player, "<white>Jumped to the targeted block.");
        if (plugin.getConfig().getBoolean("effects.sounds")) {
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, .8f, 1.25f);
        }
        if (plugin.getConfig().getBoolean("effects.particles")) {
            player.spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0),
                30, .35, .7, .35, .05);
        }
        return true;
    }

    private boolean list(Player viewer, String[] args) {
        if (args.length != 0) {
            send(viewer, "<white>Usage: /list");
            return true;
        }
        List<Player> visible = new ArrayList<Player>(plugin.getServer().getOnlinePlayers().stream()
            .filter(viewer::canSee).sorted(java.util.Comparator.comparing(Player::getName,
                String.CASE_INSENSITIVE_ORDER)).toList());
        Component names = Component.empty();
        for (int index = 0; index < visible.size(); index++) {
            if (index > 0) {
                names = names.append(Component.text(", "));
            }
            names = names.append(visible.get(index).displayName());
        }
        if (visible.isEmpty()) {
            names = Component.text("none");
        }
        plugin.messageActions().run(viewer, plugin.settings("utilities"), "list.output",
            "<#f72a4c>Online (%count%/%maximum%):</#f72a4c> <#f72a4c>%players%</#f72a4c>",
            Placeholder.unparsed("count", Integer.toString(visible.size())),
            Placeholder.unparsed("maximum", Integer.toString(plugin.getServer().getMaxPlayers())),
            Placeholder.component("players", names));
        return true;
    }

    private boolean ping(Player actor, String[] args) {
        TargetArgument parsed = parseOptionalTarget(args, actor.hasPermission("rivet.ping.others"));
        if (!parsed.valid()) {
            send(actor, "<white>Usage: /ping" + (actor.hasPermission("rivet.ping.others") ? " [player]" : ""));
            return true;
        }
        Player target = parsed.name() == null ? actor : plugin.getServer().getPlayerExact(parsed.name());
        if (target == null || !actor.canSee(target)) {
            send(actor, "<white>That player is not online.");
            return true;
        }
        int ping = target.getPing();
        String quality = pingQuality(ping);
        plugin.messageActions().run(actor, plugin.settings("utilities"), "ping.output",
            "<#f72a4c>%player%'s ping:</#f72a4c> <#f72a4c>%ping% ms</#f72a4c> <white>(%quality%)</white>",
            Placeholder.unparsed("player", target.getName()),
            Placeholder.unparsed("ping", Integer.toString(ping)),
            Placeholder.unparsed("quality", quality));
        return true;
    }

    private boolean nightVision(Player player, String[] args) {
        if (args.length != 0) {
            send(player, "<white>Usage: /nv");
            return true;
        }
        boolean enable = nextNightVisionState(
            player.hasPotionEffect(PotionEffectType.NIGHT_VISION));
        if (enable) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                PotionEffect.INFINITE_DURATION, 0, false, false, false));
        } else {
            player.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
        plugin.messageActions().run(player, plugin.settings("utilities"),
            enable ? "night-vision.enabled" : "night-vision.disabled",
            enable ? "<white>Night vision enabled.</white>"
                : "<white>Night vision disabled.</white>");
        return true;
    }

    private boolean ride(Player player, String[] args) {
        if (args.length != 0) {
            send(player, "<white>Usage: /ride");
            return true;
        }
        if (player.isInsideVehicle()) {
            riders.remove(player.getUniqueId());
            player.leaveVehicle();
            send(player, "<white>You dismounted.");
            return true;
        }
        int range = boundedRange("ride.maximum-range", 10);
        Entity target = player.getTargetEntity(range, false);
        boolean playersAllowed = plugin.settings("utilities").getBoolean("ride.allow-players", false);
        if (!validRideTarget(target != null && target.equals(player), target instanceof Player,
            playersAllowed, target != null && (!(target instanceof Player targetPlayer)
                || player.canSee(targetPlayer)) && target.isValid() && !target.isDead()
                && (target instanceof LivingEntity || target instanceof Vehicle)
                && !(target instanceof ArmorStand),
            target != null && !target.getPassengers().isEmpty())) {
            send(player, "<white>No safe rideable entity is in range.");
            return true;
        }
        if (!target.addPassenger(player)) {
            send(player, "<white>That entity could not be ridden.");
            return true;
        }
        riders.add(player.getUniqueId());
        send(player, "<white>You are now riding <#f72a4c>" + target.getType().name()
            .toLowerCase(Locale.ROOT).replace('_', ' ') + "</#f72a4c>.");
        return true;
    }

    List<String> completions(Player actor, String command, String[] args) {
        if (command.equals("ping") && args.length == 1
            && actor.hasPermission("rivet.ping.others")) {
            return plugin.getServer().getOnlinePlayers().stream().filter(actor::canSee)
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        return List.of();
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (riders.remove(event.getPlayer().getUniqueId())) {
            event.getPlayer().leaveVehicle();
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (riders.remove(event.getPlayer().getUniqueId())) {
            event.getPlayer().leaveVehicle();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (riders.remove(event.getPlayer().getUniqueId())) {
            event.getPlayer().leaveVehicle();
        }
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            riders.remove(player.getUniqueId());
        }
    }

    void shutdown() {
        new java.util.HashSet<>(riders).stream().map(plugin.getServer()::getPlayer)
            .filter(java.util.Objects::nonNull).forEach(Player::leaveVehicle);
        riders.clear();
    }

    private int boundedRange(String path, int fallback) {
        return Math.max(1, Math.min(200, plugin.settings("utilities").getInt(path, fallback)));
    }

    static boolean validRideTarget(boolean self, boolean player, boolean allowPlayers,
                                   boolean supported, boolean occupied) {
        return !self && (!player || allowPlayers) && supported && !occupied;
    }

    static TargetArgument parseOptionalTarget(String[] args, boolean canTargetOthers) {
        if (args.length == 0) {
            return new TargetArgument(null, true);
        }
        return args.length == 1 && canTargetOthers
            ? new TargetArgument(args[0], true) : new TargetArgument(null, false);
    }

    static String pingQuality(int ping) {
        return ping < 75 ? "excellent" : ping < 150 ? "good" : ping < 250 ? "fair" : "poor";
    }

    static boolean nextNightVisionState(boolean currentlyEnabled) {
        return !currentlyEnabled;
    }

    private static void send(Player player, String message) {
        player.sendMessage(MM.deserialize(message));
    }

    record TargetArgument(String name, boolean valid) {
    }
}
