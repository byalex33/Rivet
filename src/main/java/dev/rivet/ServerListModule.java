package dev.rivet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

final class ServerListModule implements Listener {
    private static final MiniMessage MM = RivetMiniMessage.miniMessage();

    private final RivetPlugin plugin;
    private volatile Settings configured = new Settings(Selection.ROTATE, 60, List.of());

    ServerListModule(RivetPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        YamlConfiguration settings = plugin.settings("server-list");
        Selection selection = Selection.parse(settings.getString("selection", "ROTATE"));
        int rotationSeconds = Math.max(1, settings.getInt("rotation-seconds", 60));
        configured = new Settings(selection, rotationSeconds, configuredMotds(settings.getMapList("motds")));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPing(ServerListPingEvent event) {
        Settings settings = configured;
        if (settings.motds().isEmpty()) {
            return;
        }
        int randomValue = ThreadLocalRandom.current().nextInt();
        int index = selectIndex(settings.selection(), settings.motds().size(),
            System.currentTimeMillis() / 1_000L, settings.rotationSeconds(), randomValue);
        Motd motd = settings.motds().get(index);
        event.motd(render(motd, event.getHostname(), event.getNumPlayers(), event.getMaxPlayers()));
    }

    static Component render(Motd motd, String hostname, int online, int maximum) {
        String text = motd.lineTwo().isEmpty()
            ? motd.lineOne() : motd.lineOne() + "\n" + motd.lineTwo();
        return MM.deserialize(text,
            Placeholder.unparsed("hostname", hostname == null ? "" : hostname),
            Placeholder.unparsed("online", Integer.toString(Math.max(0, online))),
            Placeholder.unparsed("maximum", Integer.toString(Math.max(0, maximum))));
    }

    static int selectIndex(Selection selection, int size, long epochSecond,
                           int rotationSeconds, int randomValue) {
        if (size <= 1) {
            return 0;
        }
        return switch (selection) {
            case FIRST -> 0;
            case RANDOM -> Math.floorMod(randomValue, size);
            case ROTATE -> Math.floorMod(epochSecond / Math.max(1, rotationSeconds), size);
        };
    }

    static List<Motd> configuredMotds(List<Map<?, ?>> configured) {
        List<Motd> motds = new ArrayList<>();
        for (Map<?, ?> entry : configured) {
            Object first = entry.get("line-1");
            Object second = entry.get("line-2");
            String lineOne = first instanceof String line ? line : "";
            String lineTwo = second instanceof String line ? line : "";
            if (!lineOne.isEmpty() || !lineTwo.isEmpty()) {
                motds.add(new Motd(lineOne, lineTwo));
            }
        }
        return List.copyOf(motds);
    }

    enum Selection {
        FIRST,
        RANDOM,
        ROTATE;

        static Selection parse(String configured) {
            if (configured == null) {
                return ROTATE;
            }
            try {
                return valueOf(configured.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return ROTATE;
            }
        }
    }

    record Motd(String lineOne, String lineTwo) {
    }

    private record Settings(Selection selection, int rotationSeconds, List<Motd> motds) {
    }
}
