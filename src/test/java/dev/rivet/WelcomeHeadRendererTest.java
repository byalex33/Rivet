package dev.rivet;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class WelcomeHeadRendererTest {
    @Test
    public void extractsTheFaceAndCompositesTheHatLayer() {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(skin, 8, 8, new Color(210, 30, 40, 255));
        skin.setRGB(40, 8, new Color(20, 80, 230, 255).getRGB());

        int[] withoutHat = WelcomeHeadRenderer.extractHeadPixels(skin, false);
        int[] withHat = WelcomeHeadRenderer.extractHeadPixels(skin, true);

        assertNotNull(withoutHat);
        assertEquals(new Color(210, 30, 40, 255).getRGB(), withoutHat[0]);
        assertEquals(new Color(20, 80, 230, 255).getRGB(), withHat[0]);
        assertEquals(new Color(210, 30, 40, 255).getRGB(), withHat[1]);
    }

    @Test
    public void normalizesWelcomeLayoutsToEightLinesAndSafeSpacing() {
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("lines", List.of("one", "two"));
        settings.set("character", "▓more");
        settings.set("center", true);
        settings.set("show-hat-layer", false);
        settings.set("blank-lines-before", 99);
        settings.set("blank-lines-after", -3);

        WelcomeHeadRenderer.Layout layout = WelcomeHeadRenderer.layout(settings);

        assertEquals(8, layout.lines().size());
        assertEquals("one", layout.lines().getFirst());
        assertEquals("", layout.lines().getLast());
        assertEquals("▓", layout.character());
        assertEquals(true, layout.center());
        assertEquals(false, layout.hatLayer());
        assertEquals(20, layout.blankLinesBefore());
        assertEquals(0, layout.blankLinesAfter());
    }

    private static void fill(BufferedImage skin, int startX, int startY, Color color) {
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                skin.setRGB(startX + x, startY + y, color.getRGB());
            }
        }
    }
}
