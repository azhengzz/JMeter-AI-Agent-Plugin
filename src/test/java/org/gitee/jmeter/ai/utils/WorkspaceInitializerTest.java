package org.gitee.jmeter.ai.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WorkspaceInitializer's per-file sync semantics:
 * initialize must seed exactly the missing files and never touch
 * existing ones, so it is safe to call on every startup.
 */
public class WorkspaceInitializerTest {

    private static final String[] ALL_TEMPLATES = {
        "AGENTS.md", "SOUL.md", "USER.md", "TOOLS.md", "goal.md", "memory/MEMORY.md"
    };

    private static byte[] classpathTemplate(String name) throws IOException {
        try (InputStream is = WorkspaceInitializerTest.class.getClassLoader()
                .getResourceAsStream("templates/" + name)) {
            assertNotNull(is, "template missing from test classpath: " + name);
            return is.readAllBytes();
        }
    }

    private static void seedWorkspaceWith(Path workspace, String content) throws IOException {
        for (String name : ALL_TEMPLATES) {
            Path file = workspace.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        }
        Files.createDirectories(workspace.resolve("sessions"));
        Files.createDirectories(workspace.resolve("skills"));
        Files.createFile(workspace.resolve("memory/HISTORY.md"));
    }

    @Test
    public void freshWorkspaceSeedsAllTemplatesAndDirs(@TempDir Path tmp) throws IOException {
        Path workspace = tmp.resolve("workspace");

        List<String> created = WorkspaceInitializer.initialize(workspace);

        assertEquals(List.of(
                "AGENTS.md", "SOUL.md", "USER.md", "TOOLS.md", "goal.md", "memory/MEMORY.md"),
                created, "created list must name exactly the seeded files, in order");
        for (String name : ALL_TEMPLATES) {
            Path file = workspace.resolve(name);
            assertTrue(Files.exists(file), "missing seeded file: " + name);
            assertArrayEquals(classpathTemplate(name), Files.readAllBytes(file),
                    "seeded content must be byte-identical to the bundled template: " + name);
        }
        assertTrue(Files.isDirectory(workspace.resolve("memory")));
        assertTrue(Files.isDirectory(workspace.resolve("sessions")));
        assertTrue(Files.isDirectory(workspace.resolve("skills")));
        assertTrue(Files.exists(workspace.resolve("memory/HISTORY.md")));
    }

    @Test
    public void existingWorkspaceWithOnlyGoalMissingSeedsJustGoal(@TempDir Path tmp) throws IOException {
        Path workspace = tmp.resolve("workspace");
        seedWorkspaceWith(workspace, "USER MODIFIED CONTENT");
        Files.delete(workspace.resolve("goal.md"));

        List<String> created = WorkspaceInitializer.initialize(workspace);

        assertEquals(List.of("goal.md"), created,
                "upgrade scenario: only the missing goal.md template may be seeded");
        assertArrayEquals(classpathTemplate("goal.md"),
                Files.readAllBytes(workspace.resolve("goal.md")));
        for (String name : ALL_TEMPLATES) {
            if ("goal.md".equals(name)) {
                continue;
            }
            assertEquals("USER MODIFIED CONTENT",
                    Files.readString(workspace.resolve(name)),
                    "existing file must stay untouched: " + name);
        }
    }

    @Test
    public void userModifiedFilesAreNeverOverwritten(@TempDir Path tmp) throws IOException {
        Path workspace = tmp.resolve("workspace");
        String marker = "# customized by user\n{{objective}} stays literal\n";
        seedWorkspaceWith(workspace, marker);

        List<String> created = WorkspaceInitializer.initialize(workspace);

        assertTrue(created.isEmpty(), "nothing is missing, nothing may be created");
        for (String name : ALL_TEMPLATES) {
            assertEquals(marker, Files.readString(workspace.resolve(name)),
                    "user content must survive the sync: " + name);
        }
    }

    @Test
    public void corruptedGoalTemplateStaysUntouched(@TempDir Path tmp) throws IOException {
        Path workspace = tmp.resolve("workspace");
        seedWorkspaceWith(workspace, "valid");
        Files.writeString(workspace.resolve("goal.md"), "garbage without marker sections");

        List<String> created = WorkspaceInitializer.initialize(workspace);

        assertTrue(created.isEmpty(), "a present-but-corrupted file must not be re-seeded");
        assertEquals("garbage without marker sections",
                Files.readString(workspace.resolve("goal.md")),
                "never-overwrite applies to corrupted files too; runtime falls back to "
                        + "embedded defaults instead");
    }

    @Test
    public void secondRunCreatesNothing(@TempDir Path tmp) throws IOException {
        Path workspace = tmp.resolve("workspace");
        WorkspaceInitializer.initialize(workspace);
        byte[] goalBefore = Files.readAllBytes(workspace.resolve("goal.md"));
        byte[] agentsBefore = Files.readAllBytes(workspace.resolve("AGENTS.md"));

        List<String> created = WorkspaceInitializer.initialize(workspace);

        assertTrue(created.isEmpty(), "idempotent: second run must be a no-op");
        assertArrayEquals(goalBefore, Files.readAllBytes(workspace.resolve("goal.md")));
        assertArrayEquals(agentsBefore, Files.readAllBytes(workspace.resolve("AGENTS.md")));
    }

    @Test
    public void deletedSubdirHistoryAndMemoryAreRestored(@TempDir Path tmp) throws IOException {
        Path workspace = tmp.resolve("workspace");
        WorkspaceInitializer.initialize(workspace);
        // Simulate a user pruning non-template artifacts between startups.
        deleteRecursively(workspace.resolve("sessions"));
        Files.delete(workspace.resolve("memory/HISTORY.md"));
        Files.delete(workspace.resolve("memory/MEMORY.md"));

        List<String> created = WorkspaceInitializer.initialize(workspace);

        assertEquals(List.of("memory/MEMORY.md"), created,
                "deleted MEMORY.md template is re-seeded on next startup");
        assertTrue(Files.isDirectory(workspace.resolve("sessions")), "subdir must be recreated");
        assertTrue(Files.exists(workspace.resolve("memory/HISTORY.md")),
                "HISTORY.md must be recreated empty");
        assertArrayEquals(classpathTemplate("memory/MEMORY.md"),
                Files.readAllBytes(workspace.resolve("memory/MEMORY.md")));
    }

    @Test
    public void workspacePathIsRegularFileFailsSoft(@TempDir Path tmp) throws IOException {
        Path workspace = tmp.resolve("not-a-dir");
        Files.writeString(workspace, "occupied");

        List<String> created = assertDoesNotThrow(() -> WorkspaceInitializer.initialize(workspace),
                "filesystem errors must be caught and logged, never escape");

        assertTrue(created.isEmpty());
        assertEquals("occupied", Files.readString(workspace),
                "the blocking file itself must not be modified");
    }

    @Test
    public void concurrentInitializationLeavesValidTemplates(@TempDir Path tmp) throws Exception {
        Path workspace = tmp.resolve("workspace");
        int threads = 4;
        int iterations = 20;
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                try {
                    start.await();
                    for (int n = 0; n < iterations; n++) {
                        WorkspaceInitializer.initialize(workspace, true);
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }, "init-worker-" + i);
            workers[i].start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(10_000);
            assertFalse(worker.isAlive(), "worker did not finish in time");
        }

        assertTrue(failures.isEmpty(), "concurrent sync must not throw: " + failures);
        for (String name : ALL_TEMPLATES) {
            Path file = workspace.resolve(name);
            assertTrue(Files.exists(file), "missing after concurrent sync: " + name);
            assertArrayEquals(classpathTemplate(name), Files.readAllBytes(file),
                    "racing writers must leave exactly one intact copy: " + name);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
