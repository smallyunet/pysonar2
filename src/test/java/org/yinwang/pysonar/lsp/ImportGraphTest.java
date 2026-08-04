package org.yinwang.pysonar.lsp;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImportGraphTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void resolvesRelativeImportsAndIgnoresImportsInsideStringsAndComments() throws Exception {
        File root = temporary.newFolder("import-graph");
        File packageDirectory = new File(root, "sample");
        assertTrue(packageDirectory.mkdirs());
        Path initializer = new File(packageDirectory, "__init__.py").toPath();
        Path model = new File(packageDirectory, "model.py").toPath();
        Path service = new File(packageDirectory, "service.py").toPath();
        Path unrelated = new File(packageDirectory, "unrelated.py").toPath();
        Files.write(initializer, new byte[0]);
        Files.write(model, "class Thing:\n    pass\n".getBytes(StandardCharsets.UTF_8));
        Files.write(service, (
                "\"\"\"import sample.unrelated\"\"\"\n" +
                "# from .unrelated import ignored\n" +
                "from .model import Thing\n").getBytes(StandardCharsets.UTF_8));
        Files.write(unrelated, "value = 1\n".getBytes(StandardCharsets.UTF_8));

        ImportGraph graph = ImportGraph.build(root.toPath(),
                Arrays.asList(initializer, model, service, unrelated));
        Set<Path> modelAffected = graph.affectedBy(Arrays.asList(model));
        assertTrue(modelAffected.contains(service.toAbsolutePath().normalize()));
        assertFalse(graph.affectedBy(Arrays.asList(unrelated))
                .contains(service.toAbsolutePath().normalize()));
        Set<Path> initializerAffected = graph.affectedBy(Arrays.asList(initializer));
        assertTrue(initializerAffected.contains(model.toAbsolutePath().normalize()));
        assertTrue(initializerAffected.contains(service.toAbsolutePath().normalize()));
        assertTrue(initializerAffected.contains(unrelated.toAbsolutePath().normalize()));
    }

    @Test
    public void marksBackslashContinuedImportsForFullRebuildFallback() throws Exception {
        File root = temporary.newFolder("continued-import-graph");
        Path module = new File(root, "module.py").toPath();
        Files.write(module, "from \\\n package import value\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(ImportGraph.build(root.toPath(), Arrays.asList(module)).isComplete());
    }
}
