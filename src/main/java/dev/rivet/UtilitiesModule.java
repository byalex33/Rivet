package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class UtilitiesModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private static final Color[] CONFETTI_COLORS = {
        Color.fromRGB(0xF94144), Color.fromRGB(0xF8961E),
        Color.fromRGB(0xF9C74F), Color.fromRGB(0x90BE6D),
        Color.fromRGB(0x43AA8B), Color.fromRGB(0x4D9DE0),
        Color.fromRGB(0x9B5DE5), Color.fromRGB(0xF15BB5)
    };
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

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onItemDamage(PlayerItemDamageEvent event) {
        var settings = plugin.settings("utilities");
        if (!settings.getBoolean("durability-warning.enabled", true)) {
            return;
        }
        ItemStack item = event.getItem();
        ItemMeta itemMeta = item.getItemMeta();
        if (!(itemMeta instanceof Damageable damageable)) {
            return;
        }
        int maximum = damageable.hasMaxDamage()
            ? damageable.getMaxDamage() : item.getType().getMaxDurability();
        int threshold = Math.clamp(
            settings.getInt("durability-warning.threshold-percent", 10), 1, 100);
        if (!crossesDurabilityThreshold(maximum, damageable.getDamage(), event.getDamage(), threshold)) {
            return;
        }
        int remaining = Math.max(0, maximum - damageable.getDamage() - event.getDamage());
        int percent = remaining * 100 / maximum;
        plugin.messageActions().run(event.getPlayer(), settings, "durability-warning.alert", List.of(
                "[message] <#f72a4c>Warning:</#f72a4c> <white>Your %item% is at %percent%% durability (%remaining%/%maximum%).</white>",
                "[sound] block.note_block.pling 0.8 1.4"),
            Placeholder.component("item", itemName(item)),
            Placeholder.unparsed("percent", Integer.toString(percent)),
            Placeholder.unparsed("remaining", Integer.toString(remaining)),
            Placeholder.unparsed("maximum", Integer.toString(maximum)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreeperExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Creeper)
            || !plugin.settings("utilities").getBoolean("creeper-confetti.enabled", true)
            || !confettiSelected(plugin.settings("utilities")
                .getDouble("creeper-confetti.chance", .1),
                ThreadLocalRandom.current().nextDouble())) {
            return;
        }

        // Leave vanilla entity damage intact, but remove all block damage and drops.
        event.blockList().clear();
        event.setYield(0);
        playConfetti(event.getLocation().clone().add(0, .5, 0));
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

    static boolean crossesDurabilityThreshold(int maximum, int currentDamage,
                                               int additionalDamage, int thresholdPercent) {
        if (maximum <= 0 || additionalDamage <= 0
            || thresholdPercent < 1 || thresholdPercent > 100) {
            return false;
        }
        int before = Math.max(0, maximum - currentDamage);
        int after = Math.max(0, before - additionalDamage);
        long threshold = (long) maximum * thresholdPercent;
        return (long) before * 100 >= threshold && (long) after * 100 < threshold;
    }

    static boolean confettiSelected(double chance, double roll) {
        double boundedChance = Double.isFinite(chance)
            ? Math.max(0, Math.min(1, chance)) : 0;
        return roll >= 0 && roll < boundedChance;
    }

    private static Component itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Component name;
        if (meta != null && meta.hasCustomName()) {
            name = meta.customName();
        } else if (meta != null && meta.hasItemName()) {
            name = meta.itemName();
        } else {
            name = Component.translatable(item.translationKey());
        }
        return DeathMessagesModule.safeWeaponComponent(name.hoverEvent(item.asHoverEvent()));
    }

    private void playConfetti(Location center) {
        var settings = plugin.settings("utilities");
        if (plugin.getConfig().getBoolean("effects.particles")) {
            int count = Math.max(0, Math.min(512,
                settings.getInt("creeper-confetti.particles.count", 96)));
            double spread = Math.max(0, Math.min(8,
                settings.getDouble("creeper-confetti.particles.spread", 1.5)));
            float size = (float) Math.max(.1, Math.min(4,
                settings.getDouble("creeper-confetti.particles.size", 1)));
            for (int index = 0; index < CONFETTI_COLORS.length; index++) {
                int amount = count / CONFETTI_COLORS.length
                    + (index < count % CONFETTI_COLORS.length ? 1 : 0);
                if (amount > 0) {
                    center.getWorld().spawnParticle(Particle.DUST, center, amount,
                        spread, spread * .75, spread, 0,
                        new Particle.DustOptions(CONFETTI_COLORS[index], size));
                }
            }
        }
        if (plugin.getConfig().getBoolean("effects.sounds")
            && settings.getBoolean("creeper-confetti.sound.enabled", true)) {
            center.getWorld().playSound(center,
                ConfiguredEffect.sound(plugin, settings, "creeper-confetti.sound.name",
                    Sound.ENTITY_FIREWORK_ROCKET_BLAST),
                Math.max(0, (float) settings.getDouble("creeper-confetti.sound.volume", 1)),
                Math.max(.01f, Math.min(2,
                    (float) settings.getDouble("creeper-confetti.sound.pitch", 1.2))));
        }
    }

    private static void send(Player player, String message) {
        player.sendMessage(MM.deserialize(message));
    }

    record TargetArgument(String name, boolean valid) {
    }
}
