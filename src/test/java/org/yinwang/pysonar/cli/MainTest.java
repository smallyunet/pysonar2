package org.yinwang.pysonar.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
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
    public void reportsVersionThroughConventionalCommands() {
        for (String command : new String[]{"version", "--version", "-V"}) {
            Result result = run(command);
            assertEquals(result.stderr, 0, result.exitCode);
            assertEquals(Main.VERSION + System.lineSeparator(), result.stdout);
        }

        Result extraArgument = run("version", "unexpected");
        assertEquals(2, extraArgument.exitCode);
        assertTrue(extraArgument.stderr.contains("does not accept arguments"));
    }

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
        assertEquals("complete", contextJson.get("coverageStatus").getAsString());
        assertTrue(contextJson.get("applicable").getAsBoolean());
        assertEquals(2, contextJson.getAsJsonObject("coverage").get("discoveredFiles").getAsInt());
        assertEquals(2, contextJson.getAsJsonObject("coverage").get("parsedFiles").getAsInt());

        Result impact = run("impact", "--root", root.getAbsolutePath(), "--file", "app.py",
                "--line", "3", "--character", "5", "--format", "json");
        assertEquals(impact.stderr, 0, impact.exitCode);
        JsonObject impactJson = JsonParser.parseString(impact.stdout).getAsJsonObject();
        assertEquals("reference-based", impactJson.get("impactKind").getAsString());
        assertTrue(impactJson.getAsJsonArray("affectedFiles").size() >= 1);
        assertEquals("complete", impactJson.get("coverageStatus").getAsString());
        assertTrue(impactJson.get("applicable").getAsBoolean());
    }

    @Test
    public void partialCoverageKeepsContextUsableButRejectsCompleteImpact() throws Exception {
        File root = temporaryFolder.newFolder("partial-project");
        Files.write(new File(root, "good.py").toPath(), (
                "def target():\n" +
                "    return 1\n" +
                "\n" +
                "value = target()\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "broken.py").toPath(),
                "def broken(:\n".getBytes(StandardCharsets.UTF_8));

        Result context = run("context", "--root", root.getAbsolutePath(), "--file", "good.py",
                "--line", "4", "--character", "10", "--format", "json");
        assertEquals(context.stderr, 0, context.exitCode);
        JsonObject contextJson = JsonParser.parseString(context.stdout).getAsJsonObject();
        assertEquals("partial", contextJson.get("coverageStatus").getAsString());
        assertEquals("partial", contextJson.get("confidence").getAsString());
        assertTrue(contextJson.get("applicable").getAsBoolean());
        JsonObject coverage = contextJson.getAsJsonObject("coverage");
        assertEquals(2, coverage.get("discoveredFiles").getAsInt());
        assertEquals(1, coverage.get("parsedFiles").getAsInt());
        assertEquals(1, coverage.get("failedFileCount").getAsInt());
        assertEquals("broken.py", coverage.getAsJsonArray("failedFiles").get(0).getAsString());

        Result impact = run("impact", "--root", root.getAbsolutePath(), "--file", "good.py",
                "--line", "1", "--character", "5", "--format", "json");
        assertEquals(impact.stderr, 0, impact.exitCode);
        JsonObject impactJson = JsonParser.parseString(impact.stdout).getAsJsonObject();
        assertEquals("partial", impactJson.get("coverageStatus").getAsString());
        assertFalse(impactJson.get("applicable").getAsBoolean());
        assertTrue(impactJson.getAsJsonArray("limitations").toString()
                .contains("Do not use this result as a complete change-impact boundary"));
    }

    @Test
    public void pytestFixtureInjectionMakesImpactExplicitlyUnsupported() throws Exception {
        File root = temporaryFolder.newFolder("pytest-fixture-project");
        Files.write(new File(root, "conftest.py").toPath(), (
                "import pytest\n\n" +
                "@pytest.fixture\n" +
                "def workspace():\n" +
                "    return object()\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "test_feature.py").toPath(), (
                "def test_feature(workspace):\n" +
                "    assert workspace is not None\n").getBytes(StandardCharsets.UTF_8));

        Result impact = run("impact", "--root", root.getAbsolutePath(), "--file", "conftest.py",
                "--line", "4", "--character", "5", "--format", "json");
        assertEquals(impact.stderr, 0, impact.exitCode);
        JsonObject json = JsonParser.parseString(impact.stdout).getAsJsonObject();
        assertEquals("complete", json.get("coverageStatus").getAsString());
        assertFalse(json.get("applicable").getAsBoolean());
        assertTrue(json.getAsJsonArray("unsupportedSemantics")
                .toString().contains("pytest-fixture-parameter-injection"));
        assertTrue(json.getAsJsonObject("coverage").getAsJsonArray("unsupportedSemanticSymbols")
                .toString().contains("workspace"));
        assertTrue(json.getAsJsonArray("limitations").toString()
                .contains("injected references may be omitted"));
    }

    @Test
    public void compactPlanResolvesSymbolsAndBatchesOneAnalysis() throws Exception {
        File root = temporaryFolder.newFolder("plan-project");
        Files.write(new File(root, "rules.py").toPath(), (
                "def score_order(value):\n" +
                "    return value * 2\n" +
                "\n" +
                "def other(value):\n" +
                "    return value\n").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "service.py").toPath(), (
                "from rules import score_order\n" +
                "\n" +
                "result = score_order(3)\n").getBytes(StandardCharsets.UTF_8));

        Result plan = run("plan", "--root", root.getAbsolutePath(),
                "--symbol", "score_order", "--symbol", "other", "--intent", "change",
                "--max-results", "8", "--format", "compact-json");
        assertEquals(plan.stderr, 0, plan.exitCode);
        JsonObject json = JsonParser.parseString(plan.stdout).getAsJsonObject();
        assertEquals("plan", json.get("command").getAsString());
        assertEquals(2, json.getAsJsonArray("queries").size());
        JsonObject score = json.getAsJsonArray("queries").get(0).getAsJsonObject();
        assertEquals("score_order", score.get("symbol").getAsString());
        assertFalse(score.getAsJsonArray("candidates").isEmpty());
        assertEquals("exact-identifier-text", score.get("occurrenceKind").getAsString());
        assertTrue(score.get("returnedOccurrenceCount").getAsInt() >= 3);
        assertTrue(score.getAsJsonArray("affectedFiles").size() >= 2);
        JsonObject candidate = score.getAsJsonArray("candidates").get(0).getAsJsonObject();
        assertFalse(candidate.getAsJsonArray("definitions").isEmpty());
        assertFalse("compact output omits verbose timing", json.has("analysisMillis"));
    }

    @Test
    public void sessionReusesSnapshotForMultiplePlans() throws Exception {
        File root = temporaryFolder.newFolder("session-project");
        Files.write(new File(root, "models.py").toPath(), (
                "class User:\n" +
                "    pass\n" +
                "\n" +
                "class Team:\n" +
                "    pass\n").getBytes(StandardCharsets.UTF_8));
        String requests =
                "{\"id\":\"first\",\"command\":\"plan\",\"symbol\":\"User\"}\n" +
                "{\"id\":\"second\",\"command\":\"plan\",\"symbol\":\"Team\"}\n" +
                "{\"id\":\"refresh\",\"command\":\"refresh\"}\n" +
                "{\"command\":\"quit\"}\n";
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = Main.run(
                new String[]{"session", "--root", root.getAbsolutePath(), "--format", "json"},
                new ByteArrayInputStream(requests.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout), new PrintStream(stderr));
        assertEquals(new String(stderr.toByteArray(), StandardCharsets.UTF_8), 0, exitCode);
        String[] lines = new String(stdout.toByteArray(), StandardCharsets.UTF_8).trim().split("\\R");
        assertEquals(5, lines.length);
        JsonObject ready = JsonParser.parseString(lines[0]).getAsJsonObject();
        assertEquals("session-ready", ready.get("command").getAsString());
        assertEquals("full", ready.get("rebuildMode").getAsString());
        assertEquals(1, ready.get("astCacheMisses").getAsInt());
        assertEquals("first", JsonParser.parseString(lines[1]).getAsJsonObject().get("id").getAsString());
        assertEquals("second", JsonParser.parseString(lines[2]).getAsJsonObject().get("id").getAsString());
        JsonObject refresh = JsonParser.parseString(lines[3]).getAsJsonObject();
        assertEquals("refresh", refresh.get("id").getAsString());
        assertEquals("no_change", refresh.get("rebuildMode").getAsString());
        assertEquals(0, refresh.get("analyzedFiles").getAsInt());
        assertEquals("quit", JsonParser.parseString(lines[4]).getAsJsonObject().get("command").getAsString());
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
