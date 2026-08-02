package org.yinwang.pysonar;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.yinwang.pysonar.ast.Assign;
import org.yinwang.pysonar.ast.Block;
import org.yinwang.pysonar.ast.ClassDef;
import org.yinwang.pysonar.ast.Comprehension;
import org.yinwang.pysonar.ast.FormattedValue;
import org.yinwang.pysonar.ast.FunctionDef;
import org.yinwang.pysonar.ast.JoinedStr;
import org.yinwang.pysonar.ast.ListComp;
import org.yinwang.pysonar.ast.PyModule;
import org.yinwang.pysonar.ast.Raise;
import org.yinwang.pysonar.ast.Return;
import org.yinwang.pysonar.ast.Try;
import org.yinwang.pysonar.ast.TypeAlias;
import org.yinwang.pysonar.ast.TypeParameter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ModernPythonSyntaxTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void preservesModernExceptionClassAndAsyncComprehensionSyntax() throws Exception
    {
        AnalysisResult result = analyze(
                "def deco(value):\n" +
                "    return value\n\n" +
                "class Base: pass\n" +
                "class Meta(type): pass\n" +
                "class Error(Exception): pass\n" +
                "cause = RuntimeError('cause')\n\n" +
                "@deco\n" +
                "class Child(Base, metaclass=Meta):\n" +
                "    pass\n\n" +
                "def raises():\n" +
                "    try:\n" +
                "        raise Error('bad') from cause\n" +
                "    except Error as caught:\n" +
                "        return caught\n\n" +
                "async def collect(source):\n" +
                "    return [item async for item in source if item]\n");

        assertTrue(result.analyzer.unsupportedNodeTypes.toString(),
                result.analyzer.unsupportedNodeTypes.isEmpty());

        ClassDef child = (ClassDef) result.module.body.seq.get(5);
        assertEquals(1, child.decorators.size());
        assertEquals(1, child.keywords.size());

        FunctionDef raises = (FunctionDef) result.module.body.seq.get(6);
        Try tryNode = (Try) ((Block) raises.body).seq.get(0);
        Raise raiseNode = (Raise) tryNode.body.seq.get(0);
        assertNotNull(raiseNode.exceptionType);
        assertNotNull("raise ... from cause should retain its cause", raiseNode.inst);
        assertNotNull("except ... as caught should retain its binder", tryNode.handlers.get(0).binder);

        FunctionDef collect = (FunctionDef) result.module.body.seq.get(7);
        Return returnNode = (Return) ((Block) collect.body).seq.get(0);
        Comprehension generator = ((ListComp) returnNode.value).generators.get(0);
        assertTrue(generator.isAsync);

        Set<String> unresolved = result.analyzer.unresolved.stream()
                .map(name -> name.id).collect(Collectors.toSet());
        for (String resolvedName : Arrays.asList("deco", "Base", "Meta", "Error", "cause", "caught"))
        {
            assertFalse(resolvedName + " should resolve", unresolved.contains(resolvedName));
        }
    }

    @Test
    public void supportsExceptionGroupsOnPython311AndNewer() throws Exception
    {
        String source =
                "class Error(Exception): pass\n" +
                "def handle():\n" +
                "    try:\n" +
                "        raise Error('bad')\n" +
                "    except* Error as grouped:\n" +
                "        handled = grouped\n";
        Assume.assumeTrue(canParse(source));

        AnalysisResult result = analyze(source);
        assertTrue(result.analyzer.unsupportedNodeTypes.toString(),
                result.analyzer.unsupportedNodeTypes.isEmpty());
        Try tryNode = (Try) ((Block) ((FunctionDef) result.module.body.seq.get(1)).body).seq.get(0);
        assertTrue(tryNode.isExceptionGroup);
        assertNotNull(tryNode.handlers.get(0).binder);
        assertFalse(result.analyzer.unresolved.stream().anyMatch(name -> "grouped".equals(name.id)));
    }

    @Test
    public void supportsPep695TypeAliasesAndTypeParametersWhenAvailable() throws Exception
    {
        String source =
                "type Pair[T: int] = tuple[T, T]\n" +
                "type Variadic[*Ts] = tuple[*Ts]\n" +
                "type Callback[**P] = tuple[P, int]\n" +
                "def identity[T](value: T) -> T:\n" +
                "    marker = T\n" +
                "    return value\n" +
                "class Box[T]:\n" +
                "    marker = T\n";
        Assume.assumeTrue(canParse(source));

        AnalysisResult result = analyze(source);
        assertTrue(result.analyzer.unsupportedNodeTypes.toString(),
                result.analyzer.unsupportedNodeTypes.isEmpty());

        TypeAlias pair = (TypeAlias) result.module.body.seq.get(0);
        assertEquals("Pair", pair.nameNode.id);
        assertEquals(1, pair.typeParams.size());
        TypeParameter t = pair.typeParams.get(0);
        assertEquals("TypeVar", t.parameterKind);
        assertNotNull(t.bound);
        assertEquals("TypeVarTuple", ((TypeAlias) result.module.body.seq.get(1))
                .typeParams.get(0).parameterKind);
        assertEquals("ParamSpec", ((TypeAlias) result.module.body.seq.get(2))
                .typeParams.get(0).parameterKind);
        assertEquals(1, ((FunctionDef) result.module.body.seq.get(3)).typeParams.size());
        assertEquals(1, ((ClassDef) result.module.body.seq.get(4)).typeParams.size());
        Set<String> unresolved = result.analyzer.unresolved.stream()
                .map(name -> name.id).collect(Collectors.toSet());
        for (String typeParameter : Arrays.asList("T", "Ts", "P"))
        {
            assertFalse(typeParameter + " should resolve in its generic scope",
                    unresolved.contains(typeParameter));
        }
    }

    @Test
    public void supportsTypeParameterDefaultsWhenAvailable() throws Exception
    {
        String source = "type Pair[T = int] = tuple[T, T]\n";
        Assume.assumeTrue(canParse(source));

        AnalysisResult result = analyze(source);
        assertTrue(result.analyzer.unsupportedNodeTypes.toString(),
                result.analyzer.unsupportedNodeTypes.isEmpty());
        TypeParameter parameter = ((TypeAlias) result.module.body.seq.get(0)).typeParams.get(0);
        assertNotNull(parameter.defaultValue);
    }

    @Test
    public void supportsTemplateStringsWhenAvailable() throws Exception
    {
        String source = "name = 'world'\nrendered = t'hello {name!r:>10}'\n";
        Assume.assumeTrue(canParse(source));

        AnalysisResult result = analyze(source);
        assertTrue(result.analyzer.unsupportedNodeTypes.toString(),
                result.analyzer.unsupportedNodeTypes.isEmpty());
        JoinedStr template = (JoinedStr) ((Assign) result.module.body.seq.get(1)).value;
        assertEquals(2, template.values.size());
        assertTrue(template.values.get(1) instanceof FormattedValue);
        assertFalse(result.analyzer.unresolved.stream().anyMatch(name -> "name".equals(name.id)));
    }

    private AnalysisResult analyze(String content) throws Exception
    {
        File source = temporaryFolder.newFile("syntax_" + System.nanoTime() + ".py");
        Files.write(source.toPath(), content.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> options = new HashMap<>();
        options.put("quiet", true);
        Analyzer analyzer = new Analyzer(options);
        try
        {
            analyzer.analyze(source.getAbsolutePath());
            PyModule module = (PyModule) analyzer.getAstForFile(source.getAbsolutePath());
            analyzer.finish();
            return new AnalysisResult(analyzer, module);
        }
        catch (Throwable failure)
        {
            analyzer.close();
            throw failure;
        }
    }

    private boolean canParse(String source) throws Exception
    {
        String python = System.getenv("PYSONAR_PYTHON");
        if (python == null || python.trim().isEmpty())
        {
            python = "python3";
        }
        Process process = new ProcessBuilder(python, "-c", "compile(" + quote(source) + ", '<test>', 'exec')")
                .redirectErrorStream(true)
                .start();
        return process.waitFor() == 0;
    }

    private String quote(String value)
    {
        return "" + '\'' + value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n") + '\'';
    }

    private static final class AnalysisResult
    {
        final Analyzer analyzer;
        final PyModule module;

        AnalysisResult(Analyzer analyzer, PyModule module)
        {
            this.analyzer = analyzer;
            this.module = module;
        }
    }
}
