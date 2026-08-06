package org.yinwang.pysonar.lsp;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.yinwang.pysonar.Analyzer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AnalysisSessionTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void buildsEditorSnapshotForCrossFileNavigation() throws Exception {
        File root = temporary.newFolder("workspace");
        File model = new File(root, "model.py");
        File main = new File(root, "main.py");
        Files.write(model.toPath(), (
                "class Thing:\n" +
                "    \"\"\"A model discovered across modules.\"\"\"\n" +
                "    def __init__(self, name):\n" +
                "        self.name = name\n").getBytes(StandardCharsets.UTF_8));
        Files.write(main.toPath(), (
                "from model import Thing\n" +
                "\n" +
                "def build():\n" +
                "    return Thing(\"example\")\n").getBytes(StandardCharsets.UTF_8));

        try (AnalysisSession session = new AnalysisSession(root.toPath(), Collections.emptyList())) {
            AnalysisSnapshot snapshot = session.rebuildNow();
            assertEquals(2, snapshot.fileCount());

            List<Location> definitions = snapshot.definitions(main.toPath(), new Position(3, 12));
            assertFalse(definitions.isEmpty());
            assertTrue(definitions.get(0).getUri().endsWith("model.py"));
            assertTrue(snapshot.hover(main.toPath(), new Position(3, 12)).isPresent());
            assertFalse(snapshot.documentSymbols(model.toPath()).isEmpty());
            assertFalse(snapshot.references(model.toPath(), new Position(0, 7), true).isEmpty());
            assertNull("LSP snapshots must not retain the completed analyzer globally", Analyzer.self);
        }
    }

    @Test
    public void convertsAstralCharactersToUtf16Positions() {
        String source = "x = '😀'\nvalue = x\n";
        int valueOffset = source.codePointCount(0, source.indexOf("value"));
        Position position = PositionCodec.toPosition(source, valueOffset);
        assertEquals(1, position.getLine());
        assertEquals(0, position.getCharacter());
        assertEquals(valueOffset, PositionCodec.toCodePointOffset(source, position));

        PositionCodec.LineIndex index = PositionCodec.index("😀x\n中😀z\n");
        assertEquals(new Position(1, 1), index.toPosition(4));
        assertEquals(new Position(1, 3), index.toPosition(5));
        assertEquals(5, index.toCodePointOffset(new Position(1, 3)));
    }

    @Test
    public void reportsDiscoveryAnalysisAndSnapshotProgress() throws Exception {
        File root = temporary.newFolder("progress-workspace");
        Files.write(new File(root, "first.py").toPath(), "value = 1\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(root, "second.py").toPath(), "other = value\n".getBytes(StandardCharsets.UTF_8));
        List<AnalysisProgress> progress = new ArrayList<>();

        try (AnalysisSession session = new AnalysisSession(root.toPath(), Collections.emptyList(), progress::add)) {
            session.rebuildNow();
        }

        assertTrue(progress.stream().anyMatch(item -> "discovering".equals(item.getPhase())));
        assertTrue(progress.stream().anyMatch(item -> "analyzing".equals(item.getPhase())
                && item.getTotal() == 2 && item.getCurrent() == 1 && item.getPath().endsWith("first.py")));
        assertTrue(progress.stream().anyMatch(item -> "analyzing".equals(item.getPhase())
                && item.getTotal() == 2 && item.getCurrent() == 2 && item.getPath().endsWith("second.py")));
        assertTrue(progress.stream().anyMatch(item -> "finalizing".equals(item.getPhase())));
        assertTrue(progress.stream().anyMatch(item -> "snapshot".equals(item.getPhase())));
    }

    @Test
    public void discoversNestedPythonProjectRootsInMonorepos() throws Exception {
        File root = temporary.newFolder("monorepo");
        File project = new File(root, "child-project");
        File packageDirectory = new File(project, "sample_app");
        assertTrue(packageDirectory.mkdirs());
        Files.write(new File(project, "pyproject.toml").toPath(),
                "[build-system]\nrequires = []\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(packageDirectory, "__init__.py").toPath(), new byte[0]);
        File model = new File(packageDirectory, "model.py");
        File main = new File(packageDirectory, "main.py");
        Files.write(model.toPath(), "class Thing:\n    pass\n".getBytes(StandardCharsets.UTF_8));
        Files.write(main.toPath(), (
                "from sample_app.model import Thing\n" +
                "value = Thing()\n").getBytes(StandardCharsets.UTF_8));

        try (AnalysisSession session = new AnalysisSession(root.toPath(), Collections.emptyList())) {
            AnalysisSnapshot snapshot = session.rebuildNow();
            List<Location> definitions = snapshot.definitions(main.toPath(), new Position(1, 9));
            assertFalse(definitions.isEmpty());
            assertTrue(definitions.get(0).getUri().endsWith("model.py"));
        }
    }

    @Test
    public void conservativeDiagnosticsSuppressUnknownTypeCascades() throws Exception {
        File root = temporary.newFolder("diagnostic-workspace");
        File main = new File(root, "main.py");
        Files.write(main.toPath(), (
                "from missing_dependency import External\n" +
                "value: External = External()\n" +
                "value.attribute = 1\n").getBytes(StandardCharsets.UTF_8));

        try (AnalysisSession session = new AnalysisSession(root.toPath(), Collections.emptyList())) {
            Map<String, List<Diagnostic>> diagnostics = session.rebuildNow().diagnosticsByUri();
            assertTrue(diagnostics.isEmpty());
        }
    }

    @Test
    public void diagnosticsCanBeDisabledOrCapped() throws Exception {
        File root = temporary.newFolder("diagnostic-policy-workspace");
        File main = new File(root, "main.py");
        Files.write(main.toPath(), "first\nsecond\nthird\n".getBytes(StandardCharsets.UTF_8));

        DiagnosticPolicy capped = new DiagnosticPolicy(DiagnosticPolicy.Mode.ALL, 2);
        try (AnalysisSession session = new AnalysisSession(root.toPath(), Collections.emptyList(), capped,
                progress -> { })) {
            Map<String, List<Diagnostic>> diagnostics = session.rebuildNow().diagnosticsByUri();
            assertEquals(1, diagnostics.size());
            List<Diagnostic> findings = diagnostics.values().iterator().next();
            assertEquals(2, findings.size());
            assertEquals(DiagnosticSeverity.Warning, findings.get(0).getSeverity());
        }

        DiagnosticPolicy disabled = new DiagnosticPolicy(DiagnosticPolicy.Mode.OFF, 100);
        try (AnalysisSession session = new AnalysisSession(root.toPath(), Collections.emptyList(), disabled,
                progress -> { })) {
            assertTrue(session.rebuildNow().diagnosticsByUri().isEmpty());
        }

        DiagnosticPolicy conservative = DiagnosticPolicy.conservative();
        assertTrue(conservative.shouldPublish("Incorrect number of arguments for isinstance"));
        assertFalse(conservative.shouldPublish("unbound variable Optional"));
        assertFalse(conservative.shouldPublish("Attribute is not found in type: get"));
    }

    @Test
    public void skipsUnchangedWorkspacesAndRebuildsOnlyReverseImportClosure() throws Exception {
        File root = temporary.newFolder("incremental-workspace");
        File model = new File(root, "model.py");
        File service = new File(root, "service.py");
        File main = new File(root, "main.py");
        File unrelated = new File(root, "unrelated.py");
        Files.write(model.toPath(), "class Thing:\n    pass\n".getBytes(StandardCharsets.UTF_8));
        Files.write(service.toPath(), (
                "from model import Thing\n" +
                "value = Thing()\n").getBytes(StandardCharsets.UTF_8));
        Files.write(main.toPath(), (
                "from service import value\n" +
                "result = value\n").getBytes(StandardCharsets.UTF_8));
        Files.write(unrelated.toPath(), "standalone = 1\n".getBytes(StandardCharsets.UTF_8));

        Path cache = temporary.newFolder("incremental-cache").toPath();
        try (AnalysisSession session = new AnalysisSession(root.toPath(), Collections.emptyList(),
                DiagnosticPolicy.conservative(), progress -> { }, cache)) {
            AnalysisSnapshot initial = session.rebuildNow();
            assertEquals(RebuildMetrics.Mode.FULL, session.lastMetrics().getMode());
            assertEquals(4, initial.fileCount());

            AnalysisSnapshot unchanged = session.rebuildNow();
            assertSame(initial, unchanged);
            assertEquals(RebuildMetrics.Mode.NO_CHANGE, session.lastMetrics().getMode());
            assertEquals(0, session.lastMetrics().getAnalyzedFiles());

            Files.write(model.toPath(), "\nclass Thing:\n    pass\n".getBytes(StandardCharsets.UTF_8));
            AnalysisSnapshot dependentUpdate = session.rebuildNow();
            assertEquals(RebuildMetrics.Mode.INCREMENTAL, session.lastMetrics().getMode());
            assertEquals(1, session.lastMetrics().getChangedFiles());
            assertEquals(3, session.lastMetrics().getAffectedFiles());
            List<Location> definitions = dependentUpdate.definitions(service.toPath(), new Position(1, 9));
            assertFalse(definitions.isEmpty());
            assertEquals(1, definitions.get(0).getRange().getStart().getLine());
            assertFalse(dependentUpdate.documentSymbols(unrelated.toPath()).isEmpty());

            Files.write(unrelated.toPath(), "standalone = 2\n".getBytes(StandardCharsets.UTF_8));
            session.rebuildNow();
            assertEquals(RebuildMetrics.Mode.INCREMENTAL, session.lastMetrics().getMode());
            assertEquals(1, session.lastMetrics().getAffectedFiles());

            Files.delete(unrelated.toPath());
            AnalysisSnapshot deleted = session.rebuildNow();
            assertEquals(RebuildMetrics.Mode.INCREMENTAL, session.lastMetrics().getMode());
            assertEquals(1, session.lastMetrics().getChangedFiles());
            assertEquals(3, deleted.fileCount());
            assertTrue(deleted.documentSymbols(unrelated.toPath()).isEmpty());
        }
    }

    @Test
    public void reusesPersistentAstCacheAcrossAnalyzerInstancesAndInvalidatesByContent() throws Exception {
        File root = temporary.newFolder("persistent-cache-workspace");
        File source = new File(root, "module.py");
        Files.write(source.toPath(), "value = 1\n".getBytes(StandardCharsets.UTF_8));
        Path cache = temporary.newFolder("persistent-cache").toPath();

        try (AnalysisSession first = new AnalysisSession(root.toPath(), Collections.emptyList(),
                DiagnosticPolicy.conservative(), progress -> { }, cache)) {
            first.rebuildNow();
            assertTrue(first.lastMetrics().getAstCacheMisses() >= 1);
        }
        try (AnalysisSession second = new AnalysisSession(root.toPath(), Collections.emptyList(),
                DiagnosticPolicy.conservative(), progress -> { }, cache)) {
            second.rebuildNow();
            assertTrue(second.lastMetrics().getAstCacheHits() >= 1);

            Files.write(source.toPath(), "value = 2\n".getBytes(StandardCharsets.UTF_8));
            second.rebuildNow();
            assertTrue(second.lastMetrics().getAstCacheMisses() >= 1);
        }
    }

    @Test
    public void reconcilesReferencesAcrossIncrementalSnapshotBoundaries() throws Exception {
        File root = temporary.newFolder("incremental-reference-workspace");
        File model = new File(root, "model.py");
        File first = new File(root, "first.py");
        File second = new File(root, "second.py");
        Files.write(model.toPath(), "class Thing:\n    pass\n".getBytes(StandardCharsets.UTF_8));
        Files.write(first.toPath(), (
                "from model import Thing\n" +
                "one = Thing()\n").getBytes(StandardCharsets.UTF_8));
        Files.write(second.toPath(), (
                "from model import Thing\n" +
                "two = Thing()\n").getBytes(StandardCharsets.UTF_8));

        Path cache = temporary.newFolder("incremental-reference-cache").toPath();
        try (AnalysisSession session = new AnalysisSession(root.toPath(), Collections.emptyList(),
                DiagnosticPolicy.conservative(), progress -> { }, cache)) {
            AnalysisSnapshot initial = session.rebuildNow();
            assertTrue(initial.references(model.toPath(), new Position(0, 7), false).size() >= 2);

            Files.write(first.toPath(), (
                    "\n" +
                    "from model import Thing\n" +
                    "one = Thing()\n").getBytes(StandardCharsets.UTF_8));
            AnalysisSnapshot updated = session.rebuildNow();
            List<Location> references = updated.references(model.toPath(), new Position(0, 7), false);
            assertTrue(references.stream().anyMatch(location -> location.getUri().endsWith("first.py")
                    && location.getRange().getStart().getLine() >= 1));
            assertTrue(references.stream().anyMatch(location -> location.getUri().endsWith("second.py")));
        }
    }

    @Test
    public void repairsParseCoverageAcrossIncrementalRebuilds() throws Exception {
        File root = temporary.newFolder("incremental-coverage-workspace");
        File good = new File(root, "good.py");
        File broken = new File(root, "broken.py");
        Files.write(good.toPath(), "value = 1\n".getBytes(StandardCharsets.UTF_8));
        Files.write(broken.toPath(), "def broken(:\n".getBytes(StandardCharsets.UTF_8));

        Path cache = temporary.newFolder("incremental-coverage-cache").toPath();
        try (AnalysisSession session = new AnalysisSession(root.toPath(), Collections.emptyList(),
                DiagnosticPolicy.conservative(), progress -> { }, cache)) {
            AnalysisSnapshot initial = session.rebuildNow();
            assertEquals("partial", initial.coverageStatus());
            assertEquals(2, initial.discoveredFileCount());
            assertEquals(1, initial.parsedFileCount());
            assertEquals(Collections.singletonList("broken.py"), initial.failedFiles());

            Files.write(broken.toPath(), "def repaired():\n    return 1\n".getBytes(StandardCharsets.UTF_8));
            AnalysisSnapshot repaired = session.rebuildNow();
            assertEquals(RebuildMetrics.Mode.INCREMENTAL, session.lastMetrics().getMode());
            assertEquals("complete", repaired.coverageStatus());
            assertEquals(2, repaired.discoveredFileCount());
            assertEquals(2, repaired.parsedFileCount());
            assertTrue(repaired.failedFiles().isEmpty());
            assertTrue(repaired.isParsed(good.toPath()));
            assertTrue(repaired.isParsed(broken.toPath()));
        }
    }
}
