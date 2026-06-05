package org.gitee.jmeter.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Retry policy for API calls with exponential backoff.
 * Based on Nanobot's retry strategy for resilient API communication.
 */
public class RetryPolicy {
    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    private final int maxAttempts;
    private final long initialDelayMs;
    private final double backoffMultiplier;
    private final double maxDelayMs;

    private RetryPolicy(int maxAttempts, long initialDelayMs, double backoffMultiplier, double maxDelayMs) {
        this.maxAttempts = maxAttempts;
        this.initialDelayMs = initialDelayMs;
        this.backoffMultiplier = backoffMultiplier;
        this.maxDelayMs = maxDelayMs;
    }

    /**
     * Execute operation with retry logic.
     *
     * @param operation The operation to execute
     * @param <T> Return type
     * @return Result of the operation
     * @throws Exception if all retries fail
     */
    public <T> T execute(Supplier<T> operation) throws Exception {
        Exception lastException = null;
        long delay = initialDelayMs;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return operation.get();
            } catch (Exception e) {
                lastException = e;

                // Don't retry on certain errors
                if (!shouldRetry(e)) {
                    log.debug("Error is not retryable: {}", e.getMessage());
                    throw e;
                }

                if (attempt < maxAttempts) {
                    log.warn("Attempt {}/{} failed, retrying in {}ms: {}",
                            attempt, maxAttempts, delay, e.getMessage());
                    Thread.sleep(delay);
                    delay = Math.min((long) (delay * backoffMultiplier), (long) maxDelayMs);
                } else {
                    log.error("All {} retry attempts failed", maxAttempts);
                }
            }
        }

        throw lastException;
    }

    /**
     * Execute operation with retry logic (void version).
     */
    public void execute(Runnable operation) throws Exception {
        execute(() -> {
            operation.run();
            return null;
        });
    }

    /**
     * Determine if an exception is retryable.
     */
    private boolean shouldRetry(Exception e) {
        String message = e.getMessage();

        // Don't retry on authentication errors
        if (message != null) {
            if (message.contains("invalid_api_key") ||
                message.contains("authentication") ||
                message.contains("unauthorized") ||
                message.contains("401")) {
                return false;
            }
        }

        // Retry on rate limits, timeouts, and server errors
        if (message != null) {
            return message.contains("rate_limit") ||
                   message.contains("timeout") ||
                   message.contains("503") ||
                   message.contains("502") ||
                   message.contains("500") ||
                   message.contains("connection") ||
                   message.contains("temporarily unavailable");
        }

        // Retry on IO exceptions
        return e instanceof java.io.IOException;
    }

    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for RetryPolicy.
     */
    public static class Builder {
        private int maxAttempts = 3;
        private long initialDelayMs = 1000;
        private double backoffMultiplier = 2.0;
        private double maxDelayMs = 10000;

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder initialDelayMs(long initialDelayMs) {
            this.initialDelayMs = initialDelayMs;
            return this;
        }

        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        public Builder maxDelayMs(double maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(maxAttempts, initialDelayMs, backoffMultiplier, maxDelayMs);
        }
    }

    /**
     * Default retry policy for API calls.
     */
    public static RetryPolicy defaultPolicy() {
        return builder().build();
    }

    /**
     * No retry policy.
     */
    public static RetryPolicy noRetry() {
        return builder().maxAttempts(1).build();
    }
}
