package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Draws the 8x8 face from a player's skin as a large component-based chat portrait. */
final class WelcomeHeadRenderer {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();
    private static final int PIXELS = 8;
    private static final int CHAT_CENTER_PIXELS = 154;
    private static final int SPACE_PIXELS = 4;
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final String TEXTURE_HOST = "textures.minecraft.net";

    private final RivetPlugin plugin;
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private final Map<CacheKey, int[]> cache = new ConcurrentHashMap<>();

    WelcomeHeadRenderer(RivetPlugin plugin) {
        this.plugin = plugin;
    }

    void send(Player player, ConfigurationSection section, TagResolver placeholders) {
        Layout layout = layout(section);
        URI texture = textureUri(player);
        if (texture == null) {
            deliver(player, layout, null, placeholders);
            return;
        }
        CacheKey key = new CacheKey(player.getUniqueId(), texture, layout.hatLayer());
        int[] cached = cache.get(key);
        if (cached != null) {
            deliver(player, layout, cached, placeholders);
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            int[] downloaded = download(texture, layout.hatLayer());
            if (downloaded != null) {
                cache.put(key, downloaded);
            }
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin,
                () -> deliver(player, layout, downloaded, placeholders));
        });
    }

    private int[] download(URI texture, boolean hatLayer) {
        try {
            HttpRequest request = HttpRequest.newBuilder(texture)
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "Rivet/1.0")
                .build();
            HttpResponse<byte[]> response = client.send(request,
                HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > MAX_IMAGE_BYTES) {
                return null;
            }
            BufferedImage skin = ImageIO.read(new ByteArrayInputStream(response.body()));
            return skin == null ? null : extractHeadPixels(skin, hatLayer);
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not load a welcome portrait: "
                + exception.getClass().getSimpleName());
            return null;
        }
    }

    private void deliver(Player player, Layout layout, int[] pixels, TagResolver placeholders) {
        if (!player.isOnline()) {
            return;
        }
        for (int line = 0; line < layout.blankLinesBefore(); line++) {
            player.sendMessage(Component.empty());
        }
        for (int y = 0; y < PIXELS; y++) {
            Component portrait = pixels == null
                ? (y == 3 ? RivetHeads.component(player) : Component.text("        "))
                : pixelLine(pixels, y, layout.character());
            String configured = y < layout.lines().size() ? layout.lines().get(y) : "";
            Component text = configured.isEmpty() ? Component.empty()
                : MM.deserialize(configured, placeholders);
            Component line = portrait.append(Component.text("  ")).append(text);
            player.sendMessage(layout.center() ? center(line) : line);
        }
        for (int line = 0; line < layout.blankLinesAfter(); line++) {
            player.sendMessage(Component.empty());
        }
    }

    static int[] extractHeadPixels(BufferedImage skin, boolean hatLayer) {
        if (skin.getWidth() < 16 || skin.getHeight() < 16) {
            return null;
        }
        BufferedImage head = new BufferedImage(PIXELS, PIXELS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = head.createGraphics();
        graphics.drawImage(skin.getSubimage(8, 8, PIXELS, PIXELS), 0, 0, null);
        if (hatLayer && skin.getWidth() >= 48 && skin.getHeight() >= 16) {
            graphics.drawImage(skin.getSubimage(40, 8, PIXELS, PIXELS), 0, 0, null);
        }
        graphics.dispose();
        return head.getRGB(0, 0, PIXELS, PIXELS, null, 0, PIXELS);
    }

    static Component pixelLine(int[] pixels, int row, String character) {
        Component line = Component.empty();
        for (int x = 0; x < PIXELS; x++) {
            int argb = pixels[row * PIXELS + x];
            line = line.append((argb >>> 24) < 128 ? Component.text(" ")
                : Component.text(character, TextColor.color(argb & 0x00ffffff)));
        }
        return line;
    }

    static Component center(Component line) {
        String plain = PlainTextComponentSerializer.plainText().serialize(line);
        int width = plain.codePoints().map(codePoint -> codePoint == ' ' ? 4 : 6).sum();
        int spaces = Math.max(0, (CHAT_CENTER_PIXELS - width / 2) / SPACE_PIXELS);
        return spaces == 0 ? line : Component.text(" ".repeat(spaces)).append(line);
    }

    static Layout layout(ConfigurationSection section) {
        List<String> lines = section == null ? List.of() : section.getStringList("lines");
        List<String> fixedLines = new ArrayList<>(PIXELS);
        for (int index = 0; index < PIXELS; index++) {
            fixedLines.add(index < lines.size() ? lines.get(index) : "");
        }
        String configuredCharacter = section == null ? "█"
            : section.getString("character", "█");
        int codePoint = configuredCharacter == null || configuredCharacter.isEmpty()
            ? '█' : configuredCharacter.codePointAt(0);
        String character = new String(Character.toChars(codePoint));
        return new Layout(List.copyOf(fixedLines), character,
            section != null && section.getBoolean("center", false),
            section == null || section.getBoolean("show-hat-layer", true),
            Math.clamp(section == null ? 0 : section.getInt("blank-lines-before", 0), 0, 20),
            Math.clamp(section == null ? 0 : section.getInt("blank-lines-after", 0), 0, 20));
    }

    private static URI textureUri(Player player) {
        try {
            var skin = player.getPlayerProfile().getTextures().getSkin();
            if (skin == null) {
                return null;
            }
            URI original = skin.toURI();
            if (!TEXTURE_HOST.equalsIgnoreCase(original.getHost())) {
                return null;
            }
            return new URI("https", original.getUserInfo(), original.getHost(),
                original.getPort(), original.getPath(), original.getQuery(), null);
        } catch (Exception ignored) {
            return null;
        }
    }

    record Layout(List<String> lines, String character, boolean center, boolean hatLayer,
                  int blankLinesBefore, int blankLinesAfter) {
    }

    private record CacheKey(UUID playerId, URI texture, boolean hatLayer) {
    }
}
