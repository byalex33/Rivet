package dev.rivet;

import org.bukkit.event.inventory.ClickType;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PollModuleTest {
    @Test
    public void validatesSafeSingleArgumentPollNames() {
        assertTrue(PollModule.validName("server_event-2"));
        assertFalse(PollModule.validName("server event"));
        assertFalse(PollModule.validName(""));
        assertFalse(PollModule.validName("x".repeat(33)));
    }

    @Test
    public void reportsOnlyPollsThePlayerHasNotAnswered() {
        UUID player = UUID.randomUUID();
        PollModule.Poll answered = poll("answered");
        PollModule.Poll waiting = poll("waiting");
        answered.votes().put(player, true);

        assertFalse(PollModule.hasUnvoted(List.of(answered), player));
        assertTrue(PollModule.hasUnvoted(List.of(answered, waiting), player));
        assertEquals(1, PollModule.unvotedCount(List.of(answered, waiting), player));
        assertFalse(PollModule.hasUnvoted(List.of(), player));
    }

    @Test
    public void wrapsDescriptionsWithoutDroppingWords() {
        assertEquals(List.of("A short", "poll", "description"),
            PollModule.wrap("A short poll description", 8));
    }

    @Test
    public void resolvesNamedYesAndNoPlaceholderApiTotals() {
        PollModule.Poll poll = poll("server_event");
        poll.votes().put(UUID.randomUUID(), true);
        poll.votes().put(UUID.randomUUID(), true);
        poll.votes().put(UUID.randomUUID(), false);

        assertEquals("2", PollModule.pollVotePlaceholder(List.of(poll),
            "poll_server_event_yes"));
        assertEquals("1", PollModule.pollVotePlaceholder(List.of(poll),
            "poll_SERVER_EVENT_no"));
        assertEquals(null, PollModule.pollVotePlaceholder(List.of(poll),
            "poll_missing_yes"));
        assertEquals(null, PollModule.pollVotePlaceholder(List.of(poll), "yes"));
    }

    @Test
    public void mapsLeftAndRightClicksDirectlyToYesAndNo() {
        assertEquals(Boolean.TRUE, PollModule.voteForClick(ClickType.LEFT));
        assertEquals(Boolean.TRUE, PollModule.voteForClick(ClickType.SHIFT_LEFT));
        assertEquals(Boolean.FALSE, PollModule.voteForClick(ClickType.RIGHT));
        assertEquals(Boolean.FALSE, PollModule.voteForClick(ClickType.SHIFT_RIGHT));
        assertEquals(null, PollModule.voteForClick(ClickType.MIDDLE));
    }

    @Test
    public void findsPollNamesCaseInsensitivelyForAdminDeletion() {
        PollModule.Poll poll = poll("server_event");

        assertEquals(poll, PollModule.findByName(List.of(poll), "SERVER_EVENT"));
        assertEquals(null, PollModule.findByName(List.of(poll), "missing"));
    }

    private static PollModule.Poll poll(String id) {
        return new PollModule.Poll(id, id, "Description", 1, new HashMap<>());
    }
}
