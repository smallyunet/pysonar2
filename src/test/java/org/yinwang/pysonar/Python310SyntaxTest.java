package org.yinwang.pysonar;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.yinwang.pysonar.ast.AnnAssign;
import org.yinwang.pysonar.ast.Block;
import org.yinwang.pysonar.ast.FunctionDef;
import org.yinwang.pysonar.ast.If;
import org.yinwang.pysonar.ast.JoinedStr;
import org.yinwang.pysonar.ast.Match;
import org.yinwang.pysonar.ast.MatchPattern;
import org.yinwang.pysonar.ast.NamedExpr;
import org.yinwang.pysonar.ast.Node;
import org.yinwang.pysonar.ast.PyModule;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class Python310SyntaxTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void preservesAndAnalyzesModernPython310Syntax() throws Exception
    {
        File source = temporaryFolder.newFile("modern.py");
        Files.write(source.toPath(), (
                "class Item:\n" +
                "    value: int\n\n" +
                "def modern(a: int, /, b: str = 'x', *, flag: bool = True) -> str:\n" +
                "    total: int = a\n" +
                "    text = f'{b}:{total!r}'\n" +
                "    if (size := len(text)) < 0:\n" +
                "        return str(size)\n" +
                "    match {'kind': b, 'value': total}:\n" +
                "        case {'kind': kind, 'value': number} if number > 0:\n" +
                "            return f'{kind}:{number}'\n" +
                "        case _:\n" +
                "            return text\n\n" +
                "result = modern(1, flag=True)\n").getBytes(StandardCharsets.UTF_8));

        Map<String, Object> options = new HashMap<>();
        options.put("quiet", true);
        Analyzer analyzer = new Analyzer(options);
        PyModule module;
        try
        {
            analyzer.analyze(source.getAbsolutePath());
            module = (PyModule) analyzer.getAstForFile(source.getAbsolutePath());
            analyzer.finish();
        }
        catch (Throwable failure)
        {
            analyzer.close();
            throw failure;
        }

        assertNotNull(module);
        assertTrue("all targeted Python 3.10 nodes should be supported",
                analyzer.unsupportedNodeTypes.isEmpty());

        Block classBody = (Block) ((org.yinwang.pysonar.ast.ClassDef) module.body.seq.get(0)).body;
        assertTrue(classBody.seq.get(0) instanceof AnnAssign);

        FunctionDef function = (FunctionDef) module.body.seq.get(1);
        assertEquals(1, function.posOnlyArgCount);
        assertEquals(2, function.args.size());
        assertEquals(1, function.kwOnlyArgs.size());
        assertEquals(3, function.annotations.size());
        assertNotNull(function.returnAnnotation);
        assertEquals("(a, /, b, *, flag)", function.getArgumentExpr());

        Block body = (Block) function.body;
        assertTrue(body.seq.get(0) instanceof AnnAssign);
        assertTrue(((org.yinwang.pysonar.ast.Assign) body.seq.get(1)).value instanceof JoinedStr);
        Node condition = ((If) body.seq.get(2)).test;
        assertTrue(((org.yinwang.pysonar.ast.BinOp) condition).left instanceof NamedExpr);
        Match match = (Match) body.seq.get(3);
        assertEquals(2, match.cases.size());
        assertEquals("MatchMapping", match.cases.get(0).pattern.patternKind);
        assertEquals(2, countCaptures(match.cases.get(0).pattern));

        Set<String> unresolved = analyzer.unresolved.stream().map(name -> name.id).collect(Collectors.toSet());
        assertFalse("walrus target should be bound", unresolved.contains("size"));
        assertFalse("match capture should be bound", unresolved.contains("kind"));
        assertFalse("match capture should be bound", unresolved.contains("number"));
        List<String> resultTypes = analyzer.allBindings.stream()
                .filter(binding -> "result".equals(binding.name))
                .map(binding -> binding.type.toString())
                .collect(Collectors.toList());
        assertTrue("positional-only and keyword-only arguments should participate in inference: " + resultTypes,
                resultTypes.stream().anyMatch(type -> type.contains("str")));
        assertTrue(analyzer.allBindings.stream()
                .filter(binding -> binding.kind == Binding.Kind.PARAMETER)
                .anyMatch(binding -> "a".equals(binding.name)
                        && binding.type == org.yinwang.pysonar.types.Types.IntInstance));
        assertTrue(analyzer.allBindings.stream()
                .filter(binding -> binding.kind == Binding.Kind.PARAMETER)
                .anyMatch(binding -> "flag".equals(binding.name)
                        && binding.type == org.yinwang.pysonar.types.Types.BoolInstance));
    }

    @Test
    public void convertsEveryPython310PatternKind() throws Exception
    {
        File source = temporaryFolder.newFile("patterns.py");
        Files.write(source.toPath(), (
                "class Color:\n" +
                "    RED = 1\n\n" +
                "class Point:\n" +
                "    pass\n\n" +
                "def classify(value):\n" +
                "    match value:\n" +
                "        case Color.RED:\n" +
                "            return 'value'\n" +
                "        case None:\n" +
                "            return 'singleton'\n" +
                "        case [only] | (only,):\n" +
                "            return only\n" +
                "        case [head, *tail]:\n" +
                "            return head\n" +
                "        case {'key': mapped}:\n" +
                "            return mapped\n" +
                "        case Point(x, y=y):\n" +
                "            return x\n" +
                "        case 1 | 2:\n" +
                "            return 'or'\n" +
                "        case _:\n" +
                "            return 'wildcard'\n").getBytes(StandardCharsets.UTF_8));

        Map<String, Object> options = new HashMap<>();
        options.put("quiet", true);
        Analyzer analyzer = new Analyzer(options);
        PyModule module;
        try
        {
            analyzer.analyze(source.getAbsolutePath());
            module = (PyModule) analyzer.getAstForFile(source.getAbsolutePath());
            analyzer.finish();
        }
        catch (Throwable failure)
        {
            analyzer.close();
            throw failure;
        }

        assertTrue(analyzer.unsupportedNodeTypes.isEmpty());
        FunctionDef function = (FunctionDef) module.body.seq.get(2);
        Match match = (Match) ((Block) function.body).seq.get(0);
        Set<String> kinds = new HashSet<>();
        for (org.yinwang.pysonar.ast.MatchCase matchCase : match.cases)
        {
            collectPatternKinds(matchCase.pattern, kinds);
        }
        assertTrue(kinds.contains("MatchValue"));
        assertTrue(kinds.contains("MatchSingleton"));
        assertTrue(kinds.contains("MatchSequence"));
        assertTrue(kinds.contains("MatchMapping"));
        assertTrue(kinds.contains("MatchClass"));
        assertTrue(kinds.contains("MatchStar"));
        assertTrue(kinds.contains("MatchAs"));
        assertTrue(kinds.contains("MatchOr"));
        boolean resolvedBothAlternatives = analyzer.references.keySet().stream()
                .filter(node -> node instanceof org.yinwang.pysonar.ast.Name)
                .filter(node -> "only".equals(((org.yinwang.pysonar.ast.Name) node).id))
                .anyMatch(node -> analyzer.references.get(node).size() == 2);
        assertTrue("a reference after an OR pattern should resolve to both capture sites",
                resolvedBothAlternatives);
    }

    private static void collectPatternKinds(MatchPattern pattern, Set<String> kinds)
    {
        kinds.add(pattern.patternKind);
        for (MatchPattern child : pattern.patterns)
        {
            collectPatternKinds(child, kinds);
        }
    }

    private static int countCaptures(MatchPattern pattern)
    {
        int count = pattern.captures.size();
        for (MatchPattern child : pattern.patterns)
        {
            count += countCaptures(child);
        }
        return count;
    }
}
