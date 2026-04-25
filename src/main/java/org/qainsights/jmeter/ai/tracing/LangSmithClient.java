package org.qainsights.jmeter.ai.tracing;

import com.langchain.smith.client.LangsmithClient;
import com.langchain.smith.client.okhttp.LangsmithOkHttpClient;
import com.langchain.smith.models.runs.Run;
import com.langchain.smith.services.blocking.RunService;
import com.langchain.smith.core.JsonValue;
import org.qainsights.jmeter.ai.utils.AiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Client for sending traces to LangSmith using the official SDK.
 *
 * Configuration:
 * - langsmith.api.key: LangSmith API key (required)
 * - langsmith.project.name: Project name (default: "jmeter-ai")
 * - langsmith.endpoint: API endpoint (reads from environment or uses default)
 * - langsmith.enabled: Enable/disable tracing (default: true)
 * - langsmith.sample.rate: Sampling rate 0.0-1.0 (default: 1.0 = trace all)
 */
public class LangSmithClient {
    private static final Logger log = LoggerFactory.getLogger(LangSmithClient.class);

    private static final String DEFAULT_PROJECT = "jmeter-ai";
    private static final double DEFAULT_SAMPLE_RATE = 1.0;

    private final String projectName;
    private final boolean enabled;
    private final double sampleRate;
    private final RunService runService;
    private final Random random = new Random();

    private LangSmithClient() {
        this.projectName = AiConfig.getProperty("langsmith.project.name", DEFAULT_PROJECT);
        this.enabled = Boolean.parseBoolean(AiConfig.getProperty("langsmith.enabled", "true"));
        this.sampleRate = Double.parseDouble(AiConfig.getProperty("langsmith.sample.rate", String.valueOf(DEFAULT_SAMPLE_RATE)));

        RunService service = null;
        String apiKey = AiConfig.getProperty("langsmith.api.key", "");

        if (enabled && !apiKey.isEmpty()) {
            try {
                // Build client using the official SDK
                LangsmithOkHttpClient.Builder clientBuilder = LangsmithOkHttpClient.builder();
                // Set API key explicitly
                clientBuilder.apiKey(apiKey);

                LangsmithClient client = clientBuilder.build();
                service = client.runs();

                log.info("LangSmith tracing initialized: project={}, sample.rate={}", projectName, sampleRate);
            } catch (Exception e) {
                log.error("Failed to initialize LangSmith client", e);
            }
        } else {
            if (enabled && apiKey.isEmpty()) {
                log.warn("LangSmith tracing is enabled but no API key is configured. " +
                        "Set 'langsmith.api.key' in properties to enable tracing.");
            } else {
                log.info("LangSmith tracing is disabled");
            }
        }

        this.runService = service;
    }

    private static final class InstanceHolder {
        private static final LangSmithClient INSTANCE = new LangSmithClient();
    }

    public static LangSmithClient getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Create a new trace for an LLM run.
     *
     * @param runId Unique ID for this run
     * @param name Name of the run (e.g., model name or operation)
     * @param inputs Input data (e.g., prompt, messages)
     * @return LLMRun that can be updated with results later
     */
    public LLMRun createRun(String runId, String name, Map<String, Object> inputs) {
        return createRun(runId, name, inputs, null);
    }

    /**
     * Create a new trace for an LLM run with tags.
     *
     * @param runId Unique ID for this run
     * @param name Name of the run (e.g., model name or operation)
     * @param inputs Input data (e.g., prompt, messages)
     * @param tags Optional tags for filtering in LangSmith dashboard
     * @return LLMRun that can be updated with results later
     */
    public LLMRun createRun(String runId, String name, Map<String, Object> inputs, List<String> tags) {
        if (!isEnabled() || !shouldSample()) {
            log.debug("LangSmith disabled or sampled out, skipping trace");
            return new LLMRun(runId, name, this, false);
        }

        try {
            // Build inputs properly using Run.Inputs
            Map<String, JsonValue> inputMap = new HashMap<>();
            if (inputs != null && !inputs.isEmpty()) {
                for (Map.Entry<String, Object> entry : inputs.entrySet()) {
                    inputMap.put(entry.getKey(), JsonValue.from(entry.getValue()));
                }
            }
            Run.Inputs inputsObj = null;
            if (!inputMap.isEmpty()) {
                inputsObj = new Run.Inputs.Builder()
                        .additionalProperties(inputMap)
                        .build();
            }

            // Create Run object
            Run.Builder runBuilder = new Run.Builder()
                    .id(runId)
                    .name(name)
                    .startTime(OffsetDateTime.now().toString())
                    .runType(Run.RunType.LLM);

            if (inputsObj != null) {
                runBuilder.inputs(inputsObj);
            }

            if (tags != null && !tags.isEmpty()) {
                runBuilder.tags(tags);
            }

            Run run = runBuilder.build();

            log.info("Creating LangSmith run: id={}, name={}, project={}, inputs={}",
                    runId, name, projectName, inputMap.keySet());

            // Use RunCreateParams with query params for project name
            com.langchain.smith.models.runs.RunCreateParams.Builder paramsBuilder =
                new com.langchain.smith.models.runs.RunCreateParams.Builder()
                    .run(run);

            // Add project name as query parameter
            com.langchain.smith.core.http.QueryParams.Builder queryParamsBuilder =
                new com.langchain.smith.core.http.QueryParams.Builder()
                    .put("project_name", projectName);
            paramsBuilder.additionalQueryParams(queryParamsBuilder.build());

            com.langchain.smith.models.runs.RunCreateParams createParams = paramsBuilder.build();

            // Create the run via SDK
            var response = runService.create(createParams);
            log.info("LangSmith run created successfully: runId={}", runId);

            return new LLMRun(runId, name, this, true);

        } catch (Exception e) {
            log.error("Failed to create LangSmith run: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e);
            return new LLMRun(runId, name, this, false);
        }
    }

    /**
     * Create a new run with auto-generated ID.
     */
    public LLMRun createRun(String name, Map<String, Object> inputs) {
        String runId = UUID.randomUUID().toString();
        return createRun(runId, name, inputs, null);
    }

    /**
     * Create a new run with auto-generated ID and tags.
     */
    public LLMRun createRun(String name, Map<String, Object> inputs, List<String> tags) {
        String runId = UUID.randomUUID().toString();
        return createRun(runId, name, inputs, tags);
    }

    /**
     * Update an existing run with results.
     */
    void updateRun(String runId, Map<String, Object> outputs, String status, String error) {
        if (!isEnabled() || runId == null) {
            log.debug("LangSmith update skipped: enabled={}, runId={}", isEnabled(), runId);
            return;
        }

        try {
            // Build Run with updated values
            Run.Builder runBuilder = new Run.Builder()
                    .id(runId)
                    .endTime(OffsetDateTime.now().toString())
                    .status(status);

            // Add error if present
            if (error != null && !error.isEmpty()) {
                runBuilder.error(error);
            }

            // Build outputs properly using Run.Outputs
            if (outputs != null && !outputs.isEmpty()) {
                Map<String, JsonValue> outputMap = new HashMap<>();
                for (Map.Entry<String, Object> entry : outputs.entrySet()) {
                    outputMap.put(entry.getKey(), JsonValue.from(entry.getValue()));
                }

                Run.Outputs outputsObj = new Run.Outputs.Builder()
                        .additionalProperties(outputMap)
                        .build();

                runBuilder.outputs(outputsObj);
            }

            log.info("Updating LangSmith run: runId={}, status={}, outputs={}",
                    runId, status, outputs != null ? outputs.keySet() : "none");

            // Build RunUpdateParams with the Run
            com.langchain.smith.models.runs.RunUpdateParams updateParams =
                new com.langchain.smith.models.runs.RunUpdateParams.Builder()
                    .run(runBuilder.build())
                    .build();

            // Update via SDK
            runService.update(runId, updateParams);
            log.info("LangSmith run updated successfully: runId={}", runId);

        } catch (Exception e) {
            log.error("Failed to update LangSmith run: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    /**
     * Check if this run should be sampled based on the sample rate.
     */
    private boolean shouldSample() {
        return random.nextDouble() < sampleRate;
    }

    /**
     * Check if LangSmith tracing is enabled and configured.
     */
    public boolean isEnabled() {
        return enabled && runService != null;
    }

    /**
     * Get the sample rate for tracing.
     */
    public double getSampleRate() {
        return sampleRate;
    }

    /**
     * Represents a single LLM run that can be updated with results.
     */
    public static class LLMRun {
        private final String runId;
        private final String name;
        private final LangSmithClient client;
        private final boolean active;
        private final long startTime;

        LLMRun(String runId, String name, LangSmithClient client, boolean active) {
            this.runId = runId;
            this.name = name;
            this.client = client;
            this.active = active;
            this.startTime = System.currentTimeMillis();
        }

        /**
         * Complete the run successfully.
         *
         * @param outputs Output data (e.g., response, token usage)
         */
        public void complete(Map<String, Object> outputs) {
            if (active) {
                long duration = System.currentTimeMillis() - startTime;
                outputs.put("_duration_ms", duration);
                client.updateRun(runId, outputs, "success", null);
            }
        }

        /**
         * Complete the run with an error.
         *
         * @param error Error message
         */
        public void error(String error) {
            if (active) {
                client.updateRun(runId, Map.of(), "error", error);
            }
        }

        public String getRunId() {
            return runId;
        }

        public String getName() {
            return name;
        }

        public boolean isActive() {
            return active;
        }
    }

    /**
     * Convert a list of conversation strings to LangSmith message format.
     */
    public static Map<String, Object> formatConversation(java.util.List<String> conversation) {
        Map<String, Object> result = new HashMap<>();
        result.put("conversation", conversation);
        result.put("message_count", conversation.size());
        return result;
    }
}
