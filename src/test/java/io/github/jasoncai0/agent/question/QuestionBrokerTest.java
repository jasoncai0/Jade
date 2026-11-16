package io.github.jasoncai0.agent.question;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionBrokerTest {
    @Test
    void resolvesSingleChoiceAndRejectsInvalidSelection() {
        QuestionBroker broker = new QuestionBroker();
        QuestionRequest request = QuestionRequest.create("run-1", "Which API?", "",
                QuestionMode.SINGLE, List.of(new QuestionOption("opt-1", "REST"),
                        new QuestionOption("opt-2", "GraphQL")), 60_000);
        broker.register(request);
        assertThrows(IllegalArgumentException.class,
                () -> broker.resolve(request.id(), List.of("unknown"), ""));
        assertThrows(IllegalArgumentException.class,
                () -> broker.resolve(request.id(), List.of("opt-1", "opt-2"), ""));
        QuestionAnswer answer = broker.resolve(request.id(), List.of("opt-1"), "");
        assertNotNull(answer);
        assertEquals(List.of("opt-1"), answer.selected());
        assertTrue(answer.answered());
        assertNull(broker.resolve(request.id(), List.of("opt-1"), ""));
    }


    @Test
    void resolvesMultipleChoices() {
        QuestionBroker broker = new QuestionBroker();
        QuestionRequest request = QuestionRequest.create("run-3", "Pick features", "",
                QuestionMode.MULTIPLE, List.of(new QuestionOption("opt-1", "Auth"),
                        new QuestionOption("opt-2", "Audit")), 60_000);
        broker.register(request);
        QuestionAnswer answer = broker.resolve(request.id(), List.of("opt-1", "opt-2"), "");
        assertNotNull(answer);
        assertEquals(List.of("opt-1", "opt-2"), answer.selected());
    }
    @Test
    void requiresTextForTextModeAndTimesOut() {
        QuestionBroker broker = new QuestionBroker();
        QuestionRequest request = QuestionRequest.create("run-2", "What name?", "user input",
                QuestionMode.TEXT, List.of(), 50);
        broker.register(request);
        assertThrows(IllegalArgumentException.class,
                () -> broker.resolve(request.id(), List.of(), "  "));
        QuestionAnswer answer = broker.await(request, Duration.ofMillis(120));
        assertTrue(answer.timedOut());
        assertFalse(answer.answered());
    }
}
