package org.yinwang.pysonar;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.yinwang.pysonar.ast.Name;
import org.yinwang.pysonar.ast.Node;
import org.yinwang.pysonar.types.Types;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SemanticCoreFeaturesTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void usesC3MroForDiamondAttributeResolution() throws Exception
    {
        Analyzer analyzer = analyzeProject("mro.py",
                "class Root:\n" +
                "    def route(self): return 'root'\n\n" +
                "class Left(Root):\n" +
                "    pass\n\n" +
                "class Right(Root):\n" +
                "    def route(self): return 'right'\n\n" +
                "class Leaf(Left, Right):\n" +
                "    pass\n\n" +
                "result = Leaf().route()\n");

        Binding rightRoute = binding(analyzer, "Right.route");
        assertTrue("Leaf(Left, Right) must resolve route through C3 to Right before Root",
                rightRoute.refs.stream().anyMatch(node -> node instanceof Name
                        && "route".equals(((Name) node).id)));
        Binding rootRoute = binding(analyzer, "Root.route");
        assertFalse("the call must not incorrectly resolve through Left -> Root depth-first",
                rootRoute.refs.stream().anyMatch(node -> node instanceof Name
                        && "route".equals(((Name) node).id)
                        && node.line > 10));
    }

    @Test
    public void usesAnnotationsWhenRuntimeEvidenceIsUnknown() throws Exception
    {
        Analyzer analyzer = analyzeProject("annotations.py",
                "class Market:\n" +
                "    def label(self) -> str:\n" +
                "        return 'active'\n\n" +
                "def render(market: Market) -> str:\n" +
                "    return market.label()\n\n" +
                "selected: Market\n" +
                "result = selected.label()\n" +
                "markets: list[Market]\n" +
                "first = markets[0]\n" +
                "generic_result = first.label()\n" +
                "runtime_wins: str = 1\n");

        Binding label = binding(analyzer, "Market.label");
        long references = label.refs.stream()
                .filter(node -> node instanceof Name && "label".equals(((Name) node).id))
                .count();
        assertTrue("parameter and variable annotations should seed both attribute references",
                references >= 3);
        assertTrue(analyzer.allBindings.stream()
                .filter(binding -> "selected".equals(binding.name))
                .anyMatch(binding -> binding.type.toString().contains("Market")));
        assertTrue("runtime inference must take precedence over a conflicting annotation",
                analyzer.allBindings.stream()
                        .filter(binding -> "runtime_wins".equals(binding.name))
                        .anyMatch(binding -> binding.type == Types.IntInstance));
    }

    @Test
    public void infersPropertyValuesInsteadOfBoundFunctions() throws Exception
    {
        Analyzer analyzer = analyzeProject("property.py",
                "class Market:\n" +
                "    @property\n" +
                "    def label(self) -> str:\n" +
                "        return 'active'\n\n" +
                "market = Market()\n" +
                "result = market.label\n");

        assertTrue(analyzer.allBindings.stream()
                .filter(binding -> "result".equals(binding.name))
                .anyMatch(binding -> binding.type.toString().contains("str")
                        && !binding.type.toString().contains("->")));
    }

    @Test
    public void distinguishesAsyncCallsFromAwaitedResults() throws Exception
    {
        Analyzer analyzer = analyzeProject("async_types.py",
                "async def fetch() -> str:\n" +
                "    return 'ready'\n\n" +
                "pending = fetch()\n\n" +
                "async def refresh():\n" +
                "    resolved = await fetch()\n" +
                "    return resolved\n\n" +
                "refresh()\n");

        assertTrue(analyzer.allBindings.stream()
                .filter(binding -> "pending".equals(binding.name))
                .anyMatch(binding -> binding.type.toString().contains("Awaitable[str]")));
        assertTrue(analyzer.allBindings.stream()
                .filter(binding -> "resolved".equals(binding.name))
                .anyMatch(binding -> "str".equals(binding.type.toString())));
    }

    @Test
    public void preservesBindingsThroughPackageReExportsAndModuleAttributes() throws Exception
    {
        File root = temporaryFolder.newFolder("reexport_project");
        File pkg = new File(root, "catalog");
        assertTrue(pkg.mkdir());
        write(new File(pkg, "models.py"),
                "class Market:\n" +
                "    def summary(self): return 'market'\n");
        write(new File(pkg, "__init__.py"),
                "from .models import Market as PublicMarket\n" +
                "__all__ = ['PublicMarket']\n");
        write(new File(root, "main.py"),
                "import catalog\n" +
                "market = catalog.PublicMarket()\n" +
                "text = market.summary()\n");

        Analyzer analyzer = analyze(root);
        Binding market = binding(analyzer, "models.Market");
        assertTrue("module attribute usage should point through the re-export to the class",
                market.refs.stream().anyMatch(node -> node instanceof Name
                        && "PublicMarket".equals(((Name) node).id)));
        Binding summary = binding(analyzer, "Market.summary");
        assertTrue(summary.refs.stream().anyMatch(node -> node instanceof Name
                && "summary".equals(((Name) node).id)));
    }

    @Test
    public void stateResolutionOrderFollowsC3Directly()
    {
        Analyzer analyzer = new Analyzer(new HashMap<>());
        try
        {
            State root = new State(null, State.StateType.CLASS);
            State left = new State(null, State.StateType.CLASS);
            State right = new State(null, State.StateType.CLASS);
            State leaf = new State(null, State.StateType.CLASS);
            left.addSuper(root);
            right.addSuper(root);
            leaf.addSuper(left);
            leaf.addSuper(right);
            root.insert("value", new Name("root"), Types.StrInstance, Binding.Kind.ATTRIBUTE);
            right.insert("value", new Name("right"), Types.IntInstance, Binding.Kind.ATTRIBUTE);

            assertSame(Types.IntInstance, State.makeUnion(leaf.lookupAttr("value")));
        }
        finally
        {
            analyzer.close();
        }
    }

    private Analyzer analyzeProject(String name, String source) throws Exception
    {
        File root = temporaryFolder.newFolder("project_" + System.nanoTime());
        write(new File(root, name), source);
        return analyze(root);
    }

    private Analyzer analyze(File root) throws Exception
    {
        Map<String, Object> options = new HashMap<>();
        options.put("quiet", true);
        Analyzer analyzer = new Analyzer(options);
        try
        {
            analyzer.analyze(root.getAbsolutePath());
            analyzer.finish();
            return analyzer;
        }
        catch (Throwable failure)
        {
            analyzer.close();
            throw failure;
        }
    }

    private Binding binding(Analyzer analyzer, String suffix)
    {
        Binding match = analyzer.allBindings.stream()
                .filter(binding -> binding.qname.endsWith(suffix))
                .findFirst().orElse(null);
        assertNotNull("missing binding ending in " + suffix, match);
        return match;
    }

    private void write(File file, String source) throws Exception
    {
        Files.write(file.toPath(), source.getBytes(StandardCharsets.UTF_8));
    }
}
