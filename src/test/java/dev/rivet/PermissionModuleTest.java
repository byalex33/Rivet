package dev.rivet;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class PermissionModuleTest {
    @Test
    public void packagesReadableWeightedTriStateDefaults() throws Exception {
        YamlConfiguration groups = new YamlConfiguration();
        groups.options().pathSeparator('/');
        groups.load(new InputStreamReader(getClass().getResourceAsStream(
            "/settings/permissions.yml"), StandardCharsets.UTF_8));

        assertEquals(0, PermissionResolver.weight(groups, "default"));
        assertEquals(50, PermissionResolver.weight(groups, "staff"));
        assertEquals(100, PermissionResolver.weight(groups, "admin"));
        assertEquals(List.of("staff"), PermissionResolver.parents(groups, "admin"));
        assertEquals(Boolean.TRUE,
            PermissionResolver.groupPermissions(groups, "default").get("rivet.message"));
        assertEquals(Boolean.TRUE,
            PermissionResolver.groupPermissions(groups, "admin").get("*"));
        assertEquals("<#f72a4c>[Admin] </#f72a4c>",
            groups.getString("groups/admin/meta/prefix"));
        PermissionModule.validate(groups, new YamlConfiguration());
    }

    @Test
    public void directUserRulesAlwaysOverrideWeightedGroups() {
        YamlConfiguration groups = groups();
        permission(groups, "staff", "rivet.fly", true);
        permission(groups, "admin", "rivet.fly", false);
        assertEquals(Map.of("rivet.fly", true),
            PermissionResolver.groupPermissions(groups, "staff"));
        assertEquals(Map.of("rivet.fly", false),
            PermissionResolver.groupPermissions(groups, "admin"));

        PermissionResolver.Resolution groupResult = PermissionResolver.resolve(groups,
            List.of("staff", "admin"), Map.of(), "rivet.fly", true);
        assertFalse(groupResult.value());
        assertEquals("admin", groupResult.winner().source());
        assertEquals(100, groupResult.winner().weight());

        PermissionResolver.Resolution directResult = PermissionResolver.resolve(groups,
            List.of("admin"), Map.of("rivet.*", true), "rivet.fly", false);
        assertTrue(directResult.value());
        assertTrue(directResult.winner().direct());
        assertEquals("rivet.*", directResult.winner().node());

        PermissionResolver.Resolution directDeny = PermissionResolver.resolve(groups,
            List.of("admin"), Map.of("rivet.fly", false), "rivet.fly", true);
        assertFalse(directDeny.value());
        assertTrue(directDeny.winner().direct());
    }

    @Test
    public void exactRulesBeatWildcardsAndDeniesBeatExactTies() {
        YamlConfiguration groups = groups();
        permission(groups, "admin", "rivet.*", true);
        permission(groups, "admin", "rivet.permissions.manage", false);
        assertEquals(Map.of("rivet.*", true, "rivet.permissions.manage", false),
            PermissionResolver.groupPermissions(groups, "admin"));

        PermissionResolver.Resolution denied = PermissionResolver.resolve(groups,
            List.of("admin"), Map.of(), "rivet.permissions.manage", true);
        assertFalse(denied.value());
        assertEquals("rivet.permissions.manage", denied.winner().node());

        PermissionResolver.Match tie = PermissionResolver.bestMatch(
            new java.util.LinkedHashMap<>(Map.of("rivet.*", true, "*", false)),
            "rivet.home", "test", 0, false, false);
        assertTrue(tie.value());
        assertEquals("rivet.*", tie.node());
        assertTrue(PermissionResolver.matches("rivet.inventory.*", "rivet.inventory.clear"));
        assertFalse(PermissionResolver.matches("rivet.inventory.*", "rivet.inventory"));
    }

    @Test
    public void higherWeightWinsAcrossDirectAndInheritedGroups() {
        YamlConfiguration groups = groups();
        permission(groups, "default", "rivet.home", false);
        permission(groups, "staff", "rivet.*", true);
        createGroup(groups, "builder", 75, List.of("default"));
        permission(groups, "builder", "rivet.home", false);

        PermissionResolver.Resolution result = PermissionResolver.resolve(groups,
            List.of("staff", "builder"), Map.of(), "rivet.home", true);
        assertFalse(result.value());
        assertEquals("builder", result.winner().source());
        assertEquals(List.of("staff", "default", "builder"),
            result.groups().stream().map(PermissionResolver.GroupRef::name).toList());
        assertTrue(result.groups().stream().filter(group -> group.name().equals("default"))
            .findFirst().orElseThrow().inherited());
    }

    @Test
    public void unsetFallsBackAndWildcardDenyOverridesPaperDefault() {
        YamlConfiguration groups = groups();
        PermissionResolver.Resolution fallback = PermissionResolver.resolve(groups,
            List.of("default"), Map.of(), "rivet.home", true);
        assertTrue(fallback.value());
        assertNull(fallback.winner());

        permission(groups, "default", "rivet.*", false);
        PermissionResolver.Resolution denied = PermissionResolver.resolve(groups,
            List.of("default"), Map.of(), "rivet.home", true);
        assertFalse(denied.value());
        assertEquals("rivet.*", denied.winner().node());
    }

    @Test
    public void supportsMultipleParentsMetadataAndReadableTrees() {
        YamlConfiguration groups = groups();
        createGroup(groups, "builder", 25, List.of("default"));
        createGroup(groups, "moderator", 40, List.of("default"));
        createGroup(groups, "lead", 80, List.of("builder", "moderator"));
        groups.set("groups/lead/meta/prefix", "<red>[Lead] </red>");

        List<PermissionResolver.GroupRef> memberships = PermissionResolver.memberships(
            groups, List.of("lead"));
        assertEquals(List.of("lead", "builder", "default", "moderator"),
            memberships.stream().map(PermissionResolver.GroupRef::name).toList());
        assertEquals("<red>[Lead] </red>",
            PermissionResolver.effectiveMeta(groups, memberships, "prefix"));
        assertEquals(List.of("• lead (weight 80)", "  • builder (weight 25)",
            "    • default (weight 0)", "  • moderator (weight 40)",
            "    • default (weight 0)"), PermissionResolver.treeLines(groups, List.of("lead")));
        assertTrue(PermissionResolver.wouldCreateCycle(groups, "default", "lead"));
    }

    @Test
    public void migratesLegacyGroupsAndUsersWithoutDroppingAssignments() throws Exception {
        YamlConfiguration groups = new YamlConfiguration();
        groups.set("groups.default.permissions", List.of("rivet.message"));
        groups.set("groups.staff.parent", "default");
        groups.set("groups.staff.permissions", List.of("rivet.vanish", "rivet.world.*"));
        assertTrue(PermissionModule.migrateGroups(groups));
        assertEquals(50, groups.getInt("groups/staff/weight"));
        assertEquals(List.of("default"), groups.getStringList("groups/staff/parents"));
        assertEquals(Map.of("rivet.vanish", true, "rivet.world.*", true),
            PermissionResolver.groupPermissions(groups, "staff"));
        assertFalse(groups.contains("groups/staff/parent"));

        UUID alex = UUID.randomUUID();
        YamlConfiguration users = new YamlConfiguration();
        users.set("users." + alex + ".name", "Alex");
        users.set("users." + alex + ".group", "staff");
        users.set("users." + alex + ".permissions", List.of("rivet.fly", "rivet.home"));
        assertTrue(PermissionModule.migrateUsers(users));
        assertEquals(List.of("staff"), users.getStringList("users/" + alex + "/groups"));
        assertEquals(Map.of("rivet.fly", true, "rivet.home", true),
            PermissionResolver.permissions(users, "users/" + alex + "/permissions"));
        assertFalse(users.contains("users/" + alex + "/group"));
        PermissionModule.validate(groups, users);

        String saved = groups.saveToString();
        assertTrue(saved.contains("rivet.vanish: true"));
        assertTrue(saved.contains("rivet.world.*: true"));
    }

    private static YamlConfiguration groups() {
        YamlConfiguration groups = new YamlConfiguration();
        groups.options().pathSeparator('/');
        createGroup(groups, "default", 0, List.of());
        createGroup(groups, "staff", 50, List.of("default"));
        createGroup(groups, "admin", 100, List.of("staff"));
        return groups;
    }

    private static void createGroup(YamlConfiguration groups, String name, int weight,
                                    List<String> parents) {
        String base = "groups/" + name;
        groups.createSection(base);
        groups.set(base + "/weight", weight);
        groups.set(base + "/parents", parents);
        groups.createSection(base + "/permissions");
        groups.set(base + "/meta/prefix", "");
        groups.set(base + "/meta/suffix", "");
    }

    private static void permission(YamlConfiguration groups, String group, String node,
                                   boolean value) {
        groups.getConfigurationSection("groups/" + group + "/permissions").set(node, value);
    }
}
