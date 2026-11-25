package io.github.jasoncai0.agent.team;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamTaskStoreTest {
    @Test
    void createsClaimsAndCasUpdates() {
        TeamTaskStore store = new InMemoryTeamTaskStore();
        store.ensureTeam("root", true);
        TeamTask task = store.create("root", "Inspect", "read files", List.of(), List.of("src"));
        assertEquals("task-1", task.id());
        assertEquals(1, task.revision());
        assertEquals(TeamTaskStatus.PENDING, task.status());

        TeamTask claimed = task.ownedBy("run-a", "reviewer", TeamTaskStatus.IN_PROGRESS, 1);
        TeamTask saved = store.save("root", task, claimed);
        assertNotNull(saved);
        assertEquals(2, saved.revision());
        assertEquals("reviewer", saved.ownerName());

        assertNull(store.save("root", task, claimed), "stale revision must not commit");
    }

    @Test
    void supportsDependenciesAndTombstones() {
        TeamTaskStore store = new InMemoryTeamTaskStore();
        store.ensureTeam("root", true);
        TeamTask first = store.create("root", "First", "", List.of(), List.of());
        TeamTask second = store.create("root", "Second", "", List.of(first.id()), List.of());
        assertEquals(List.of("task-1"), second.blockedBy());

        TeamTask completed = first.withStatus(TeamTaskStatus.COMPLETED, 1);
        assertNotNull(store.save("root", first, completed));
        TeamTask deleted = second.withStatus(TeamTaskStatus.DELETED, 1);
        assertNotNull(store.save("root", second, deleted));
        assertEquals(2, store.list("root").size());
    }

    @Test
    void togglesTeamAndPersistsAcrossOperations() {
        TeamTaskStore store = new InMemoryTeamTaskStore();
        store.ensureTeam("root", false);
        assertFalse(store.enabled("root"));
        store.setEnabled("root", true);
        assertTrue(store.enabled("root"));
        store.setEnabled("root", false);
        assertFalse(store.enabled("root"));
    }
}
