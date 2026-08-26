package dev.rivet;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public final class ServerListModuleTest {
    @Test
    public void readsTwoLineMotdsAndSkipsEmptyEntries() {
        assertEquals(List.of(new ServerListModule.Motd("First", "Second")),
            ServerListModule.configuredMotds(List.of(
                Map.of("line-1", "First", "line-2", "Second"),
                Map.of("line-1", "", "line-2", ""))));
    }

    @Test
    public void rotatesAtTheConfiguredInterval() {
        assertEquals(0, ServerListModule.selectIndex(
            ServerListModule.Selection.ROTATE, 3, 0, 60, 999));
        assertEquals(1, ServerListModule.selectIndex(
            ServerListModule.Selection.ROTATE, 3, 60, 60, 999));
        assertEquals(0, ServerListModule.selectIndex(
            ServerListModule.Selection.ROTATE, 3, 180, 60, 999));
        assertEquals(2, ServerListModule.selectIndex(
            ServerListModule.Selection.RANDOM, 3, 0, 60, -1));
        assertEquals(0, ServerListModule.selectIndex(
            ServerListModule.Selection.FIRST, 3, 999, 60, 2));
    }

    @Test
    public void rendersServerListPlaceholdersOnTwoLines() {
        String plain = PlainTextComponentSerializer.plainText().serialize(ServerListModule.render(
            new ServerListModule.Motd("Welcome to %hostname%", "%online%/%maximum% online"),
            "play.example.net", 12, 50));

        assertEquals("Welcome to play.example.net\n12/50 online", plain);
    }

    @Test
    public void invalidSelectionFallsBackToRotation() {
        assertEquals(ServerListModule.Selection.ROTATE,
            ServerListModule.Selection.parse("something-else"));
    }
}
