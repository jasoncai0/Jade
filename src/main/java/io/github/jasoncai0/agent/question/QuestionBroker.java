package io.github.jasoncai0.agent.question;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Coordinates ask_user requests between a blocked agent thread and the Web/CLI.
 * A question has at most one answer; resolving a stale id returns null.
 */
public final class QuestionBroker {
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public void register(QuestionRequest request) {
        if (closed) {
            throw new IllegalStateException("Question broker is closed");
        }
        pending.put(request.id(), new Pending(request, new CompletableFuture<>()));
    }

    public QuestionAnswer await(QuestionRequest request, Duration timeout) {
        Pending item = pending.get(request.id());
        if (item == null) {
            return QuestionAnswer.cancelled(request.id());
        }
        try {
            return item.future().get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return QuestionAnswer.cancelled(request.id());
        } catch (ExecutionException e) {
            return QuestionAnswer.cancelled(request.id());
        } catch (TimeoutException e) {
            return QuestionAnswer.timeout(request.id());
        } finally {
            pending.remove(request.id());
        }
    }

    /**
     * Resolves a pending question after validating the answer against its mode.
     * Returns null when the id is not pending.
     */
    public QuestionAnswer resolve(String requestId, List<String> selected, String text) {
        Pending item = pending.remove(requestId);
        if (item == null) {
            return null;
        }
        QuestionRequest request = item.request();
        List<String> clean = selected == null ? List.of() : selected.stream()
                .filter(s -> s != null && !s.isBlank()).map(String::strip).distinct().toList();
        String answerText = text == null ? "" : text.strip();
        if (request.mode() == QuestionMode.TEXT) {
            if (answerText.isEmpty()) {
                pending.put(requestId, item);
                throw new IllegalArgumentException("A text answer is required");
            }
            clean = List.of();
        } else {
            if (clean.isEmpty()) {
                pending.put(requestId, item);
                throw new IllegalArgumentException("Select at least one option");
            }
            if (request.mode() == QuestionMode.SINGLE && clean.size() != 1) {
                pending.put(requestId, item);
                throw new IllegalArgumentException("Select exactly one option");
            }
            boolean known = clean.stream().allMatch(id -> request.options().stream()
                    .anyMatch(option -> option.id().equals(id)));
            if (!known) {
                pending.put(requestId, item);
                throw new IllegalArgumentException("Unknown option selected");
            }
            answerText = "";
        }
        QuestionAnswer answer = QuestionAnswer.answer(requestId, clean, answerText);
        item.future().complete(answer);
        return answer;
    }

    public QuestionRequest pendingFor(String runId) {
        return pending.values().stream().map(Pending::request)
                .filter(request -> request.runId().equals(runId))
                .findFirst().orElse(null);
    }

    public List<QuestionRequest> pending() {
        List<QuestionRequest> result = new ArrayList<>();
        pending.values().forEach(item -> result.add(item.request()));
        return result;
    }

    public void cancelAll(String reason) {
        for (String id : List.copyOf(pending.keySet())) {
            Pending item = pending.remove(id);
            if (item != null) {
                item.future().complete(QuestionAnswer.cancelled(id));
            }
        }
    }

    public void close() {
        closed = true;
        cancelAll("broker closed");
    }

    private record Pending(QuestionRequest request, CompletableFuture<QuestionAnswer> future) {
    }
}
