package io.github.jasoncai0.agent.history;

import io.github.jasoncai0.agent.agent.AgentEvent;
import io.github.jasoncai0.agent.config.MysqlSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MySqlEventStoreTest {
    private static final String RUN_ID = "test-" + UUID.randomUUID();
    private static MySqlEventStore store;
    private static MysqlSettings settings;

    @BeforeAll
    static void connect() {
        settings = new MysqlSettings(MysqlSettings.DEFAULT_URL, "jade", "jade_dev_password");
        try (Connection ignored = DriverManager.getConnection(settings.url(), settings.user(), settings.password())) {
            store = new MySqlEventStore(settings);
        } catch (Exception e) {
            Assumptions.abort("MySQL test database is not available: " + e.getMessage());
        }
    }

    @AfterAll
    static void cleanup() {
        if (store != null) store.close();
        if (settings == null) return;
        try (Connection c = DriverManager.getConnection(settings.url(), settings.user(), settings.password());
             Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM jade_events WHERE run_id='" + RUN_ID + "'");
            st.executeUpdate("DELETE FROM jade_sessions WHERE run_id='" + RUN_ID + "'");
        } catch (Exception ignored) {
        }
    }

    @Test
    void pagesNewestFirstInAscendingOrderAndOmitsFullLlmBody() {
        store.append(RUN_ID, new AgentEvent(1, 1000, "run.started", Map.of("task", "test")));
        store.append(RUN_ID, new AgentEvent(2, 1001, "llm.request", Map.of(
                "step", 1, "body", "{\"model\":\"deepseek-chat\",\"messages\":[" + "x".repeat(5000) + "]}")));
        store.append(RUN_ID, new AgentEvent(3, 1002, "tool.end", Map.of("name", "read_file", "ok", true)));

        EventStore.Page first = store.page(RUN_ID, 0, 2, true);
        assertEquals(List.of(2L, 3L), first.events().stream().map(AgentEvent::seq).toList());
        assertTrue(first.hasMore());
        assertEquals(2, first.nextBefore());
        assertEquals(3, first.lastSeq());
        assertEquals(3, first.eventCount());

        AgentEvent llm = first.events().get(0);
        assertFalse(llm.data().containsKey("body"));
        assertTrue(llm.data().get("bodyOmitted") instanceof Boolean b && b);
        assertTrue(((Number) llm.data().get("bodySize")).intValue() >= 5000);
        assertTrue(((String) llm.data().get("bodyPreview")).startsWith("{\"model\""));

        EventStore.Page older = store.page(RUN_ID, first.nextBefore(), 2, true);
        assertEquals(List.of(1L), older.events().stream().map(AgentEvent::seq).toList());
        assertFalse(older.hasMore());
        assertEquals(1, older.nextBefore());
    }

    @Test
    void returnsFullPayloadOnlyOnExplicitEventLookup() {
        EventStore.Page page = store.page(RUN_ID, 0, 10, true);
        AgentEvent compactLlm = page.events().stream().filter(e -> e.type().equals("llm.request")).findFirst().orElseThrow();
        AgentEvent full = store.event(RUN_ID, compactLlm.seq()).orElseThrow();
        assertTrue(((String) full.data().get("body")).contains("deepseek-chat"));
    }
}
