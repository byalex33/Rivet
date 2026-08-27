package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

final class HelpModule {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private static final int DEFAULT_PAGE_SIZE = 6;
    private static final int MINIMUM_PAGE_SIZE = 3;
    private static final int MAXIMUM_PAGE_SIZE = 10;
    private final RivetPlugin plugin;

    HelpModule(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    boolean command(CommandSender sender, String[] args) {
        List<CommandEntry> commands = commands(sender);
        int pageSize = pageSize();
        int pages = pageCount(commands.size(), pageSize);
        Integer requested = requestedPage(args);
        if (requested == null) {
            sender.sendMessage(MM.deserialize("<white>Usage: /help [page]</white>"));
            return true;
        }
        int page = clampPage(requested, pages);
        show(sender, commands, page, pages, pageSize);
        return true;
    }

    List<String> completions(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        int pages = pageCount(commands(sender).size(), pageSize());
        return java.util.stream.IntStream.rangeClosed(1, pages)
            .mapToObj(Integer::toString).toList();
    }

    private void show(CommandSender sender, List<CommandEntry> commands, int page,
                      int pages, int pageSize) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("━━━━━━━━━━ ", RivetPalette.SECONDARY)
            .append(MM.deserialize(plugin.settings("help").getString("title",
                "<#f72a4c><bold>RIVET HELP</bold></#f72a4c>")))
            .append(Component.text(" ━━━━━━━━━━", RivetPalette.SECONDARY)));
        sender.sendMessage(MM.deserialize(plugin.settings("help").getString("subtitle",
                "<white>%commands% available commands <#f72a4c>•</#f72a4c> Page %page%/%pages%</white>"),
            Placeholder.unparsed("commands", Integer.toString(commands.size())),
            Placeholder.unparsed("page", Integer.toString(page)),
            Placeholder.unparsed("pages", Integer.toString(pages))));
        sender.sendMessage(Component.empty());

        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, commands.size());
        for (CommandEntry entry : commands.subList(start, end)) {
            Component command = Component.text(entry.usage(), RivetPalette.SECONDARY)
                .decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.suggestCommand("/" + entry.name() + " "))
                .hoverEvent(HoverEvent.showText(Component.text("Click to put this command in chat",
                    RivetPalette.PRIMARY)));
            sender.sendMessage(Component.text("  • ", RivetPalette.SECONDARY).append(command));
            sender.sendMessage(Component.text("    " + entry.description(), RivetPalette.PRIMARY));
        }

        if (commands.isEmpty()) {
            sender.sendMessage(Component.text("  No commands are currently available to you.",
                RivetPalette.PRIMARY));
        }
        sender.sendMessage(Component.empty());
        sender.sendMessage(navigation(page, pages));
        sender.sendMessage(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━", RivetPalette.SECONDARY));
    }

    static Component navigation(int page, int pages) {
        TextComponent.Builder footer = Component.text();
        footer.append(button("« First", 1, page > 1));
        footer.append(Component.space());
        footer.append(button("‹ Previous", page - 1, page > 1));
        footer.append(Component.text("  " + page + "/" + pages + "  ", RivetPalette.PRIMARY));
        footer.append(button("Next ›", page + 1, page < pages));
        footer.append(Component.space());
        footer.append(button("Last »", pages, page < pages));
        return footer.build();
    }

    private static Component button(String label, int page, boolean enabled) {
        if (!enabled) {
            return Component.text("[" + label + "]", RivetPalette.PRIMARY);
        }
        return Component.text("[" + label + "]", RivetPalette.SECONDARY)
            .decorate(TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand("/rivethelp " + page))
            .hoverEvent(HoverEvent.showText(Component.text("Open page " + page,
                RivetPalette.PRIMARY)));
    }

    private List<CommandEntry> commands(CommandSender sender) {
        List<CommandEntry> declared = new ArrayList<>();
        plugin.getDescription().getCommands().keySet().forEach(name -> {
            PluginCommand command = plugin.getCommand(name);
            if (command == null) {
                return;
            }
            String usage = command.getUsage();
            if (usage == null || usage.isBlank()) {
                usage = "/" + name;
            } else if (!usage.startsWith("/")) {
                usage = "/" + usage;
            }
            declared.add(new CommandEntry(name, usage, command.getDescription(),
                RivetPlugin.moduleForCommand(name), command.getPermission()));
        });
        return visible(declared, plugin::moduleEnabled, sender::hasPermission);
    }

    private int pageSize() {
        return Math.max(MINIMUM_PAGE_SIZE, Math.min(MAXIMUM_PAGE_SIZE,
            plugin.settings("help").getInt("page-size", DEFAULT_PAGE_SIZE)));
    }

    private static Integer requestedPage(String[] args) {
        if (args.length == 0) {
            return 1;
        }
        if (args.length != 1) {
            return null;
        }
        try {
            return Integer.parseInt(args[0]);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static List<CommandEntry> visible(List<CommandEntry> commands,
                                      Predicate<String> moduleEnabled,
                                      Predicate<String> permitted) {
        return commands.stream()
            .filter(command -> command.module() == null || moduleEnabled.test(command.module()))
            .filter(command -> command.permission() == null || command.permission().isBlank()
                || permitted.test(command.permission()))
            .sorted(Comparator.comparing(CommandEntry::name, String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    static int pageCount(int commandCount, int pageSize) {
        return Math.max(1, (commandCount + pageSize - 1) / pageSize);
    }

    static int clampPage(int requested, int pages) {
        return Math.max(1, Math.min(requested, pages));
    }

    record CommandEntry(String name, String usage, String description, String module,
                        String permission) {
    }
}
