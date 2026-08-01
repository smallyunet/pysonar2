package org.yinwang.pysonar.lsp;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    }
}
