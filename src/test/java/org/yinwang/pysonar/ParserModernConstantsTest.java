package org.yinwang.pysonar;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.yinwang.pysonar.ast.Assign;
import org.yinwang.pysonar.ast.Block;
import org.yinwang.pysonar.ast.Bytes;
import org.yinwang.pysonar.ast.Ellipsis;
import org.yinwang.pysonar.ast.Name;
import org.yinwang.pysonar.ast.PyFloat;
import org.yinwang.pysonar.ast.PyInt;
import org.yinwang.pysonar.ast.PyModule;
import org.yinwang.pysonar.ast.Str;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ParserModernConstantsTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void parsesPython3ConstantNodesWithoutUnsupportedFallback() throws Exception
    {
        File source = temporaryFolder.newFile("constants.py");
        Files.write(source.toPath(), (
                "none_value = None\n" +
                "true_value = True\n" +
                "false_value = False\n" +
                "int_value = 42\n" +
                "float_value = 3.5\n" +
                "string_value = 'hello'\n" +
                "bytes_value = b'hello'\n" +
                "ellipsis_value = ...\n").getBytes(StandardCharsets.UTF_8));

        Map<String, Object> options = new HashMap<>();
        options.put("quiet", true);
        Analyzer analyzer = new Analyzer(options);
        PyModule module;
        try
        {
            module = (PyModule) analyzer.getAstForFile(source.getAbsolutePath());
        }
        finally
        {
            analyzer.finish();
        }

        assertNotNull("parser returned no module", module);
        assertNotNull("parsed module has no body", module.body);
        assertEquals(8, module.body.seq.size());
        assertNameConstant(module, 0, "None");
        assertNameConstant(module, 1, "True");
        assertNameConstant(module, 2, "False");
        assertTrue(valueAt(module, 3) instanceof PyInt);
        assertTrue(valueAt(module, 4) instanceof PyFloat);
        assertTrue(valueAt(module, 5) instanceof Str);
        assertTrue(valueAt(module, 6) instanceof Bytes);
        assertTrue(valueAt(module, 7) instanceof Ellipsis);
    }

    private static void assertNameConstant(PyModule module, int index, String expected)
    {
        assertTrue(valueAt(module, index) instanceof Name);
        assertEquals(expected, ((Name) valueAt(module, index)).id);
    }

    private static org.yinwang.pysonar.ast.Node valueAt(PyModule module, int index)
    {
        assertTrue(module.body.seq.get(index) instanceof Assign);
        return ((Assign) module.body.seq.get(index)).value;
    }
}
