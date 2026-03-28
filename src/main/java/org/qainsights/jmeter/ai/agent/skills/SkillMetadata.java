package org.qainsights.jmeter.ai.agent.skills;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata extracted from a skill's frontmatter.
 */
public class SkillMetadata {
    private String name;
    private String description;
    private boolean always;
    private final List<String> requiredBinaries = new ArrayList<>();
    private final List<String> requiredEnvVars = new ArrayList<>();

    public SkillMetadata() {
        this.description = "";
        this.always = false;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description != null ? description : "";
    }

    public boolean isAlways() {
        return always;
    }

    public void setAlways(boolean always) {
        this.always = always;
    }

    public List<String> getRequiredBinaries() {
        return requiredBinaries;
    }

    public void addRequiredBinary(String binary) {
        requiredBinaries.add(binary);
    }

    public List<String> getRequiredEnvVars() {
        return requiredEnvVars;
    }

    public void addRequiredEnvVar(String envVar) {
        requiredEnvVars.add(envVar);
    }

    public String getMissingRequirements() {
        List<String> missing = new ArrayList<>();

        for (String binary : requiredBinaries) {
            if (!isBinaryAvailable(binary)) {
                missing.add("CLI: " + binary);
            }
        }

        for (String envVar : requiredEnvVars) {
            if (System.getenv(envVar) == null) {
                missing.add("ENV: " + envVar);
            }
        }

        return String.join(", ", missing);
    }

    private boolean isBinaryAvailable(String binary) {
        try {
            Process process = Runtime.getRuntime().exec(
                    System.getProperty("os.name").toLowerCase().contains("win")
                            ? new String[]{"where", binary}
                            : new String[]{"which", binary}
            );
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
