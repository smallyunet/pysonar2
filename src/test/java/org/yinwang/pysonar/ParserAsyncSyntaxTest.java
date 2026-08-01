package org.yinwang.pysonar;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.yinwang.pysonar.ast.Await;
import org.yinwang.pysonar.ast.Block;
import org.yinwang.pysonar.ast.Expr;
import org.yinwang.pysonar.ast.FunctionDef;
import org.yinwang.pysonar.ast.PyModule;
import org.yinwang.pysonar.ast.YieldFrom;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ParserAsyncSyntaxTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void preservesAwaitAndYieldFromNodeKinds() throws Exception
    {
        File source = temporaryFolder.newFile("async_syntax.py");
        Files.write(source.toPath(), (
                "async def fetch(source):\n" +
                "    await source()\n\n" +
                "def produce(values):\n" +
                "    yield from values\n").getBytes(StandardCharsets.UTF_8));

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
        assertEquals(2, module.body.seq.size());

        FunctionDef fetch = (FunctionDef) module.body.seq.get(0);
        assertTrue(fetch.isAsync);
        assertTrue(expressionAt(fetch, 0) instanceof Await);

        FunctionDef produce = (FunctionDef) module.body.seq.get(1);
        assertTrue(expressionAt(produce, 0) instanceof YieldFrom);
    }

    private static org.yinwang.pysonar.ast.Node expressionAt(FunctionDef function, int index)
    {
        assertTrue(function.body instanceof Block);
        org.yinwang.pysonar.ast.Node statement = ((Block) function.body).seq.get(index);
        assertTrue(statement instanceof Expr);
        return ((Expr) statement).value;
    }
}
