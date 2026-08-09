package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class HologramModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private static final List<String> ROOT_COMMANDS =
        List.of("help", "list", "nearby", "create", "remove", "copy", "info", "edit");
    private static final List<String> EDIT_COMMANDS = List.of(
        "movehere", "moveto", "rotate", "rotatepitch", "translate", "visibilitydistance",
        "visibility", "scale", "billboard", "shadowstrength", "shadowradius", "setline",
        "addline", "removeline", "insertbefore", "insertafter", "background", "textshadow",
        "brightness", "textalignment", "item", "block", "animation");

    private final RivetPlugin plugin;
    private final NamespacedKey hologramKey;
    private final File file;
    private final Map<String, Hologram> holograms = new HashMap<>();
    private long animationTick;

    HologramModule(RivetPlugin plugin) {
        this.plugin = plugin;
        hologramKey = new NamespacedKey(plugin, "hologram");
        file = plugin.dataFile("holograms");
        load();
        plugin.getServer().getScheduler().runTask(plugin, this::ensureLoaded);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickAnimations, 1, 2);
    }

    boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rivet.holograms")) {
            send(sender, "<white>You do not have permission to manage holograms.");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(sender);
            case "list" -> list(sender);
            case "nearby" -> nearby(sender, args);
            case "create" -> create(sender, args);
            case "remove" -> remove(sender, args);
            case "copy" -> copy(sender, args);
            case "info" -> info(sender, args);
            case "edit" -> edit(sender, args);
            default -> {
                send(sender, "<white>Unknown subcommand. Use <#f72a4c>/hologram help</#f72a4c>.");
                yield true;
            }
        };
    }

    List<String> completions(String[] args) {
        if (args.length == 1) {
            return ROOT_COMMANDS;
        }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (root) {
                case "create" -> List.of("text", "item", "block");
                case "remove", "copy", "info", "edit" -> names();
                case "nearby" -> List.of("10", "25", "50", "100");
                default -> List.of();
            };
        }
        if (root.equals("edit") && args.length == 3) {
            return EDIT_COMMANDS;
        }
        if (root.equals("edit") && args.length == 4) {
            return switch (args[2].toLowerCase(Locale.ROOT)) {
                case "visibility" -> List.of("all", "manual", "permission_needed");
                case "billboard" -> List.of("center", "fixed", "vertical", "horizontal");
                case "background" -> List.of("none", "#80000000", "#80FFFFFF");
                case "textshadow" -> List.of("true", "false");
                case "brightness" -> List.of("block", "sky");
                case "textalignment" -> List.of("center", "left", "right");
                case "animation" -> List.of("none", "float", "spin", "pulse", "float_spin");
                case "block" -> Arrays.stream(Material.values()).filter(Material::isBlock)
                    .map(material -> material.name().toLowerCase(Locale.ROOT)).sorted().toList();
                default -> List.of();
            };
        }
        if (root.equals("edit") && args.length == 5
            && args[2].equalsIgnoreCase("brightness")) {
            return List.of("0", "5", "10", "15");
        }
        return List.of();
    }

    private boolean help(CommandSender sender) {
        sender.sendMessage(MM.deserialize("""
            <#f72a4c><bold>Hologram commands</bold></#f72a4c>
            <white>/hologram list</white> <white>— list holograms
            <white>/hologram nearby <range></white> <white>— list nearby holograms
            <white>/hologram create [text|item|block] <name></white>
            <white>/hologram remove <name></white>
            <white>/hologram copy <name> <new-name></white>
            <white>/hologram info <name></white>
            <white>/hologram edit <name> <operation> ...</white>
            <white>Move:</white> movehere, moveto, rotate, rotatepitch, translate
            <white>Display:</white> scale, billboard, visibilitydistance, visibility, shadowstrength, shadowradius, animation
            <white>Animation presets:</white> none, float, spin, pulse, float_spin
            <white>Text:</white> setline, addline, removeline, insertbefore, insertafter, background, textshadow, brightness, textalignment
            <white>Other:</white> item, block
            """));
        return true;
    }

    private boolean list(CommandSender sender) {
        if (holograms.isEmpty()) {
            send(sender, "<white>No holograms exist.");
            return true;
        }
        send(sender, "<#f72a4c>Holograms:</#f72a4c> <#f72a4c>" + String.join(", ", names()));
        return true;
    }

    private boolean nearby(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "<white>This subcommand is only available to players.");
            return true;
        }
        if (args.length != 2) {
            send(sender, "<white>Usage: /hologram nearby <range>");
            return true;
        }
        Double range = number(args[1]);
        if (range == null || range < 0) {
            send(sender, "<white>Range must be a positive number.");
            return true;
        }
        List<String> nearby = holograms.values().stream()
            .filter(hologram -> hologram.world.equals(player.getWorld().getUID()))
            .filter(hologram -> hologram.location(player.getWorld()).distanceSquared(player.getLocation())
                <= range * range)
            .map(hologram -> hologram.name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        send(sender, nearby.isEmpty() ? "<white>No holograms are nearby."
            : "<#f72a4c>Nearby holograms:</#f72a4c> <#f72a4c>" + String.join(", ", nearby));
        return true;
    }

    private boolean create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "<white>This subcommand is only available to players.");
            return true;
        }
        Type type;
        String name;
        if (args.length == 2) {
            type = Type.TEXT;
            name = args[1];
        } else if (args.length == 3) {
            type = enumValue(Type.class, args[1]);
            name = args[2];
        } else {
            send(sender, "<white>Usage: /hologram create [text|item|block] <name>");
            return true;
        }
        if (type == null || !validName(name)) {
            send(sender, "<white>Use a valid type and a 1–32 character name containing letters, numbers, _ or -.");
            return true;
        }
        String key = key(name);
        if (holograms.containsKey(key)) {
            send(sender, "<white>A hologram named <#f72a4c>" + name + "</#f72a4c> already exists.");
            return true;
        }

        Location location = player.getEyeLocation();
        Hologram hologram = new Hologram(name, type, location);
        if (type == Type.ITEM) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held.getType().isAir()) {
                send(sender, "<white>Hold the item to display first.");
                return true;
            }
            hologram.item = held.clone();
            hologram.item.setAmount(1);
        } else if (type == Type.BLOCK) {
            Material held = player.getInventory().getItemInMainHand().getType();
            hologram.block = held.isBlock() && !held.isAir() ? held : Material.STONE;
        }

        holograms.put(key, hologram);
        if (!save(sender)) {
            holograms.remove(key);
            return true;
        }
        spawn(hologram);
        send(sender, "<white>Created " + type.name().toLowerCase(Locale.ROOT)
            + " hologram <#f72a4c>" + name + "</#f72a4c>.");
        return true;
    }

    private boolean remove(CommandSender sender, String[] args) {
        Hologram hologram = require(sender, args, 2, "/hologram remove <name>");
        if (hologram == null) {
            return true;
        }
        holograms.remove(key(hologram.name));
        if (!save(sender)) {
            holograms.put(key(hologram.name), hologram);
            return true;
        }
        removeDisplays(hologram);
        send(sender, "<white>Removed hologram <#f72a4c>" + hologram.name + "</#f72a4c>.");
        return true;
    }

    private boolean copy(CommandSender sender, String[] args) {
        if (args.length != 3 || !validName(args[2])) {
            send(sender, "<white>Usage: /hologram copy <name> <new-name>");
            return true;
        }
        Hologram source = holograms.get(key(args[1]));
        if (source == null) {
            send(sender, "<white>That hologram does not exist.");
            return true;
        }
        if (holograms.containsKey(key(args[2]))) {
            send(sender, "<white>A hologram with that name already exists.");
            return true;
        }
        Hologram copy = source.copy(args[2]);
        holograms.put(key(copy.name), copy);
        if (!save(sender)) {
            holograms.remove(key(copy.name));
            return true;
        }
        spawn(copy);
        send(sender, "<white>Copied <#f72a4c>" + source.name + "</#f72a4c> to <#f72a4c>" + copy.name + "</#f72a4c>.");
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        Hologram hologram = require(sender, args, 2, "/hologram info <name>");
        if (hologram == null) {
            return true;
        }
        World world = plugin.getServer().getWorld(hologram.world);
        sender.sendMessage(MM.deserialize("<#f72a4c><bold>" + hologram.name + "</bold></#f72a4c>"
            + "\n<white>Type:</white> <#f72a4c>" + hologram.type.name().toLowerCase(Locale.ROOT)
            + "\n<white>World:</white> <#f72a4c>" + (world == null ? hologram.world : world.getName())
            + "\n<white>Position:</white> <#f72a4c>" + decimal(hologram.x) + ", " + decimal(hologram.y)
            + ", " + decimal(hologram.z)
            + "\n<white>Rotation:</white> <#f72a4c>" + decimal(hologram.yaw) + " yaw, "
            + decimal(hologram.pitch) + " pitch"
            + "\n<white>Scale:</white> <#f72a4c>" + decimal(hologram.scale)
            + "\n<white>Animation:</white> <#f72a4c>" + hologram.animation.name().toLowerCase(Locale.ROOT)
            + "\n<white>Billboard:</white> <#f72a4c>" + hologram.billboard.name().toLowerCase(Locale.ROOT)
            + "\n<white>Visibility:</white> <#f72a4c>" + hologram.visibility.name().toLowerCase(Locale.ROOT)));
        return true;
    }

    private boolean edit(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "<white>Usage: /hologram edit <name> <operation> ...");
            return true;
        }
        Hologram original = holograms.get(key(args[1]));
        if (original == null) {
            send(sender, "<white>That hologram does not exist.");
            return true;
        }
        Hologram changed = original.copy(original.name);
        String operation = args[2].toLowerCase(Locale.ROOT);
        String error = applyEdit(sender, changed, operation, args);
        if (error != null) {
            send(sender, "<white>" + error);
            return true;
        }

        holograms.put(key(changed.name), changed);
        if (!save(sender)) {
            holograms.put(key(original.name), original);
            return true;
        }
        removeDisplays(original);
        spawn(changed);
        send(sender, "<white>Updated hologram <#f72a4c>" + changed.name + "</#f72a4c>.");
        return true;
    }

    private String applyEdit(CommandSender sender, Hologram hologram, String operation, String[] args) {
        return switch (operation) {
            case "movehere", "position" -> {
                if (args.length != 3 || !(sender instanceof Player player)) {
                    yield "Usage: /hologram edit <name> movehere";
                }
                hologram.setLocation(player.getEyeLocation());
                yield null;
            }
            case "moveto" -> {
                if (args.length < 6 || args.length > 8) {
                    yield "Usage: /hologram edit <name> moveto <x> <y> <z> [yaw] [pitch]";
                }
                Double x = number(args[3]);
                Double y = number(args[4]);
                Double z = number(args[5]);
                Double yaw = args.length >= 7 ? number(args[6]) : (double) hologram.yaw;
                Double pitch = args.length == 8 ? number(args[7]) : (double) hologram.pitch;
                if (x == null || y == null || z == null || yaw == null || pitch == null
                    || !RivetPlugin.validCoordinates(x, y, z, yaw.floatValue(), pitch.floatValue())) {
                    yield "Coordinates and rotation must be finite numbers.";
                }
                hologram.x = x;
                hologram.y = y;
                hologram.z = z;
                hologram.yaw = yaw.floatValue();
                hologram.pitch = pitch.floatValue();
                yield null;
            }
            case "rotate", "rotatepitch" -> {
                Double degrees = args.length == 4 ? number(args[3]) : null;
                if (degrees == null) {
                    yield "Usage: /hologram edit <name> " + operation + " <degrees>";
                }
                if (operation.equals("rotate")) {
                    hologram.yaw += degrees.floatValue();
                } else {
                    hologram.pitch += degrees.floatValue();
                }
                yield null;
            }
            case "translate" -> {
                Double x = args.length == 6 ? number(args[3]) : null;
                Double y = args.length == 6 ? number(args[4]) : null;
                Double z = args.length == 6 ? number(args[5]) : null;
                if (x == null || y == null || z == null) {
                    yield "Usage: /hologram edit <name> translate <x> <y> <z>";
                }
                hologram.x += x;
                hologram.y += y;
                hologram.z += z;
                yield null;
            }
            case "visibilitydistance" -> {
                Double distance = args.length == 4 ? number(args[3]) : null;
                if (distance == null || (distance != -1 && distance <= 0)) {
                    yield "Visibility distance must be -1 or a positive number.";
                }
                hologram.visibilityDistance = distance.floatValue();
                yield null;
            }
            case "visibility" -> {
                Visibility visibility = args.length == 4 ? enumValue(Visibility.class, args[3]) : null;
                if (visibility == null) {
                    yield "Visibility must be all, manual or permission_needed.";
                }
                hologram.visibility = visibility;
                yield null;
            }
            case "scale" -> {
                Double scale = args.length == 4 ? number(args[3]) : null;
                if (scale == null || scale <= 0 || scale > 100) {
                    yield "Scale must be greater than 0 and no more than 100.";
                }
                hologram.scale = scale.floatValue();
                yield null;
            }
            case "animation" -> {
                Animation animation = args.length == 4 ? enumValue(Animation.class, args[3]) : null;
                if (animation == null) {
                    yield "Animation must be none, float, spin, pulse or float_spin.";
                }
                hologram.animation = animation;
                yield null;
            }
            case "billboard" -> {
                Display.Billboard billboard =
                    args.length == 4 ? enumValue(Display.Billboard.class, args[3]) : null;
                if (billboard == null) {
                    yield "Billboard must be center, fixed, vertical or horizontal.";
                }
                hologram.billboard = billboard;
                yield null;
            }
            case "shadowstrength", "shadowradius" -> {
                Double value = args.length == 4 ? number(args[3]) : null;
                if (value == null || value < 0 || operation.equals("shadowstrength") && value > 1) {
                    yield operation.equals("shadowstrength")
                        ? "Shadow strength must be between 0 and 1."
                        : "Shadow radius must be a non-negative number.";
                }
                if (operation.equals("shadowstrength")) {
                    hologram.shadowStrength = value.floatValue();
                } else {
                    hologram.shadowRadius = value.floatValue();
                }
                yield null;
            }
            case "setline", "insertbefore", "insertafter" -> editLine(hologram, operation, args);
            case "addline" -> {
                if (hologram.type != Type.TEXT || args.length < 4) {
                    yield "Usage for text holograms: /hologram edit <name> addline <MiniMessage text>";
                }
                hologram.lines.add(join(args, 3));
                yield null;
            }
            case "removeline" -> {
                Integer line = hologram.type == Type.TEXT && args.length == 4
                    ? line(args[3], hologram.lines.size()) : null;
                if (line == null) {
                    yield "Usage: /hologram edit <name> removeline <line>";
                }
                hologram.lines.remove((int) line);
                yield null;
            }
            case "background" -> {
                if (hologram.type != Type.TEXT || args.length != 4) {
                    yield "Usage: /hologram edit <name> background <#RRGGBB|#AARRGGBB|color|none>";
                }
                try {
                    hologram.background = parseBackground(args[3]);
                    yield null;
                } catch (IllegalArgumentException exception) {
                    yield "Use a named color, #RRGGBB, #AARRGGBB or none.";
                }
            }
            case "textshadow" -> {
                Boolean enabled = hologram.type == Type.TEXT && args.length == 4
                    ? bool(args[3]) : null;
                if (enabled == null) {
                    yield "Usage: /hologram edit <name> textshadow <true|false>";
                }
                hologram.textShadow = enabled;
                yield null;
            }
            case "brightness" -> {
                Integer brightness = args.length == 5 ? integer(args[4]) : null;
                if (brightness == null || brightness < 0 || brightness > 15
                    || !(args[3].equalsIgnoreCase("block") || args[3].equalsIgnoreCase("sky"))) {
                    yield "Usage: /hologram edit <name> brightness <block|sky> <0-15>";
                }
                if (args[3].equalsIgnoreCase("block")) {
                    hologram.blockLight = brightness;
                } else {
                    hologram.skyLight = brightness;
                }
                yield null;
            }
            case "textalignment" -> {
                TextDisplay.TextAlignment alignment = hologram.type == Type.TEXT && args.length == 4
                    ? enumValue(TextDisplay.TextAlignment.class, args[3]) : null;
                if (alignment == null) {
                    yield "Text alignment must be center, left or right.";
                }
                hologram.alignment = alignment;
                yield null;
            }
            case "item" -> {
                if (hologram.type != Type.ITEM || args.length != 3 || !(sender instanceof Player player)
                    || player.getInventory().getItemInMainHand().getType().isAir()) {
                    yield "Hold an item, then use /hologram edit <name> item.";
                }
                hologram.item = player.getInventory().getItemInMainHand().clone();
                hologram.item.setAmount(1);
                yield null;
            }
            case "block" -> {
                Material material = hologram.type == Type.BLOCK && args.length == 4
                    ? Material.matchMaterial(args[3]) : null;
                if (material == null || !material.isBlock() || material.isAir()) {
                    yield "Usage: /hologram edit <name> block <block type>";
                }
                hologram.block = material;
                yield null;
            }
            default -> "Unknown edit operation. Use /hologram help.";
        };
    }

    private String editLine(Hologram hologram, String operation, String[] args) {
        if (hologram.type != Type.TEXT || args.length < 5) {
            return "Usage: /hologram edit <name> " + operation + " <line> <MiniMessage text>";
        }
        Integer line = line(args[3], hologram.lines.size());
        if (line == null) {
            return "Line must refer to an existing 1-based line number.";
        }
        int target = operation.equals("insertafter") ? line + 1 : line;
        if (operation.equals("setline")) {
            hologram.lines.set(line, join(args, 4));
        } else {
            hologram.lines.add(target, join(args, 4));
        }
        return null;
    }

    private Hologram require(CommandSender sender, String[] args, int length, String usage) {
        if (args.length != length) {
            send(sender, "<white>Usage: " + usage);
            return null;
        }
        Hologram hologram = holograms.get(key(args[1]));
        if (hologram == null) {
            send(sender, "<white>That hologram does not exist.");
        }
        return hologram;
    }

    private void spawn(Hologram hologram) {
        World world = plugin.getServer().getWorld(hologram.world);
        if (world == null || !world.isChunkLoaded(hologram.blockX() >> 4, hologram.blockZ() >> 4)) {
            return;
        }
        Location location = hologram.location(world);
        Display display = switch (hologram.type) {
            case TEXT -> world.spawn(location, TextDisplay.class, entity -> {
                entity.text(renderText(hologram.lines));
                entity.setBackgroundColor(Color.fromARGB(hologram.background));
                entity.setShadowed(hologram.textShadow);
                entity.setAlignment(hologram.alignment);
                entity.setLineWidth(240);
            });
            case ITEM -> world.spawn(location, ItemDisplay.class, entity -> {
                entity.setItemStack(hologram.item.clone());
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            });
            case BLOCK -> world.spawn(location, BlockDisplay.class,
                entity -> entity.setBlock(hologram.block.createBlockData()));
        };
        configure(display, hologram);
    }

    private void configure(Display display, Hologram hologram) {
        display.setPersistent(true);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.setBillboard(hologram.billboard);
        display.setViewRange(hologram.visibilityDistance == -1 ? 1
            : Math.max(.01f, hologram.visibilityDistance / 64f));
        display.setShadowStrength(hologram.shadowStrength);
        display.setShadowRadius(hologram.shadowRadius);
        if (hologram.blockLight >= 0 || hologram.skyLight >= 0) {
            display.setBrightness(new Display.Brightness(
                hologram.blockLight < 0 ? 15 : hologram.blockLight,
                hologram.skyLight < 0 ? 15 : hologram.skyLight));
        }
        Transformation transformation = display.getTransformation();
        transformation.getScale().set(hologram.scale);
        display.setTransformation(transformation);
        display.getPersistentDataContainer().set(
            hologramKey, PersistentDataType.STRING, key(hologram.name));
        applyVisibility(display, hologram);
    }

    private void applyVisibility(Display display, Hologram hologram) {
        plugin.getServer().getOnlinePlayers().forEach(player -> applyVisibility(player, display, hologram));
    }

    private void applyVisibility(Player player, Display display, Hologram hologram) {
        boolean visible = hologram.visibility == Visibility.ALL
            || hologram.visibility == Visibility.MANUAL
            || hologram.visibility == Visibility.PERMISSION_NEEDED
            && player.hasPermission("rivet.holograms.view." + key(hologram.name));
        if (visible) {
            player.showEntity(plugin, display);
        } else {
            player.hideEntity(plugin, display);
        }
    }

    private void tickAnimations() {
        animationTick += 2;
        double phase = animationTick / 20d;
        holograms.values().stream().filter(hologram -> hologram.animation != Animation.NONE)
            .forEach(hologram -> displays(hologram)
                .forEach(display -> animate(display, hologram, phase)));
    }

    private void animate(Display display, Hologram hologram, double phase) {
        float[] values = animationValues(hologram.animation, phase, hologram.scale);
        float bob = values[0];
        float angle = values[1];
        float scale = values[2];
        float x = 0;
        float y = bob;
        float z = 0;
        if (display instanceof BlockDisplay
            && (hologram.animation.spins() || hologram.animation == Animation.PULSE)) {
            float half = scale / 2;
            float sin = (float) Math.sin(angle);
            float cos = (float) Math.cos(angle);
            x = .5f - (cos * half + sin * half);
            y += .5f - half;
            z = .5f - (-sin * half + cos * half);
        }
        Transformation transformation = display.getTransformation();
        transformation.getTranslation().set(x, y, z);
        transformation.getLeftRotation().set(new AxisAngle4f(angle, 0, 1, 0));
        transformation.getScale().set(scale);
        transformation.getRightRotation().identity();
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(2);
        display.setTransformation(transformation);
    }

    static float[] animationValues(Animation animation, double phase, float scale) {
        float bob = animation.floats() ? (float) (Math.sin(phase * 2.4) * .22) : 0;
        float angle = animation.spins()
            ? (float) ((phase * 1.5) % (Math.PI * 2)) : 0;
        float animatedScale = animation == Animation.PULSE
            ? scale * (1 + (float) Math.sin(phase * 2.4) * .08f) : scale;
        return new float[]{bob, angle, animatedScale};
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            String name = entity.getPersistentDataContainer().get(
                hologramKey, PersistentDataType.STRING);
            Hologram hologram = name == null ? null : holograms.get(name);
            if (name != null && (hologram == null || !hologram.world.equals(event.getWorld().getUID())
                || hologram.blockX() >> 4 != event.getChunk().getX()
                || hologram.blockZ() >> 4 != event.getChunk().getZ())) {
                entity.remove();
            }
        }
        List<Hologram> loaded = holograms.values().stream()
            .filter(hologram -> hologram.world.equals(event.getWorld().getUID())
                && hologram.blockX() >> 4 == event.getChunk().getX()
                && hologram.blockZ() >> 4 == event.getChunk().getZ())
            .toList();
        if (!loaded.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> loaded.forEach(this::ensure));
        }
    }

    void refresh(Player player) {
        holograms.values().forEach(hologram ->
            displays(hologram).forEach(display -> applyVisibility(player, display, hologram)));
    }

    private void ensureLoaded() {
        plugin.getServer().getWorlds().forEach(world -> world.getEntities().forEach(entity -> {
            String name = entity.getPersistentDataContainer().get(
                hologramKey, PersistentDataType.STRING);
            Hologram hologram = name == null ? null : holograms.get(name);
            if (name != null && (hologram == null || !hologram.world.equals(world.getUID())
                || hologram.blockX() >> 4 != entity.getChunk().getX()
                || hologram.blockZ() >> 4 != entity.getChunk().getZ())) {
                entity.remove();
            }
        }));
        holograms.values().forEach(hologram -> {
            World world = plugin.getServer().getWorld(hologram.world);
            if (world != null && world.isChunkLoaded(hologram.blockX() >> 4, hologram.blockZ() >> 4)) {
                ensure(hologram);
            }
        });
    }

    private void ensure(Hologram hologram) {
        removeDisplays(hologram);
        spawn(hologram);
    }

    private List<Display> displays(Hologram hologram) {
        World world = plugin.getServer().getWorld(hologram.world);
        if (world == null || !world.isChunkLoaded(hologram.blockX() >> 4, hologram.blockZ() >> 4)) {
            return List.of();
        }
        String value = key(hologram.name);
        List<Display> displays = new ArrayList<>();
        for (Entity entity : world.getChunkAt(hologram.blockX() >> 4, hologram.blockZ() >> 4).getEntities()) {
            if (entity instanceof Display display && value.equals(entity.getPersistentDataContainer()
                .get(hologramKey, PersistentDataType.STRING))) {
                displays.add(display);
            }
        }
        return displays;
    }

    private void removeDisplays(Hologram hologram) {
        displays(hologram).forEach(Entity::remove);
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            ConfigurationSection root = yaml.getConfigurationSection("holograms");
            if (root == null) {
                return;
            }
            for (String name : root.getKeys(false)) {
                try {
                    Hologram hologram = read(yaml, "holograms." + name, name);
                    holograms.put(key(hologram.name), hologram);
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Skipped invalid hologram " + name + ": " + exception.getMessage());
                }
            }
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Could not load data/holograms.yml: " + exception.getMessage());
        }
    }

    private Hologram read(YamlConfiguration yaml, String path, String name) {
        UUID world = UUID.fromString(required(yaml.getString(path + ".world")));
        Type type = Type.valueOf(required(yaml.getString(path + ".type")));
        Hologram hologram = new Hologram(name, type);
        hologram.world = world;
        hologram.x = yaml.getDouble(path + ".x");
        hologram.y = yaml.getDouble(path + ".y");
        hologram.z = yaml.getDouble(path + ".z");
        hologram.yaw = (float) yaml.getDouble(path + ".yaw");
        hologram.pitch = (float) yaml.getDouble(path + ".pitch");
        hologram.scale = (float) yaml.getDouble(path + ".scale", 1);
        hologram.animation = Animation.valueOf(yaml.getString(path + ".animation", "NONE"));
        hologram.billboard = Display.Billboard.valueOf(yaml.getString(path + ".billboard", "CENTER"));
        hologram.visibilityDistance = (float) yaml.getDouble(path + ".visibility-distance", -1);
        hologram.visibility = Visibility.valueOf(yaml.getString(path + ".visibility", "ALL"));
        hologram.shadowStrength = (float) yaml.getDouble(path + ".shadow-strength", 1);
        hologram.shadowRadius = (float) yaml.getDouble(path + ".shadow-radius", 0);
        hologram.lines = new ArrayList<>(yaml.getStringList(path + ".lines"));
        if (hologram.lines.isEmpty()) {
            hologram.lines.add("<#f72a4c>" + name);
        }
        hologram.background = (int) yaml.getLong(path + ".background", 0);
        hologram.textShadow = yaml.getBoolean(path + ".text-shadow", true);
        hologram.alignment = TextDisplay.TextAlignment.valueOf(
            yaml.getString(path + ".text-alignment", "CENTER"));
        hologram.blockLight = yaml.getInt(path + ".block-light", -1);
        hologram.skyLight = yaml.getInt(path + ".sky-light", -1);
        ItemStack item = yaml.getItemStack(path + ".item");
        hologram.item = item == null ? new ItemStack(Material.STONE) : item;
        Material block = Material.matchMaterial(yaml.getString(path + ".block", "stone"));
        hologram.block = block == null || !block.isBlock() ? Material.STONE : block;
        return hologram;
    }

    private boolean save(CommandSender sender) {
        YamlConfiguration yaml = new YamlConfiguration();
        holograms.values().stream().sorted(Comparator.comparing(hologram -> hologram.name))
            .forEach(hologram -> write(yaml, hologram));
        try {
            Files.createDirectories(file.toPath().getParent());
            File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
            yaml.save(temporary);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data/holograms.yml: " + exception.getMessage());
            send(sender, "<white>The hologram change could not be saved.");
            return false;
        }
    }

    private void write(YamlConfiguration yaml, Hologram hologram) {
        String path = "holograms." + hologram.name;
        yaml.set(path + ".type", hologram.type.name());
        yaml.set(path + ".world", hologram.world.toString());
        yaml.set(path + ".x", hologram.x);
        yaml.set(path + ".y", hologram.y);
        yaml.set(path + ".z", hologram.z);
        yaml.set(path + ".yaw", hologram.yaw);
        yaml.set(path + ".pitch", hologram.pitch);
        yaml.set(path + ".scale", hologram.scale);
        yaml.set(path + ".animation", hologram.animation.name());
        yaml.set(path + ".billboard", hologram.billboard.name());
        yaml.set(path + ".visibility-distance", hologram.visibilityDistance);
        yaml.set(path + ".visibility", hologram.visibility.name());
        yaml.set(path + ".shadow-strength", hologram.shadowStrength);
        yaml.set(path + ".shadow-radius", hologram.shadowRadius);
        yaml.set(path + ".lines", hologram.lines);
        yaml.set(path + ".background", Integer.toUnsignedLong(hologram.background));
        yaml.set(path + ".text-shadow", hologram.textShadow);
        yaml.set(path + ".text-alignment", hologram.alignment.name());
        yaml.set(path + ".block-light", hologram.blockLight);
        yaml.set(path + ".sky-light", hologram.skyLight);
        yaml.set(path + ".item", hologram.item);
        yaml.set(path + ".block", hologram.block.name());
    }

    private List<String> names() {
        return holograms.values().stream().map(hologram -> hologram.name)
            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    static Component renderText(List<String> lines) {
        Component result = Component.empty();
        for (int index = 0; index < lines.size(); index++) {
            if (index != 0) {
                result = result.append(Component.newline());
            }
            result = result.append(MM.deserialize(lines.get(index)));
        }
        return result;
    }

    static int parseBackground(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.equals("none") || normalized.equals("delete") || normalized.equals("transparent")) {
            return 0;
        }
        NamedTextColor named = NamedTextColor.NAMES.value(normalized);
        if (named != null) {
            return 0x80000000 | named.value();
        }
        String hex = normalized.startsWith("#") ? normalized.substring(1) : normalized;
        if (!(hex.length() == 6 || hex.length() == 8) || !hex.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException("invalid color");
        }
        long parsed = Long.parseUnsignedLong(hex, 16);
        return (int) (hex.length() == 6 ? 0x80000000L | parsed : parsed);
    }

    static boolean validName(String name) {
        return name != null && RivetPlugin.validWorldName(name);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String join(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    private static Integer line(String value, int size) {
        Integer parsed = integer(value);
        return parsed != null && parsed >= 1 && parsed <= size ? parsed - 1 : null;
    }

    private static Double number(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Boolean bool(String value) {
        return value.equalsIgnoreCase("true") ? true
            : value.equalsIgnoreCase("false") ? false : null;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing value");
        }
        return value;
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(MM.deserialize(message));
    }

    private enum Type {
        TEXT, ITEM, BLOCK
    }

    private enum Visibility {
        ALL, MANUAL, PERMISSION_NEEDED
    }

    enum Animation {
        NONE, FLOAT, SPIN, PULSE, FLOAT_SPIN;

        private boolean floats() {
            return this == FLOAT || this == FLOAT_SPIN;
        }

        private boolean spins() {
            return this == SPIN || this == FLOAT_SPIN;
        }
    }

    private static final class Hologram {
        private final String name;
        private final Type type;
        private UUID world;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        private float scale = 1;
        private Animation animation = Animation.NONE;
        private Display.Billboard billboard = Display.Billboard.CENTER;
        private float visibilityDistance = -1;
        private Visibility visibility = Visibility.ALL;
        private float shadowStrength = 1;
        private float shadowRadius;
        private List<String> lines;
        private int background;
        private boolean textShadow = true;
        private TextDisplay.TextAlignment alignment = TextDisplay.TextAlignment.CENTER;
        private int blockLight = -1;
        private int skyLight = -1;
        private ItemStack item = new ItemStack(Material.STONE);
        private Material block = Material.STONE;

        private Hologram(String name, Type type, Location location) {
            this(name, type);
            setLocation(location);
        }

        private Hologram(String name, Type type) {
            this.name = name;
            this.type = type;
            lines = new ArrayList<>(List.of("<#f72a4c><bold>" + name + "</bold>"));
        }

        private Hologram copy(String newName) {
            Hologram copy = new Hologram(newName, type);
            copy.world = world;
            copy.x = x;
            copy.y = y;
            copy.z = z;
            copy.yaw = yaw;
            copy.pitch = pitch;
            copy.scale = scale;
            copy.animation = animation;
            copy.billboard = billboard;
            copy.visibilityDistance = visibilityDistance;
            copy.visibility = visibility;
            copy.shadowStrength = shadowStrength;
            copy.shadowRadius = shadowRadius;
            copy.lines = new ArrayList<>(lines);
            copy.background = background;
            copy.textShadow = textShadow;
            copy.alignment = alignment;
            copy.blockLight = blockLight;
            copy.skyLight = skyLight;
            copy.item = item.clone();
            copy.block = block;
            return copy;
        }

        private void setLocation(Location location) {
            world = location.getWorld().getUID();
            x = location.getX();
            y = location.getY();
            z = location.getZ();
            yaw = location.getYaw();
            pitch = location.getPitch();
        }

        private Location location(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }

        private int blockX() {
            return (int) Math.floor(x);
        }

        private int blockZ() {
            return (int) Math.floor(z);
        }
    }
}
