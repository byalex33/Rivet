package dev.rivet;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RivetMiniMessage {
    private static final Set<String> PLACEHOLDERS = Set.of(
        "amount", "animal", "biome", "blocks_broken", "blocks_placed", "bred",
        "command", "commands", "coordinates", "count", "current", "death_location", "deaths", "description", "display",
        "details", "distance", "duration", "first_join", "food", "id", "item", "items", "jumps",
        "hostname", "killer", "kit", "kits", "last_death", "last_login", "last_logout", "matches", "material",
        "max", "maximum", "message", "mob", "mob_kills", "name", "page", "pages", "ping",
        "online", "percent", "player", "player_kills", "players", "playtime", "plural", "quality", "radius", "reason", "remaining",
        "prefix", "recipient", "requester", "seconds", "sender", "session", "since_first_join", "speed", "staff",
        "status", "streak", "suffix", "tag", "target", "text", "time", "timestamp", "used", "weapon", "weather", "world", "x", "y", "yes", "no", "z");
    private static final Pattern PERCENT_PLACEHOLDER = Pattern.compile(
        "%([a-z][a-z0-9_-]*)%", Pattern.CASE_INSENSITIVE);
    private static final MiniMessage MINI_MESSAGE = builder().build();

    private RivetMiniMessage() {
    }

    static MiniMessage miniMessage() {
        return MINI_MESSAGE;
    }

    static MiniMessage.Builder builder() {
        return MiniMessage.builder()
            .preProcessor(RivetMiniMessage::toResolverTags)
            .editTags(tags -> tags.tag("lime", Tag.styling(NamedTextColor.GREEN)));
    }

    static String toResolverTags(String input) {
        Matcher matcher = PERCENT_PLACEHOLDER.matcher(input);
        StringBuilder converted = new StringBuilder(input.length());
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            matcher.appendReplacement(converted, PLACEHOLDERS.contains(name)
                ? Matcher.quoteReplacement("<" + name + ">")
                : Matcher.quoteReplacement(matcher.group()));
        }
        return matcher.appendTail(converted).toString();
    }

    static String toPercentPlaceholders(String input) {
        String converted = input;
        for (String placeholder : PLACEHOLDERS) {
            converted = converted.replaceAll("(?i)<" + Pattern.quote(placeholder) + ">",
                Matcher.quoteReplacement("%" + placeholder + "%"));
        }
        return converted;
    }
}
