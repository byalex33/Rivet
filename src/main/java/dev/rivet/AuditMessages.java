package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

final class AuditMessages {
    private static final TextColor ACCENT = RivetPalette.SECONDARY;

    private AuditMessages() {
    }

    static Component history(Component tag, String title, String target, String world,
                             int x, int y, int z, List<AuditEntry> entries,
                             boolean canTeleport) {
        return history(tag, title, target, world, x, y, z, entries, 1, 1, canTeleport);
    }

    static Component history(Component tag, String title, String target, String world,
                             int x, int y, int z, List<AuditEntry> entries, int page,
                             int pages, boolean canTeleport) {
        Component result = tag.append(Component.space())
            .append(Component.text(title, ACCENT).decorate(TextDecoration.BOLD))
            .append(Component.newline())
            .append(Component.text(target, NamedTextColor.GRAY))
            .append(Component.text(" at ", NamedTextColor.DARK_GRAY))
            .append(coordinates(world, x, y, z, canTeleport))
            .append(Component.newline()).append(Component.newline());
        if (entries.isEmpty()) {
            result = result.append(Component.text("No recent history was found.", NamedTextColor.GRAY));
        } else {
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) {
                    result = result.append(Component.newline());
                }
                result = result.append(historyLine(entries.get(index)));
            }
        }
        if (pages > 1) {
            result = result.append(Component.newline()).append(Component.newline())
                .append(Component.text("Page " + page + "/" + pages, NamedTextColor.DARK_GRAY));
            if (page > 1) {
                result = result.append(Component.space()).append(pageButton("Previous", page - 1));
            }
            if (page < pages) {
                result = result.append(Component.space()).append(pageButton("Next", page + 1));
            }
        }
        return result;
    }

    static Component lookup(Component tag, String title, String context, List<AuditEntry> entries,
                            int page, int pages, int pageSize, boolean canTeleport) {
        Component result = tag.append(Component.space())
            .append(Component.text(title, ACCENT).decorate(TextDecoration.BOLD))
            .append(Component.newline())
            .append(Component.text(context, NamedTextColor.DARK_GRAY))
            .append(Component.newline()).append(Component.newline());
        if (entries.isEmpty()) {
            result = result.append(Component.text("No matching entries were found.", NamedTextColor.GRAY));
        } else {
            for (int index = 0; index < entries.size(); index++) {
                if (index > 0) {
                    result = result.append(Component.newline());
                }
                result = result.append(lookupLine(index + 1 + (page - 1) * pageSize,
                    entries.get(index), canTeleport));
            }
        }
        result = result.append(Component.newline()).append(Component.newline())
            .append(Component.text("Page " + page + "/" + pages, NamedTextColor.DARK_GRAY));
        if (page > 1) {
            result = result.append(Component.space()).append(pageButton("Previous", page - 1));
        }
        if (page < pages) {
            result = result.append(Component.space()).append(pageButton("Next", page + 1));
        }
        return result;
    }

    static Component coordinates(String world, int x, int y, int z, boolean canTeleport) {
        Component coordinates = Component.text(x + ", " + y + ", " + z, NamedTextColor.WHITE)
            .hoverEvent(HoverEvent.showText(Component.text(world + " • " + x + ", " + y + ", " + z,
                NamedTextColor.WHITE)));
        if (!canTeleport) {
            return coordinates;
        }
        String encodedWorld = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(world.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return coordinates.clickEvent(ClickEvent.runCommand(
            "/log tp " + encodedWorld + " " + x + " " + y + " " + z));
    }

    static Component historyLine(AuditEntry entry) {
        return Component.text(ago(entry.timestamp()), NamedTextColor.DARK_GRAY)
            .append(Component.text("  "))
            .append(symbol(entry.action()))
            .append(Component.space())
            .append(Component.text(actor(entry), NamedTextColor.WHITE))
            .append(Component.space())
            .append(Component.text(verb(entry.action()), NamedTextColor.GRAY))
            .append(Component.space())
            .append(Component.text(amountTarget(entry), NamedTextColor.WHITE));
    }

    static Component lookupLine(int number, AuditEntry entry, boolean canTeleport) {
        return Component.text(number + ". ", NamedTextColor.GRAY)
            .append(Component.text(shortAgo(entry.timestamp()), NamedTextColor.DARK_GRAY))
            .append(Component.space()).append(symbol(entry.action())).append(Component.space())
            .append(Component.text(amountTarget(entry), NamedTextColor.WHITE))
            .append(Component.space())
            .append(coordinates(entry.world(), entry.x(), entry.y(), entry.z(), canTeleport));
    }

    static String ago(long timestamp) {
        long seconds = Math.max(0, (System.currentTimeMillis() - timestamp) / 1_000);
        if (seconds < 60) {
            return seconds + "s ago";
        }
        if (seconds < 3_600) {
            return seconds / 60 + "m ago";
        }
        if (seconds < 86_400) {
            return seconds / 3_600 + "h ago";
        }
        return seconds / 86_400 + "d ago";
    }

    static String timeLabel(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds % 86_400 == 0) {
            return seconds / 86_400 + " day" + (seconds == 86_400 ? "" : "s");
        }
        if (seconds % 3_600 == 0) {
            return seconds / 3_600 + " hour" + (seconds == 3_600 ? "" : "s");
        }
        if (seconds % 60 == 0) {
            return seconds / 60 + " minute" + (seconds == 60 ? "" : "s");
        }
        return seconds + " seconds";
    }

    private static String shortAgo(long timestamp) {
        return ago(timestamp).replace(" ago", "");
    }

    private static Component symbol(AuditAction action) {
        return switch (action) {
            case BLOCK_PLACE, CONTAINER_ADD, ITEM_PICKUP -> Component.text("+", NamedTextColor.GREEN);
            case BLOCK_BREAK, CONTAINER_REMOVE, ITEM_DROP -> Component.text("−", NamedTextColor.RED);
            case ENTITY_KILL, PLAYER_DEATH, EXPLOSION, CREEPER_DAMAGE, FIRE_DAMAGE ->
                Component.text("×", NamedTextColor.RED);
            case SIGN_EDIT, BLOCK_INTERACT -> Component.text("•", ACCENT);
            case SNAPSHOT_CREATE -> Component.text("◇", ACCENT);
            case SNAPSHOT_RESTORE -> Component.text("↺", NamedTextColor.GREEN);
            case COMMAND -> Component.text(">", ACCENT);
        };
    }

    private static String actor(AuditEntry entry) {
        return entry.playerName() == null ? "Environment" : entry.playerName();
    }

    private static String amountTarget(AuditEntry entry) {
        return entry.amount() != null && entry.amount() > 1
            ? entry.amount() + "× " + entry.target() : entry.target();
    }

    private static String verb(AuditAction action) {
        return switch (action) {
            case BLOCK_PLACE -> "placed";
            case BLOCK_BREAK -> "broke";
            case CONTAINER_ADD -> "added";
            case CONTAINER_REMOVE -> "removed";
            case ITEM_PICKUP -> "picked up";
            case ITEM_DROP -> "dropped";
            case ENTITY_KILL -> "killed";
            case PLAYER_DEATH -> "died near";
            case SIGN_EDIT -> "edited";
            case EXPLOSION -> "exploded";
            case CREEPER_DAMAGE -> "creeper damaged";
            case FIRE_DAMAGE -> "burned";
            case BLOCK_INTERACT -> "used";
            case SNAPSHOT_CREATE -> "created";
            case SNAPSHOT_RESTORE -> "restored";
            case COMMAND -> "ran";
        };
    }

    private static Component pageButton(String label, int page) {
        return Component.text("[" + label + "]", ACCENT)
            .clickEvent(ClickEvent.runCommand("/log page " + page))
            .hoverEvent(HoverEvent.showText(Component.text("Open page " + page,
                NamedTextColor.WHITE)));
    }
}
