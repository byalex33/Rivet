package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ChatMentionsTest {
    @Test
    public void recognizesPlainAndAtNamesOnlyAtNameBoundaries() {
        assertTrue(ChatModule.containsMention("Hi Alex!", "Alex"));
        assertTrue(ChatModule.containsMention("Hi @aLeX!", "Alex"));
        assertFalse(ChatModule.containsMention("Hi Alexander", "Alex"));
        assertFalse(ChatModule.containsMention("email@Alex", "Alex"));
    }

    @Test
    public void preservesMentionSpellingAndRestoresTheSelectedChatColor() {
        Component redMessage = ChatModule.formatMessage("<red>",
            Component.text("Hi Alex, welcome back"));
        Component highlighted = ChatModule.replaceMention(redMessage, "Alex", match ->
            Component.text(match, NamedTextColor.GREEN));

        assertEquals(NamedTextColor.GREEN, colorOf(highlighted, "Alex", null));
        assertEquals(NamedTextColor.RED, colorOf(highlighted, "welcome back", null));

        Component atMention = ChatModule.replaceMention(Component.text("Hi @Alex"), "Alex",
            match -> Component.text(match, NamedTextColor.GREEN));
        assertEquals("@Alex", textContaining(atMention, "Alex"));
    }

    @Test
    public void packagesGreenHighlightAndSoundTitleNotifications() {
        YamlConfiguration settings = settings();

        assertEquals("<green>%mention%</green>", settings.getString("mentions.format"));
        assertTrue(settings.getStringList("mentions.notifications.actions").stream()
            .anyMatch(action -> action.startsWith("[sound]")));
        assertTrue(settings.getStringList("mentions.notifications.actions").stream()
            .anyMatch(action -> action.startsWith("[title]")));
    }

    @Test
    public void migratesThePreviousMentionDefaultsWithoutLosingItsSound() {
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("mentions.format", "<yellow>@%player%</yellow>");
        settings.set("mentions.sound", "ENTITY_EXPERIENCE_ORB_PICKUP");

        assertTrue(RivetConfig.migrateLegacyChatSettings(settings));
        assertEquals("<green>%mention%</green>", settings.getString("mentions.format"));
        assertTrue(settings.getStringList("mentions.notifications.actions").getFirst()
            .contains("ENTITY_EXPERIENCE_ORB_PICKUP"));
    }

    private static TextColor colorOf(Component component, String text, TextColor inherited) {
        TextColor effective = component.color() == null ? inherited : component.color();
        if (component instanceof TextComponent value && value.content().contains(text)) {
            return effective;
        }
        for (Component child : component.children()) {
            TextColor color = colorOf(child, text, effective);
            if (color != null) {
                return color;
            }
        }
        return null;
    }

    private static String textContaining(Component component, String text) {
        if (component instanceof TextComponent value && value.content().contains(text)) {
            return value.content();
        }
        return component.children().stream().map(child -> textContaining(child, text))
            .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private static YamlConfiguration settings() {
        var resource = ChatMentionsTest.class.getResourceAsStream("/settings/chat.yml");
        assertNotNull(resource);
        return YamlConfiguration.loadConfiguration(
            new InputStreamReader(resource, StandardCharsets.UTF_8));
    }
}
