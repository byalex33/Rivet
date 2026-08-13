package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

final class CreeperRestoration implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private static final int MAX_GIVE_AMOUNT = 2_304;

    private final RivetPlugin plugin;
    private final YamlConfiguration settings;
    private final NamespacedKey coreKey;
    private final NamespacedKey projectileKey;
    private final List<Crater> craters = new ArrayList<>();
    private final Set<UUID> projectiles = new HashSet<>();
    private final Set<UUID> displays = new HashSet<>();

    CreeperRestoration(RivetPlugin plugin) {
        this.plugin = plugin;
        settings = plugin.settings("creeper-restoration");
        coreKey = new NamespacedKey(plugin, "restoration_core");
        projectileKey = new NamespacedKey(plugin, "restoration_core_projectile");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreeperExplode(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Creeper) || event.blockList().isEmpty()) {
            return;
        }

        List<SavedBlock> blocks = event.blockList().stream()
            .filter(block -> !block.getType().isAir())
            .map(this::snapshot)
            .filter(java.util.Objects::nonNull)
            .toList();

        // Snapshot every block before touching a live inventory. When container contents will
        // be restored, move them into the in-memory snapshot by clearing the live inventory
        // before vanilla destroys the block. Yield alone does not reliably suppress inventory
        // drops on every Paper/plugin combination, which could otherwise create a second copy.
        blocks.forEach(this::escrowContainerContents);
        event.setYield(0);
        if (blocks.isEmpty()) {
            return;
        }

        Crater crater = new Crater(event.getLocation().clone(), new ArrayList<>(blocks));
        craters.add(crater);
        if (settings.getBoolean("explosion-debris.enabled", true)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> animateExplosion(crater));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUseCore(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        ItemStack held = event.getItem();
        if (hand == null || !isCore(held)) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.hasCooldown(coreMaterial())) {
            return;
        }
        consume(player, hand, held);
        Snowball projectile = player.launchProjectile(Snowball.class,
            player.getEyeLocation().getDirection().multiply(Math.max(.1,
                settings.getDouble("core.projectile-speed", 1.5))));
        projectile.setItem(core());
        projectile.getPersistentDataContainer().set(projectileKey,
            PersistentDataType.BYTE, (byte) 1);
        projectiles.add(projectile.getUniqueId());
        player.setCooldown(coreMaterial(), Math.max(0,
            settings.getInt("core.throw-cooldown-ticks", 8)));
        playSound(player.getLocation(), "effects.throw-sound", Sound.ENTITY_EGG_THROW,
            .8f, .8f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCoreHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball projectile)
            || !projectile.getPersistentDataContainer().has(
                projectileKey, PersistentDataType.BYTE)) {
            return;
        }

        projectiles.remove(projectile.getUniqueId());
        Location impact = projectile.getLocation();
        Player player = projectile.getShooter() instanceof Player shooter ? shooter : null;
        projectile.remove();
        Crater crater = closestCrater(impact);
        if (crater == null || !beginRestoration(crater, player)) {
            ItemStack returned = core();
            impact.getWorld().dropItem(impact, returned, item -> item.setVelocity(new Vector()));
            playSound(impact, "effects.miss-sound", Sound.BLOCK_AMETHYST_BLOCK_HIT,
                .7f, .7f);
            if (player != null) {
                plugin.messageActions().run(player, settings, "messages.no-crater", "actionbar",
                    "<white>No repairable creeper crater was found nearby.</white>");
            }
        }
    }

    boolean command(CommandSender sender, String[] args) {
        Player target;
        String amountArgument = null;
        if (args.length == 0 && sender instanceof Player player) {
            target = player;
        } else if (args.length == 1 && sender instanceof Player player
            && RivetPlugin.itemAmount(args[0]) != -1) {
            target = player;
            amountArgument = args[0];
        } else if (args.length == 1 || args.length == 2) {
            target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null) {
                message(sender, "messages.player-not-online",
                    "<white>That player is not online.</white>");
                return true;
            }
            if (args.length == 2) {
                amountArgument = args[1];
            }
        } else {
            usage(sender);
            return true;
        }

        int amount = amountArgument == null ? 1 : RivetPlugin.itemAmount(amountArgument);
        if (amount < 1 || amount > MAX_GIVE_AMOUNT) {
            message(sender, "messages.invalid-amount",
                "<white>Amount must be a whole number from 1 to 2304.</white>");
            return true;
        }

        int remaining = amount;
        while (remaining > 0) {
            ItemStack stack = core();
            int stackAmount = Math.min(remaining, stack.getMaxStackSize());
            stack.setAmount(stackAmount);
            target.getInventory().addItem(stack).values().forEach(leftover ->
                target.getWorld().dropItemNaturally(target.getLocation(), leftover));
            remaining -= stackAmount;
        }

        TagResolver[] placeholders = {
            Placeholder.unparsed("amount", Integer.toString(amount)),
            Placeholder.unparsed("plural", amount == 1 ? "" : "s"),
            Placeholder.unparsed("player", target.getName())
        };
        message(sender, "messages.given",
            "<white>Gave <#f72a4c>%amount% Restoration Core%plural%</#f72a4c> to <#f72a4c>%player%</#f72a4c>.</white>",
            placeholders);
        if (!sender.equals(target)) {
            message(target, "messages.received",
                "<white>You received <#f72a4c>%amount% Restoration Core%plural%</#f72a4c>.</white>",
                placeholders);
        }
        return true;
    }

    List<String> completions(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>();
            if (sender instanceof Player) {
                choices.addAll(List.of("1", "16", "32", "64"));
            }
            choices.addAll(plugin.getServer().getOnlinePlayers().stream()
                .map(Player::getName).sorted(String.CASE_INSENSITIVE_ORDER).toList());
            return choices;
        }
        return args.length == 2 ? List.of("1", "16", "32", "64") : List.of();
    }

    void shutdown() {
        // Finish an in-progress reconstruction so a restart cannot leave half a crater repaired.
        craters.stream().filter(crater -> crater.restoring).forEach(crater ->
            crater.blocks.forEach(saved -> {
                if (saved.block().getType().isAir()) {
                    saved.restore();
                }
            }));
        displays.forEach(uuid -> {
            Entity entity = plugin.getServer().getEntity(uuid);
            if (entity != null) {
                entity.remove();
            }
        });
        projectiles.forEach(uuid -> {
            Entity entity = plugin.getServer().getEntity(uuid);
            if (entity != null && entity.isValid()) {
                entity.getWorld().dropItem(entity.getLocation(), core());
                entity.remove();
            }
        });
        displays.clear();
        projectiles.clear();
        craters.clear();
    }

    private SavedBlock snapshot(Block block) {
        BlockState state = block.getState(true);
        if (state instanceof Container
            && !settings.getBoolean("restoration.restore-containers", true)) {
            return null;
        }
        if (state instanceof Container container
            && !settings.getBoolean("restoration.restore-container-contents", true)) {
            container.getSnapshotInventory().clear();
        }
        boolean preserveState = state instanceof Container
            || state instanceof TileState
            && settings.getBoolean("restoration.restore-other-block-entity-data", true);
        return new SavedBlock(block.getWorld(), block.getX(), block.getY(), block.getZ(),
            block.getBlockData().clone(), preserveState ? state : null, debrisVelocity(block));
    }

    private void escrowContainerContents(SavedBlock saved) {
        if (!shouldEscrowContents(
                settings.getBoolean("restoration.restore-containers", true),
                settings.getBoolean("restoration.restore-container-contents", true),
                saved.state instanceof Container)
            || !(saved.state instanceof Container snapshot)) {
            return;
        }
        try {
            BlockState liveState = saved.block().getState(false);
            if (!(liveState instanceof Container live)) {
                // Fail closed: if Rivet cannot remove the live copy, do not restore another.
                snapshot.getSnapshotInventory().clear();
                return;
            }
            if (live instanceof Chest chest) {
                // getInventory() combines both halves of a double chest. Clear only the block
                // represented by this saved snapshot so a surviving half is never emptied.
                chest.getBlockInventory().clear();
                if (!chest.getBlockInventory().isEmpty()) {
                    snapshot.getSnapshotInventory().clear();
                }
            } else {
                live.getInventory().clear();
                if (!live.getInventory().isEmpty()) {
                    snapshot.getSnapshotInventory().clear();
                }
            }
        } catch (RuntimeException exception) {
            snapshot.getSnapshotInventory().clear();
            plugin.getLogger().log(Level.WARNING,
                "Could not escrow container contents at " + saved.x + ", " + saved.y + ", "
                    + saved.z + "; its saved contents were discarded to prevent duplication.",
                exception);
        }
    }

    private Vector debrisVelocity(Block block) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(Math.PI * 2);
        double horizontal = Math.max(0,
            settings.getDouble("explosion-debris.horizontal-speed", .16))
            * random.nextDouble(.7, 1.3);
        double vertical = Math.max(0,
            settings.getDouble("explosion-debris.vertical-speed", .27))
            * random.nextDouble(.75, 1.25);
        return new Vector(Math.cos(angle) * horizontal, vertical,
            Math.sin(angle) * horizontal);
    }

    private void animateExplosion(Crater crater) {
        int maximum = Math.max(0, settings.getInt("explosion-debris.maximum-displays", 128));
        int duration = Math.max(1, settings.getInt("explosion-debris.duration-ticks", 24));
        List<MovingBlock> moving = crater.blocks.stream().limit(maximum)
            .map(saved -> new MovingBlock(spawnDisplay(saved.target(), saved.data), saved))
            .filter(block -> block.display != null)
            .toList();
        if (moving.isEmpty()) {
            return;
        }

        new BukkitRunnable() {
            private int tick;

            @Override
            public void run() {
                tick++;
                double time = tick;
                for (MovingBlock movingBlock : moving) {
                    SavedBlock saved = movingBlock.saved;
                    Location location = saved.target().add(
                        saved.velocity.getX() * time,
                        saved.velocity.getY() * time - .0065 * time * time,
                        saved.velocity.getZ() * time);
                    movingBlock.display.teleport(location);
                }
                if (tick >= duration) {
                    moving.forEach(block -> removeDisplay(block.display));
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    private boolean beginRestoration(Crater crater, Player player) {
        List<SavedBlock> repairable = crater.blocks.stream()
            .filter(saved -> saved.block().getType().isAir())
            .sorted(Comparator.comparingInt((SavedBlock saved) -> saved.y)
                .thenComparingDouble(saved -> saved.target().distanceSquared(crater.center)))
            .toList();
        if (repairable.isEmpty()) {
            return false;
        }

        crater.restoring = true;
        int totalDuration = Math.max(1,
            settings.getInt("restoration.total-duration-ticks", 70));
        int flightDuration = Math.max(1, Math.min(totalDuration,
            settings.getInt("restoration.block-flight-ticks", 16)));
        if (player != null) {
            plugin.messageActions().run(player, settings, "messages.restoring", "actionbar",
                "<white>Reconstructing <#f72a4c>%blocks%</#f72a4c> blocks.</white>",
                Placeholder.unparsed("blocks", Integer.toString(repairable.size())));
        }
        playSound(crater.center, "effects.start-sound", Sound.BLOCK_BEACON_ACTIVATE,
            1, 1.35f);
        animateRestoration(crater, repairable, player, totalDuration, flightDuration);
        return true;
    }

    private void animateRestoration(Crater crater, List<SavedBlock> repairable, Player player,
                                    int totalDuration, int flightDuration) {
        new BukkitRunnable() {
            private final List<ReturningBlock> active = new ArrayList<>();
            private int next;
            private int tick;
            private int restored;

            @Override
            public void run() {
                try {
                    while (next < repairable.size()
                        && startTick(next, repairable.size(), totalDuration, flightDuration) <= tick) {
                        SavedBlock saved = repairable.get(next++);
                        BlockDisplay display = spawnDisplay(debrisEnd(saved), saved.data);
                        if (display != null) {
                            active.add(new ReturningBlock(display, saved, tick));
                        } else if (saved.block().getType().isAir()) {
                            saved.restore();
                            restored++;
                        }
                    }

                    boolean placedThisTick = false;
                    Iterator<ReturningBlock> iterator = active.iterator();
                    while (iterator.hasNext()) {
                        ReturningBlock returning = iterator.next();
                        double progress = Math.min(1,
                            (tick - returning.started + 1d) / flightDuration);
                        Location location = reversePosition(returning.saved, progress);
                        returning.display.teleport(location);
                        trail(location);
                        if (progress < 1) {
                            continue;
                        }
                        removeDisplay(returning.display);
                        iterator.remove();
                        if (returning.saved.block().getType().isAir()) {
                            returning.saved.restore();
                            restored++;
                            placedThisTick = true;
                            impact(returning.saved.target());
                        }
                    }
                    if (placedThisTick) {
                        playSound(crater.center, "effects.block-sound",
                            Sound.BLOCK_AMETHYST_BLOCK_PLACE, .25f, 1.5f);
                    }

                    if (next >= repairable.size() && active.isEmpty()) {
                        finishRestoration(crater, player, restored);
                        cancel();
                        return;
                    }
                    tick++;
                } catch (RuntimeException exception) {
                    active.forEach(block -> removeDisplay(block.display));
                    repairable.forEach(saved -> {
                        if (saved.block().getType().isAir()) {
                            saved.restore();
                        }
                    });
                    craters.remove(crater);
                    plugin.getLogger().log(Level.WARNING,
                        "A creeper crater animation failed; its remaining blocks were restored immediately.",
                        exception);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0, 1);
    }

    private void finishRestoration(Crater crater, Player player, int restored) {
        craters.remove(crater);
        if (settings.getBoolean("effects.particles.enabled", true)) {
            crater.center.getWorld().spawnParticle(ConfiguredEffect.particle(plugin, settings,
                    "effects.particles.completion-name", Particle.REVERSE_PORTAL), crater.center,
                Math.max(0, settings.getInt("effects.particles.completion-count", 80)),
                2.5, 1.8, 2.5, .12);
        }
        playSound(crater.center, "effects.completion-sound",
            Sound.BLOCK_BEACON_POWER_SELECT, 1, 1.65f);
        if (player != null && player.isOnline()) {
            plugin.messageActions().run(player, settings, "messages.restored", "actionbar",
                "<white>Restored <#f72a4c>%blocks%</#f72a4c> blocks.</white>",
                Placeholder.unparsed("blocks", Integer.toString(restored)));
        }
    }

    private Crater closestCrater(Location impact) {
        double radius = Math.max(0, settings.getDouble("activation-radius", 6));
        double radiusSquared = radius * radius;
        return craters.stream()
            .filter(crater -> !crater.restoring && crater.center.getWorld().equals(impact.getWorld()))
            .filter(crater -> crater.center.distanceSquared(impact) <= radiusSquared)
            .min(Comparator.comparingDouble(crater -> crater.center.distanceSquared(impact)))
            .orElse(null);
    }

    private BlockDisplay spawnDisplay(Location location, BlockData data) {
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4,
            location.getBlockZ() >> 4)) {
            return null;
        }
        BlockDisplay display = location.getWorld().spawn(location, BlockDisplay.class, entity -> {
            entity.setBlock(data);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setViewRange(1.5f);
            entity.setShadowRadius(.35f);
            entity.setShadowStrength(.5f);
        });
        displays.add(display.getUniqueId());
        return display;
    }

    private void removeDisplay(BlockDisplay display) {
        displays.remove(display.getUniqueId());
        display.remove();
    }

    private Location debrisEnd(SavedBlock saved) {
        double duration = Math.max(1, settings.getInt("explosion-debris.duration-ticks", 24));
        return saved.target().add(
            saved.velocity.getX() * duration,
            saved.velocity.getY() * duration - .0065 * duration * duration,
            saved.velocity.getZ() * duration);
    }

    private Location reversePosition(SavedBlock saved, double progress) {
        Location start = debrisEnd(saved);
        Location target = saved.target();
        double eased = progress * progress * (3 - 2 * progress);
        return start.add(
            (target.getX() - start.getX()) * eased,
            (target.getY() - start.getY()) * eased + Math.sin(Math.PI * progress) * 1.15,
            (target.getZ() - start.getZ()) * eased);
    }

    private void trail(Location location) {
        if (!settings.getBoolean("effects.particles.enabled", true)) {
            return;
        }
        location.getWorld().spawnParticle(ConfiguredEffect.particle(plugin, settings,
                "effects.particles.trail-name", Particle.END_ROD), location,
            Math.max(0, settings.getInt("effects.particles.trail-count", 2)),
            .12, .12, .12, .01);
    }

    private void impact(Location location) {
        if (!settings.getBoolean("effects.particles.enabled", true)) {
            return;
        }
        location.getWorld().spawnParticle(ConfiguredEffect.particle(plugin, settings,
                "effects.particles.impact-name", Particle.ELECTRIC_SPARK),
            location.clone().add(.5, .5, .5),
            Math.max(0, settings.getInt("effects.particles.impact-count", 4)),
            .35, .35, .35, .05);
    }

    private ItemStack core() {
        ItemStack item = new ItemStack(coreMaterial());
        item.editMeta(meta -> {
            meta.displayName(text("core.name",
                "<#f72a4c><bold>Restoration Core</bold></#f72a4c>"));
            List<String> lore = settings.getStringList("core.lore");
            if (lore.isEmpty()) {
                lore = List.of("<white>Right-click to throw.</white>",
                    "<white>Repairs a nearby creeper crater.</white>");
            }
            meta.lore(lore.stream().map(MM::deserialize).toList());
            meta.setEnchantmentGlintOverride(
                settings.getBoolean("core.enchanted-glint", true));
            meta.getPersistentDataContainer().set(coreKey,
                PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private boolean isCore(ItemStack item) {
        return item != null && item.getType() == coreMaterial() && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(
                coreKey, PersistentDataType.BYTE);
    }

    private Material coreMaterial() {
        String configured = settings.getString("core.material", "echo_shard");
        Material material = Material.matchMaterial(configured == null ? "" : configured);
        if (material == null || !material.isItem() || material.isAir()) {
            return Material.ECHO_SHARD;
        }
        return material;
    }

    private static void consume(Player player, EquipmentSlot hand, ItemStack held) {
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
            return;
        }
        if (hand == EquipmentSlot.HAND) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        }
    }

    private void playSound(Location location, String path, Sound fallback,
                           float volume, float pitch) {
        if (!settings.getBoolean(path + ".enabled", true)) {
            return;
        }
        location.getWorld().playSound(location,
            ConfiguredEffect.sound(plugin, settings, path + ".name", fallback),
            (float) Math.max(0, settings.getDouble(path + ".volume", volume)),
            (float) Math.max(.01, settings.getDouble(path + ".pitch", pitch)));
    }

    private Component text(String path, String fallback, TagResolver... placeholders) {
        return MM.deserialize(settings.getString(path, fallback), placeholders);
    }

    private void message(CommandSender recipient, String path, String fallback,
                         TagResolver... placeholders) {
        plugin.messageActions().run(recipient, settings, path, fallback, placeholders);
    }

    private void usage(CommandSender sender) {
        message(sender, "messages.usage",
            "<white>Usage: /restorationcore [player] [amount]</white>");
    }

    static int startTick(int index, int blocks, int totalDuration, int flightDuration) {
        if (blocks <= 1) {
            return 0;
        }
        return (int) Math.round(index * (double) Math.max(0, totalDuration - flightDuration)
            / (blocks - 1));
    }

    static boolean shouldEscrowContents(boolean restoreContainers,
                                        boolean restoreContainerContents,
                                        boolean savedContainer) {
        return restoreContainers && restoreContainerContents && savedContainer;
    }

    private record SavedBlock(World world, int x, int y, int z, BlockData data,
                              BlockState state, Vector velocity) {
        private Block block() {
            return world.getBlockAt(x, y, z);
        }

        private Location target() {
            return new Location(world, x, y, z);
        }

        private void restore() {
            if (state == null || !state.update(true, false)) {
                block().setBlockData(data, false);
            }
        }
    }

    private static final class Crater {
        private final Location center;
        private final List<SavedBlock> blocks;
        private boolean restoring;

        private Crater(Location center, List<SavedBlock> blocks) {
            this.center = center;
            this.blocks = blocks;
        }
    }

    private record MovingBlock(BlockDisplay display, SavedBlock saved) {
    }

    private record ReturningBlock(BlockDisplay display, SavedBlock saved, int started) {
    }
}
