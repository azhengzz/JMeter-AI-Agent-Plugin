package org.gitee.jmeter.ai.usage;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.models.ModelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gitee.jmeter.ai.utils.AiConfig;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Class to track and provide Anthropic token usage information.
 */
public class AnthropicUsage {
    private static final Logger log = LoggerFactory.getLogger(AnthropicUsage.class);

    // Singleton instance
    private static final AnthropicUsage INSTANCE = new AnthropicUsage();

    // Anthropic client for API calls
    private AnthropicClient client;

    // Store usage history
    private final List<UsageRecord> usageHistory = new ArrayList<>();

    // Private constructor for singleton
    private AnthropicUsage() {
        initializeClient();
    }

    /**
     * Initialize the Anthropic client
     */
    private void initializeClient() {
        try {
            String apiKey = AiConfig.getProperty("anthropic.api.key", "");
            if (apiKey.isEmpty()) {
                log.warn("Anthropic API key is empty. Token usage information may not be accurate.");
            }

            // Initialize the client using the correct builder pattern
            client = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();

            log.info("Anthropic client initialized for usage tracking");
        } catch (Exception e) {
            log.error("Failed to initialize Anthropic client for usage tracking", e);
        }
    }

    /**
     * Get the singleton instance of AnthropicUsage.
     *
     * @return The singleton instance
     */
    public static AnthropicUsage getInstance() {
        return INSTANCE;
    }

    /**
     * Record usage from a Message response.
     *
     * @param message          The Message response from Anthropic
     * @param model            The model used for the completion
     * @param promptTokens     The number of prompt tokens (input)
     * @param completionTokens The number of completion tokens (output)
     */
    public void recordUsage(Message message, String model, long promptTokens, long completionTokens) {
        if (message == null) {
            log.warn("Unable to record usage - message is null");
            return;
        }

        try {
            long totalTokens = promptTokens + completionTokens;

            // Record usage
            UsageRecord record = new UsageRecord(
                    new Date(),
                    model,
                    promptTokens,
                    completionTokens,
                    totalTokens);

            usageHistory.add(record);
            log.info("Recorded usage: {}", record);
        } catch (Exception e) {
            log.error("Error recording usage", e);
        }
    }

    /**
     * Set the Anthropic client for usage tracking
     * 
     * @param client The Anthropic client to use
     */
    public void setClient(AnthropicClient client) {
        this.client = client;
        log.info("Anthropic client set for usage tracking");
    }

    /**
     * Get the last recorded prompt and completion tokens.
     * Returns [promptTokens, completionTokens] or [0, 0] if no history.
     */
    public long[] getLastRecordedUsage() {
        if (usageHistory.isEmpty()) {
            return new long[]{0, 0};
        }
        UsageRecord last = usageHistory.get(usageHistory.size() - 1);
        return new long[]{last.promptTokens, last.completionTokens};
    }

    /**
     * Class to store a single usage record.
     */
    private static class UsageRecord {
        private final Date timestamp;
        private final String model;
        private final long promptTokens;
        private final long completionTokens;
        private final long totalTokens;

        public UsageRecord(Date timestamp, String model, long promptTokens, long completionTokens,
                long totalTokens) {
            this.timestamp = timestamp;
            this.model = model;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        @Override
        public String toString() {
            return "UsageRecord{" +
                    "timestamp=" + timestamp +
                    ", model='" + model + '\'' +
                    ", promptTokens=" + promptTokens +
                    ", completionTokens=" + completionTokens +
                    ", totalTokens=" + totalTokens +
                    '}';
        }
    }
}
