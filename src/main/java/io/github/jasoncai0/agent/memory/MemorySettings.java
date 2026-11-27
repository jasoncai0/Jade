package io.github.jasoncai0.agent.memory;

import io.github.jasoncai0.agent.memory.admission.AdmissionMode;

import java.net.URI;
import java.time.Duration;

/** Immutable Mem0 client and admission v2 settings. */
public record MemorySettings(
        MemoryMode mode,
        String provider,
        String baseUrl,
        String apiKey,
        String userId,
        int topK,
        int recallTimeoutMs,
        int writeTimeoutMs,
        boolean telemetry,
        String projectId,
        AdmissionMode admissionMode,
        double autoThreshold,
        double reviewThreshold,
        int maxWritesPerRun,
        int admissionTimeoutMs
) {
    public static final String DEFAULT_PROVIDER = "mem0-oss";
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8888";
    public static final String DEFAULT_USER_ID = "jade-local-user";
    public static final int DEFAULT_TOP_K = 5;
    public static final int DEFAULT_RECALL_TIMEOUT_MS = 500;
    public static final int DEFAULT_WRITE_TIMEOUT_MS = 2_000;
    public static final double DEFAULT_AUTO_THRESHOLD = 0.85;
    public static final double DEFAULT_REVIEW_THRESHOLD = 0.60;
    public static final int DEFAULT_MAX_WRITES_PER_RUN = 3;
    public static final int DEFAULT_ADMISSION_TIMEOUT_MS = 2_000;

    public MemorySettings(MemoryMode mode, String provider, String baseUrl, String apiKey,
                          String userId, int topK, int recallTimeoutMs, int writeTimeoutMs,
                          boolean telemetry) {
        this(mode, provider, baseUrl, apiKey, userId, topK, recallTimeoutMs, writeTimeoutMs,
                telemetry, "", AdmissionMode.HYBRID, DEFAULT_AUTO_THRESHOLD,
                DEFAULT_REVIEW_THRESHOLD, DEFAULT_MAX_WRITES_PER_RUN, DEFAULT_ADMISSION_TIMEOUT_MS);
    }

    public MemorySettings(MemoryMode mode, String provider, String baseUrl, String apiKey,
                          String userId, int topK, int recallTimeoutMs, int writeTimeoutMs,
                          boolean telemetry, String projectId) {
        this(mode, provider, baseUrl, apiKey, userId, topK, recallTimeoutMs, writeTimeoutMs,
                telemetry, projectId, AdmissionMode.HYBRID, DEFAULT_AUTO_THRESHOLD,
                DEFAULT_REVIEW_THRESHOLD, DEFAULT_MAX_WRITES_PER_RUN, DEFAULT_ADMISSION_TIMEOUT_MS);
    }

    public MemorySettings {
        if (mode == null) throw new IllegalArgumentException("memory mode is required");
        provider = provider == null || provider.isBlank() ? DEFAULT_PROVIDER : provider.trim();
        baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.trim();
        userId = userId == null || userId.isBlank() ? DEFAULT_USER_ID : userId.trim();
        projectId = projectId == null ? "" : projectId.trim();
        admissionMode = admissionMode == null ? AdmissionMode.HYBRID : admissionMode;
        if (topK < 1 || topK > 20) throw new IllegalArgumentException("AGENT_MEMORY_TOP_K must be 1..20");
        if (recallTimeoutMs < 50 || recallTimeoutMs > 10_000) {
            throw new IllegalArgumentException("AGENT_MEMORY_RECALL_TIMEOUT_MS must be 50..10000");
        }
        if (writeTimeoutMs < 100 || writeTimeoutMs > 30_000) {
            throw new IllegalArgumentException("AGENT_MEMORY_WRITE_TIMEOUT_MS must be 100..30000");
        }
        if (Double.isNaN(autoThreshold) || autoThreshold < 0.0 || autoThreshold > 1.0) {
            throw new IllegalArgumentException("AGENT_MEMORY_AUTO_THRESHOLD must be 0.0..1.0");
        }
        if (Double.isNaN(reviewThreshold) || reviewThreshold < 0.0 || reviewThreshold > 1.0) {
            throw new IllegalArgumentException("AGENT_MEMORY_REVIEW_THRESHOLD must be 0.0..1.0");
        }
        if (reviewThreshold > autoThreshold) {
            throw new IllegalArgumentException("AGENT_MEMORY_REVIEW_THRESHOLD must not exceed AGENT_MEMORY_AUTO_THRESHOLD");
        }
        if (maxWritesPerRun < 1 || maxWritesPerRun > 10) {
            throw new IllegalArgumentException("AGENT_MEMORY_MAX_WRITES_PER_RUN must be 1..10");
        }
        if (admissionTimeoutMs < 100 || admissionTimeoutMs > 30_000) {
            throw new IllegalArgumentException("AGENT_MEMORY_ADMISSION_TIMEOUT_MS must be 100..30000");
        }
    }

    public static MemorySettings defaults() {
        return new MemorySettings(MemoryMode.ON, DEFAULT_PROVIDER, DEFAULT_BASE_URL, "",
                DEFAULT_USER_ID, DEFAULT_TOP_K, DEFAULT_RECALL_TIMEOUT_MS,
                DEFAULT_WRITE_TIMEOUT_MS, true, "", AdmissionMode.HYBRID,
                DEFAULT_AUTO_THRESHOLD, DEFAULT_REVIEW_THRESHOLD,
                DEFAULT_MAX_WRITES_PER_RUN, DEFAULT_ADMISSION_TIMEOUT_MS);
    }

    public boolean active() {
        return mode.active() && !baseUrl.isBlank();
    }

    public URI uri(String path) {
        String suffix = path.startsWith("/") ? path : "/" + path;
        return URI.create(baseUrl + suffix);
    }

    public Duration recallTimeout() {
        return Duration.ofMillis(recallTimeoutMs);
    }

    public Duration writeTimeout() {
        return Duration.ofMillis(writeTimeoutMs);
    }
}
