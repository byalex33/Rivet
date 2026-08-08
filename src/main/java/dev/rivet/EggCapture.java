package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SpawnEggMeta;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

final class EggCapture implements Listener {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int ANIMATION_TICKS = 28;

    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final Map<UUID, Capture> captures = new HashMap<>();

    EggCapture(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("egg-capture");
    }

    @EventHandler(ignoreCancelled = true)
    public void onEggHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Egg egg)
            || !(egg.getShooter() instanceof Player player)
            || !(event.getHitEntity() instanceof Mob mob)
            || !player.hasPermission("rivet.eggcapture")
            || captures.containsKey(mob.getUniqueId())) {
            return;
        }

        EntitySnapshot snapshot = mob.createSnapshot();
        Material material = Bukkit.getItemFactory().getSpawnEgg(mob.getType());
        AttributeInstance scale = mob.getAttribute(Attribute.SCALE);
        if (snapshot == null || material == null || scale == null) {
            player.sendActionBar(MM.deserialize(settings.getString("messages.cannot-capture",
                "<white>That creature cannot be captured.</white>")));
            return;
        }

        ItemStack capturedEgg = new ItemStack(material);
        if (!(capturedEgg.getItemMeta() instanceof SpawnEggMeta meta)) {
            return;
        }
        meta.setSpawnedEntity(snapshot);
        meta.displayName(MM.deserialize(settings.getString("captured-egg.name",
                "<white>Captured <#f72a4c><mob></#f72a4c></white>"),
            Placeholder.component("mob", mob.name())));
        java.util.List<String> lore = settings.getStringList("captured-egg.lore");
        if (lore.isEmpty()) {
            lore = java.util.List.of("<white>Contains the original creature.</white>");
        }
        meta.lore(lore.stream().map(MM::deserialize).toList());
        capturedEgg.setItemMeta(meta);

        event.setCancelled(true);
        egg.remove();
        Capture capture = new Capture(mob, player, capturedEgg, scale, scale.getBaseValue(),
            mob.hasAI(), mob.hasGravity(), mob.isInvulnerable(), mob.isGlowing());
        captures.put(mob.getUniqueId(), capture);
        mob.setAI(false);
        mob.setGravity(false);
        mob.setInvulnerable(true);
        mob.setGlowing(true);
        mob.setVelocity(new Vector());
        animate(capture);
    }

    void shutdown() {
        captures.values().forEach(capture -> {
            restore(capture);
            give(capture.player, new ItemStack(Material.EGG), capture.mob.getLocation());
        });
        captures.clear();
    }

    private void animate(Capture capture) {
        Mob mob = capture.mob;
        playSound(mob.getLocation(), "effects.start-sound",
            Sound.BLOCK_TRIAL_SPAWNER_OMINOUS_ACTIVATE, .8f, 1.35f);
        Particle.DustTransition vortexDust = new Particle.DustTransition(
            ConfiguredEffect.color(plugin, settings,
                "effects.particles.vortex-first-color", 0x55FFFF),
            ConfiguredEffect.color(plugin, settings,
                "effects.particles.vortex-second-color", 0xBE55FF),
            (float) Math.max(.01, settings.getDouble("effects.particles.vortex-size", 1.25)));
        new BukkitRunnable() {
            private int tick;

            @Override
            public void run() {
                if (!captures.containsKey(mob.getUniqueId())) {
                    cancel();
                    return;
                }
                if (tick == ANIMATION_TICKS) {
                    captures.remove(mob.getUniqueId());
                    cancel();
                    pop(capture);
                    return;
                }

                try {
                    double progress = (tick + 1d) / ANIMATION_TICKS;
                    capture.scale.setBaseValue(captureScale(capture.originalScale, progress));
                    Location center = mob.getLocation().add(0, Math.min(mob.getHeight() / 2, 4), 0);
                    double radius = .65 + progress * 1.5;
                    if (settings.getBoolean("effects.particles.enabled", true)
                        && settings.getBoolean("effects.particles.vortex-enabled", true)) {
                        for (int point = 0; point < 4; point++) {
                            double angle = tick * .55 + point * Math.PI / 2;
                            Location ring = center.clone().add(Math.cos(angle) * radius,
                                Math.sin(tick * .35 + point) * .45, Math.sin(angle) * radius);
                            mob.getWorld().spawnParticle(
                                Particle.DUST_COLOR_TRANSITION, ring, 1, vortexDust);
                        }
                    }
                    if (settings.getBoolean("effects.particles.enabled", true)
                        && settings.getBoolean("effects.particles.portal-enabled", true)) {
                        mob.getWorld().spawnParticle(ConfiguredEffect.particle(plugin, settings,
                                "effects.particles.portal-name", Particle.REVERSE_PORTAL), center,
                            Math.max(0, settings.getInt("effects.particles.portal-count", 8)),
                            radius, Math.max(1, mob.getHeight() / 3), radius, .15);
                    }
                    tick++;
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "Capture animation failed for "
                        + mob.getType() + "; completing capture without it.", exception);
                    captures.remove(mob.getUniqueId());
                    cancel();
                    pop(capture);
                }
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    private void pop(Capture capture) {
        Location drop = capture.mob.getLocation();
        Location center = drop.clone().add(0, Math.min(capture.mob.getHeight() / 2, 4), 0);
        World world = drop.getWorld();
        capture.mob.remove();
        give(capture.player, capture.egg, drop);
        if (settings.getBoolean("effects.particles.enabled", true)) {
            if (settings.getBoolean("effects.particles.completion-flash-enabled", true)) {
                world.spawnParticle(Particle.FLASH, center,
                    Math.max(0, settings.getInt("effects.particles.completion-flash-count", 1)));
            }
            if (settings.getBoolean("effects.particles.completion-explosion-enabled", true)) {
                world.spawnParticle(Particle.EXPLOSION, center,
                    Math.max(0, settings.getInt("effects.particles.completion-explosion-count", 4)),
                    .5, .5, .5, 0);
            }
            if (settings.getBoolean("effects.particles.completion-sparks-enabled", true)) {
                world.spawnParticle(Particle.ELECTRIC_SPARK, center,
                    Math.max(0, settings.getInt("effects.particles.completion-sparks-count", 45)),
                    1.5, 1.5, 1.5, .3);
            }
            if (settings.getBoolean("effects.particles.completion-portals-enabled", true)) {
                world.spawnParticle(Particle.REVERSE_PORTAL, center,
                    Math.max(0, settings.getInt("effects.particles.completion-portals-count", 70)),
                    1.5, 1.5, 1.5, .4);
            }
        }
        playSound(center, "effects.completion-sound",
            Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1, .8f);
    }

    private static void restore(Capture capture) {
        if (!capture.mob.isValid()) {
            return;
        }
        capture.scale.setBaseValue(capture.originalScale);
        capture.mob.setAI(capture.ai);
        capture.mob.setGravity(capture.gravity);
        capture.mob.setInvulnerable(capture.invulnerable);
        capture.mob.setGlowing(capture.glowing);
    }

    private void give(Player player, ItemStack item, Location fallback) {
        if (!player.isOnline()) {
            fallback.getWorld().dropItemNaturally(fallback, item);
            return;
        }
        player.getInventory().addItem(item).values()
            .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        playSound(player.getLocation(), "effects.received-sound",
            Sound.ENTITY_ALLAY_ITEM_GIVEN, .8f, 1.3f);
    }

    private void playSound(Location location, String path, Sound fallback,
                           float defaultVolume, float defaultPitch) {
        if (!settings.getBoolean(path + ".enabled", true)) {
            return;
        }
        location.getWorld().playSound(location,
            ConfiguredEffect.sound(plugin, settings, path + ".name", fallback),
            (float) settings.getDouble(path + ".volume", defaultVolume),
            (float) settings.getDouble(path + ".pitch", defaultPitch));
    }

    static double captureScale(double original, double progress) {
        return original + (Math.min(original * 6, 16) - original) * progress * progress;
    }

    private record Capture(Mob mob, Player player, ItemStack egg, AttributeInstance scale,
                           double originalScale, boolean ai, boolean gravity,
                           boolean invulnerable, boolean glowing) {
    }
}
