package org.qainsights.jmeter.ai.agent.skills;

import java.time.Instant;

/**
 * Information about a skill.
 */
public class SkillInfo {
    private final String name;
    private final String path;
    private final SkillSource source;
    private final String description;
    private final boolean always;
    private final boolean available;
    private final Instant lastModified;

    public SkillInfo(String name, String path, SkillSource source, String description,
                     boolean always, boolean available, Instant lastModified) {
        this.name = name;
        this.path = path;
        this.source = source;
        this.description = description;
        this.always = always;
        this.available = available;
        this.lastModified = lastModified;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public SkillSource getSource() {
        return source;
    }

    public String getDescription() {
        return description;
    }

    public boolean isAlways() {
        return always;
    }

    public boolean isAvailable() {
        return available;
    }

    public Instant getLastModified() {
        return lastModified;
    }

    public enum SkillSource {
        BUILTIN,
        WORKSPACE
    }
}
