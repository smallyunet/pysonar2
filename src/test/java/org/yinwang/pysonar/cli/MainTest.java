package org.yinwang.pysonar.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MainTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void doctorReportsStableSchema() {
        Result result = run("doctor", "--format", "json");
        assertEquals(0, result.exitCode);
        JsonObject json = JsonParser.parseString(result.stdout).getAsJsonObject();
        assertEquals(1, json.get("schemaVersion").getAsInt());
        assertEquals("doctor", json.get("command").getAsString());
        assertEquals("ok", json.get("status").getAsString());
    }

    @Test
    public void contextAndImpactResolveCrossFileReferences() throws Exception {
        File root = temporaryFolder.newFolder("project");
        Files.write(new File(root, "models.py").toPath(), (
                "class User:\n" +
                "    pass\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "app.py").toPath(), (
                "from models import User\n" +
                "\n" +
                "def load():\n" +
                "    return User()\n" +
                "\n" +
                "current = load()\n").getBytes(StandardCharsets.UTF_8));

        Result context = run("context", "--root", root.getAbsolutePath(), "--file", "app.py",
                "--line", "4", "--character", "12", "--format", "json");
        assertEquals(context.stderr, 0, context.exitCode);
        JsonObject contextJson = JsonParser.parseString(context.stdout).getAsJsonObject();
        assertEquals("User", contextJson.get("symbol").getAsString());
        assertFalse(contextJson.getAsJsonArray("definitions").isEmpty());

        Result impact = run("impact", "--root", root.getAbsolutePath(), "--file", "app.py",
                "--line", "3", "--character", "5", "--format", "json");
        assertEquals(impact.stderr, 0, impact.exitCode);
        JsonObject impactJson = JsonParser.parseString(impact.stdout).getAsJsonObject();
        assertEquals("reference-based", impactJson.get("impactKind").getAsString());
        assertTrue(impactJson.getAsJsonArray("affectedFiles").size() >= 1);
    }

    @Test
    public void installsAndUninstallsProjectSkill() throws Exception {
        File root = temporaryFolder.newFolder("skill-project");
        Result install = run("skill", "install", "--agent", "codex", "--scope", "project",
                "--root", root.getAbsolutePath());
        assertEquals(install.stderr, 0, install.exitCode);
        File skill = new File(root, ".agents/skills/pysonar-code-intelligence/SKILL.md");
        assertTrue(skill.isFile());

        Result update = run("skill", "update", "--agent", "codex", "--scope", "project",
                "--root", root.getAbsolutePath());
        assertEquals(update.stderr, 0, update.exitCode);

        Result uninstall = run("skill", "uninstall", "--agent", "codex", "--scope", "project",
                "--root", root.getAbsolutePath());
        assertEquals(uninstall.stderr, 0, uninstall.exitCode);
        assertFalse(skill.exists());
    }

    @Test
    public void refusesToOverwriteModifiedManagedSkill() throws Exception {
        File root = temporaryFolder.newFolder("modified-skill-project");
        Result install = run("skill", "install", "--agent", "codex", "--scope", "project",
                "--root", root.getAbsolutePath());
        assertEquals(install.stderr, 0, install.exitCode);
        File skill = new File(root, ".agents/skills/pysonar-code-intelligence/SKILL.md");
        Files.write(skill.toPath(), "\nlocal change\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);

        Result update = run("skill", "update", "--agent", "codex", "--scope", "project",
                "--root", root.getAbsolutePath());
        assertEquals(3, update.exitCode);
        assertTrue(update.stderr.contains("locally modified"));
    }

    private static Result run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = Main.run(args, new PrintStream(stdout), new PrintStream(stderr));
        return new Result(exitCode,
                new String(stdout.toByteArray(), StandardCharsets.UTF_8),
                new String(stderr.toByteArray(), StandardCharsets.UTF_8));
    }

    private static final class Result {
        final int exitCode;
        final String stdout;
        final String stderr;

        Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
