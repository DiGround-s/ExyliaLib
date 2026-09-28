package net.exylia.lib.clan.internal;

import com.zeltuv.teams.api.ITeamPlugin;
import com.zeltuv.teams.api.cache.IMember;
import com.zeltuv.teams.api.cache.IOwner;
import com.zeltuv.teams.api.cache.IRole;
import com.zeltuv.teams.api.cache.ITeam;
import com.zeltuv.teams.api.manager.ITeamManager;
import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.clan.Clan;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether ExyliaLib still reads ZelTeams.
 *
 * <p>The fakes here implement ZelTeams' real published interfaces
 * ({@code com.github.Zeltuv:zelteams-api}), and a proxy only answers the
 * methods its interface declares. A name the provider reaches for by
 * reflection that the API no longer has therefore comes back empty and fails
 * an assertion, rather than failing silently on a live server — which is what
 * {@code getName} and {@code getTeamByName} did after ZelTeams removed them.
 */
class ZelTeamsProviderTest {

    private final Map<UUID, ITeam> teams = new LinkedHashMap<>();
    private final Map<UUID, ITeam> membership = new LinkedHashMap<>();

    private FakePlayer owner;
    private FakePlayer officer;
    private FakePlayer recruit;
    private FakePlayer rivalOwner;
    private FakePlayer ghost;
    private UUID redId;
    private UUID blueId;
    private ZelTeamsProvider provider;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();

        owner = new FakePlayer("Owner");
        officer = new FakePlayer("Officer");
        recruit = new FakePlayer("Recruit");
        rivalOwner = new FakePlayer("Blue");
        ghost = new FakePlayer("Ghost");
        FakeServer.online(owner.player(), officer.player());

        redId = UUID.randomUUID();
        blueId = UUID.randomUUID();
        team(redId, "RED", 250.5, false, List.of(blueId), id(owner), Map.of(id(officer), 1, id(recruit), 0));
        team(blueId, "BLUE", 0, false, List.of(redId), id(rivalOwner), Map.of());
        team(UUID.randomUUID(), "GRAY", 0, true, List.of(), id(ghost), Map.of());

        ITeamManager manager = fake(ITeamManager.class, Map.of(
                "getOfflinePlayerTeam", args -> Optional.ofNullable(membership.get((UUID) args[0])),
                "getTeam", args -> Optional.ofNullable(membership.get(
                        ((org.bukkit.entity.Player) args[0]).getUniqueId())),
                "getByTag", args -> teams.values().stream()
                        .filter(team -> team.getTag().equals(args[0])).findFirst(),
                "getCachedTeams", args -> teams));
        FakeServer.plugins((Plugin) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Plugin.class, ITeamPlugin.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getName" -> "ZelTeams";
                    case "isEnabled" -> true;
                    case "getTeamManager" -> manager;
                    default -> FakeServer.defaultValue(method.getReturnType());
                }));

        provider = ZelTeamsProvider.tryCreate();
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    @Test
    @DisplayName("the provider is active when ZelTeams is running")
    void detected() {
        assertTrue(provider.enabled());
    }

    @Test
    @DisplayName("a team reads with its tag as its name and its roles as ranks")
    void readsATeam() {
        Clan red = provider.clanOf(id(owner)).orElseThrow();
        assertEquals(redId.toString(), red.id());
        assertEquals("RED", red.name());
        assertEquals("RED", red.tag());
        assertEquals("RED", red.displayName());
        assertEquals(250.5, red.balance());
        assertEquals(0, red.level());
        assertTrue(red.isLeader(id(owner)));
        assertTrue(red.isModerator(id(officer)));
        assertTrue(red.isMember(id(recruit)));
        assertEquals(3, red.memberCount());
        assertEquals(2, red.onlineCount());
        assertEquals(java.util.Set.of(blueId.toString()), red.allies());
    }

    @Test
    @DisplayName("an online player's team is found through the Player overload")
    void onlineLookup() {
        assertEquals(redId.toString(), provider.clanOf(officer.player()).orElseThrow().id());
    }

    @Test
    @DisplayName("lookups by id, by tag and by an id that is really a tag")
    void lookups() {
        assertEquals(redId.toString(), provider.byId(redId.toString()).orElseThrow().id());
        assertEquals(blueId.toString(), provider.byTag("BLUE").orElseThrow().id());
        assertEquals(blueId.toString(), provider.byId("BLUE").orElseThrow().id());
        assertTrue(provider.byTag("GREEN").isEmpty());
        assertEquals(2, provider.all().size());
    }

    @Test
    @DisplayName("a disbanded team is no team")
    void closedTeam() {
        assertFalse(provider.hasClan(id(ghost)));
        assertTrue(provider.clanOf(id(ghost)).isEmpty());
        assertTrue(provider.byTag("GRAY").isEmpty());
    }

    @Test
    @DisplayName("membership and alliances are asked of ZelTeams")
    void relations() {
        UUID nobody = UUID.randomUUID();
        assertTrue(provider.hasClan(id(recruit)));
        assertFalse(provider.hasClan(nobody));
        assertTrue(provider.areInSameClan(id(owner), id(recruit)));
        assertFalse(provider.areInSameClan(id(owner), id(rivalOwner)));
        assertTrue(provider.areAllied(id(officer), id(rivalOwner)));
        assertFalse(provider.areAllied(id(owner), nobody));
        assertEquals(List.of(redId.toString()), List.copyOf(provider.alliesOf(blueId.toString())));
        assertTrue(provider.rivalsOf(redId.toString()).isEmpty());
        assertEquals(List.of(id(owner), id(officer)), List.copyOf(provider.onlineMembersOf(id(recruit))));
    }

    private void team(UUID teamId, String tag, double balance, boolean closed, List<UUID> allies,
                      UUID ownerId, Map<UUID, Integer> members) {
        List<IMember> all = new ArrayList<>();
        // ZelTeams lists the owner among the members, at priority -1.
        all.add(member(ownerId, -1));
        members.forEach((player, priority) -> all.add(member(player, priority)));
        IOwner teamOwner = fake(IOwner.class, Map.of("getUuid", args -> ownerId));
        ITeam team = fake(ITeam.class, Map.of(
                "getTeamUUID", args -> teamId,
                "getTag", args -> tag,
                "getDisplayName", args -> tag,
                "getBankBalance", args -> balance,
                "isClosed", args -> closed,
                "getOwner", args -> teamOwner,
                "getAllMembers", args -> all,
                "getAllyList", args -> allies,
                "isAlliedWith", args -> allies.contains(((ITeam) args[0]).getTeamUUID()),
                "getRank", args -> 1));
        teams.put(teamId, team);
        membership.put(ownerId, team);
        members.keySet().forEach(player -> membership.put(player, team));
    }

    private static IMember member(UUID player, int priority) {
        IRole role = fake(IRole.class, Map.of("getPriority", args -> priority));
        return fake(IMember.class, Map.of("getUuid", args -> player, "getRole", args -> role));
    }

    /** A fake of one real ZelTeams interface, answering only what is named. */
    private static <T> T fake(Class<T> type, Map<String, Function<Object[], Object>> answers) {
        return type.cast(Proxy.newProxyInstance(ZelTeamsProviderTest.class.getClassLoader(),
                new Class<?>[]{type}, (self, method, args) -> switch (method.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "toString" -> type.getSimpleName();
                    default -> {
                        Function<Object[], Object> answer = answers.get(method.getName());
                        yield answer != null ? answer.apply(args)
                                : FakeServer.defaultValue(method.getReturnType());
                    }
                }));
    }

    private static UUID id(FakePlayer player) {
        return player.player().getUniqueId();
    }
}
