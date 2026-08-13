package dev.rivet;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class PermissionResolver {
    private static final Comparator<Match> MATCH_ORDER = Comparator
        .comparingInt(Match::weight).reversed()
        .thenComparing(Comparator.comparingInt(Match::specificity).reversed())
        .thenComparing(Match::value)
        .thenComparing(Match::source);

    private PermissionResolver() {
    }

    static Resolution resolve(YamlConfiguration groups, List<String> assignedGroups,
                              Map<String, Boolean> directPermissions, String requested,
                              boolean fallback) {
        List<GroupRef> memberships = memberships(groups, assignedGroups);
        Match direct = bestMatch(directPermissions, requested, "user", Integer.MAX_VALUE,
            true, false);
        List<Match> groupMatches = memberships.stream().map(group -> bestMatch(
                groupPermissions(groups, group.name()), requested, group.name(), group.weight(),
                false, group.inherited()))
            .filter(java.util.Objects::nonNull).sorted(MATCH_ORDER).toList();
        Match winner = direct != null ? direct
            : groupMatches.isEmpty() ? null : groupMatches.getFirst();
        return new Resolution(winner == null ? fallback : winner.value(), winner,
            memberships, groupMatches, fallback);
    }

    static List<GroupRef> memberships(YamlConfiguration groups, List<String> assignedGroups) {
        LinkedHashMap<String, GroupRef> resolved = new LinkedHashMap<>();
        List<String> roots = assignedGroups.stream().map(PermissionResolver::normalize)
            .filter(PermissionResolver::validGroupName).distinct().toList();
        if (roots.isEmpty() || roots.stream().noneMatch(group -> hasGroup(groups, group))) {
            roots = hasGroup(groups, "default") ? List.of("default") : List.of();
        }
        for (String group : roots) {
            collect(groups, group, false, resolved, new HashSet<>());
        }
        return List.copyOf(resolved.values());
    }

    private static void collect(YamlConfiguration groups, String group, boolean inherited,
                                Map<String, GroupRef> resolved, Set<String> path) {
        if (!hasGroup(groups, group) || !path.add(group)) {
            return;
        }
        GroupRef current = resolved.get(group);
        if (current == null || current.inherited() && !inherited) {
            resolved.put(group, new GroupRef(group, weight(groups, group), inherited));
        }
        for (String parent : parents(groups, group)) {
            collect(groups, parent, true, resolved, path);
        }
        path.remove(group);
    }

    static Match bestMatch(Map<String, Boolean> permissions, String requested, String source,
                           int weight, boolean direct, boolean inherited) {
        return permissions.entrySet().stream()
            .filter(entry -> matches(entry.getKey(), requested))
            .map(entry -> new Match(entry.getKey(), entry.getValue(), source, weight,
                specificity(entry.getKey(), requested), direct, inherited))
            .sorted(Comparator.comparingInt(Match::specificity).reversed()
                .thenComparing(Match::value).thenComparing(Match::node))
            .findFirst().orElse(null);
    }

    static boolean matches(String configured, String requested) {
        if (configured.equals(requested) || configured.equals("*")) {
            return true;
        }
        return configured.endsWith(".*")
            && requested.startsWith(configured.substring(0, configured.length() - 1));
    }

    private static int specificity(String configured, String requested) {
        return configured.equals(requested) ? 1_000_000 + configured.length()
            : configured.equals("*") ? 0 : configured.length() - 1;
    }

    static Map<String, Boolean> permissions(YamlConfiguration configuration, String base) {
        ConfigurationSection section = configuration.getConfigurationSection(base);
        if (section == null) {
            return Map.of();
        }
        Map<String, Boolean> permissions = new LinkedHashMap<>();
        section.getValues(true).forEach((node, value) -> {
            if (value instanceof Boolean state) {
                permissions.put(node.replace('/', '.'), state);
            }
        });
        return Map.copyOf(permissions);
    }

    static Map<String, Boolean> groupPermissions(YamlConfiguration groups, String group) {
        return permissions(groups, groupPath(group) + "/permissions");
    }

    static List<String> parents(YamlConfiguration groups, String group) {
        return groups.getStringList(groupPath(group) + "/parents").stream()
            .map(PermissionResolver::normalize).filter(PermissionResolver::validGroupName)
            .distinct().toList();
    }

    static int weight(YamlConfiguration groups, String group) {
        return groups.getInt(groupPath(group) + "/weight", 0);
    }

    static boolean hasGroup(YamlConfiguration groups, String group) {
        return groups.isConfigurationSection(groupPath(group));
    }

    static String effectiveMeta(YamlConfiguration groups, List<GroupRef> memberships,
                                String key) {
        return memberships.stream()
            .filter(group -> groups.isString(groupPath(group.name()) + "/meta/" + key))
            .sorted(Comparator.comparingInt(GroupRef::weight).reversed()
                .thenComparing(GroupRef::inherited).thenComparing(GroupRef::name))
            .map(group -> groups.getString(groupPath(group.name()) + "/meta/" + key, ""))
            .findFirst().orElse("");
    }

    static boolean wouldCreateCycle(YamlConfiguration groups, String child, String parent) {
        return child.equals(parent) || reaches(groups, parent, child, new HashSet<>());
    }

    private static boolean reaches(YamlConfiguration groups, String current, String target,
                                   Set<String> visited) {
        if (!visited.add(current)) {
            return false;
        }
        if (current.equals(target)) {
            return true;
        }
        return parents(groups, current).stream()
            .anyMatch(parent -> reaches(groups, parent, target, visited));
    }

    static List<String> treeLines(YamlConfiguration groups, List<String> assignedGroups) {
        List<String> roots = memberships(groups, assignedGroups).stream()
            .filter(group -> !group.inherited()).map(GroupRef::name).toList();
        List<String> lines = new ArrayList<>();
        for (String root : roots) {
            appendTree(groups, root, "", lines, new LinkedHashSet<>());
        }
        return List.copyOf(lines);
    }

    private static void appendTree(YamlConfiguration groups, String group, String indent,
                                   List<String> lines, Set<String> path) {
        if (!path.add(group)) {
            lines.add(indent + "↳ " + group + " (cycle)");
            return;
        }
        lines.add(indent + "• " + group + " (weight " + weight(groups, group) + ")");
        for (String parent : parents(groups, group)) {
            appendTree(groups, parent, indent + "  ", lines, path);
        }
        path.remove(group);
    }

    static boolean validPermission(String value) {
        return value != null && value.matches("(\\*|[a-z0-9_.-]+(?:\\.\\*)?)");
    }

    static boolean validGroupName(String value) {
        return value != null && value.matches("[a-z0-9_-]{1,32}");
    }

    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static String groupPath(String group) {
        return "groups/" + group;
    }

    record GroupRef(String name, int weight, boolean inherited) {
    }

    record Match(String node, boolean value, String source, int weight, int specificity,
                 boolean direct, boolean inherited) {
    }

    record Resolution(boolean value, Match winner, List<GroupRef> groups,
                      List<Match> groupMatches, boolean fallback) {
        Boolean configuredValue() {
            return winner == null ? null : winner.value();
        }
    }
}
