package org.yinwang.pysonar;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.yinwang.pysonar.types.Type;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ExpandedPythonSemanticsTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void modelsSetsBytesEllipsisAndGenerators() throws Exception {
        Analyzer analyzer = analyze(
                "values = {1, 2}\n" +
                "names = {name for name in ['a', 'b']}\n" +
                "raw = b'data'\n" +
                "marker = ...\n\n" +
                "def numbers():\n" +
                "    yield 1\n" +
                "    yield 2\n\n" +
                "generated = numbers()\n" +
                "expression = (item for item in [1, 2])\n");

        assertType(analyzer, "values", "set[int]");
        assertType(analyzer, "names", "set[str]");
        assertType(analyzer, "raw", "bytes");
        assertType(analyzer, "marker", "ellipsis");
        assertType(analyzer, "generated", "Generator[int]");
        assertType(analyzer, "expression", "Generator[int]");
    }

    @Test
    public void interpretsModernTypingContainersAndWrappers() throws Exception {
        Analyzer analyzer = analyze(
                "from typing import Annotated, AsyncIterator, Awaitable, Callable, Final, FrozenSet, Iterator, Literal\n\n" +
                "numbers: set[int]\n" +
                "frozen: FrozenSet[str]\n" +
                "iterator: Iterator[int]\n" +
                "async_items: AsyncIterator[str]\n" +
                "waiting: Awaitable[int]\n" +
                "metadata: Annotated[list[str], 'tag']\n" +
                "final_name: Final[str]\n" +
                "choice: int | str\n" +
                "literal: Literal['ready', 1]\n" +
                "callback: Callable[[int], str]\n" +
                "class_value: type[int]\n");

        assertType(analyzer, "numbers", "set[int]");
        assertType(analyzer, "frozen", "frozenset[str]");
        assertType(analyzer, "iterator", "Generator[int]");
        assertType(analyzer, "async_items", "AsyncIterator[str]");
        assertType(analyzer, "waiting", "Awaitable[int]");
        assertType(analyzer, "metadata", "[str]");
        assertType(analyzer, "final_name", "str");
        assertType(analyzer, "choice", "int");
        assertType(analyzer, "choice", "str");
        assertType(analyzer, "literal", "int");
        assertType(analyzer, "literal", "str");
        assertType(analyzer, "callback", "-> str");
        assertType(analyzer, "class_value", "<int>");
    }

    @Test
    public void propagatesPatternCaptureTypes() throws Exception {
        Analyzer analyzer = analyze(
                "sequence: list[int]\n" +
                "mapping: dict[str, int]\n\n" +
                "class Point:\n" +
                "    x: int\n\n" +
                "point: Point\n\n" +
                "match sequence:\n" +
                "    case [first, *rest]:\n" +
                "        sequence_result = first\n\n" +
                "match mapping:\n" +
                "    case {'x': mapped, **remaining}:\n" +
                "        mapping_result = mapped\n\n" +
                "match point:\n" +
                "    case Point(x=coordinate):\n" +
                "        point_result = coordinate\n");

        assertType(analyzer, "first", "int");
        assertType(analyzer, "rest", "[int]");
        assertType(analyzer, "mapped", "int");
        assertType(analyzer, "remaining", "dict");
        assertType(analyzer, "coordinate", "int");
    }

    @Test
    public void usesSyncAndAsyncProtocolsForBindings() throws Exception {
        Analyzer analyzer = analyze(
                "class Manager:\n" +
                "    def __enter__(self) -> str:\n" +
                "        return 'ready'\n" +
                "    def __exit__(self, exc_type, exc, tb):\n" +
                "        return False\n\n" +
                "class AsyncManager:\n" +
                "    async def __aenter__(self) -> int:\n" +
                "        return 1\n" +
                "    async def __aexit__(self, exc_type, exc, tb):\n" +
                "        return False\n\n" +
                "class AsyncNumbers:\n" +
                "    def __aiter__(self):\n" +
                "        return self\n" +
                "    async def __anext__(self) -> int:\n" +
                "        return 1\n\n" +
                "with Manager() as entered:\n" +
                "    sync_result = entered\n\n" +
                "async def consume():\n" +
                "    async with AsyncManager() as async_entered:\n" +
                "        async_result = async_entered\n" +
                "    async for item in AsyncNumbers():\n" +
                "        iterated = item\n" +
                "    return async_result\n\n" +
                "consume()\n");

        assertType(analyzer, "entered", "str");
        assertType(analyzer, "async_entered", "int");
        assertType(analyzer, "item", "int");
        assertType(analyzer, "iterated", "int");
    }

    @Test
    public void preservesPropertyTypesAcrossSetterDecoratorsAndAssignments() throws Exception {
        Analyzer analyzer = analyze(
                "class Model:\n" +
                "    @property\n" +
                "    def value(self) -> str:\n" +
                "        return 'initial'\n\n" +
                "    @value.setter\n" +
                "    def value(self, updated: str) -> None:\n" +
                "        self._value = updated\n\n" +
                "model = Model()\n" +
                "model.value = 'changed'\n" +
                "read_back = model.value\n");

        assertType(analyzer, "read_back", "str");
        assertTrue("property assignment should remain connected to its declaration",
                analyzer.allBindings.stream()
                        .filter(binding -> binding.qname.endsWith("Model.value"))
                        .flatMap(binding -> binding.refs.stream())
                        .anyMatch(node -> node.line == 11));
    }

    @Test
    public void propagatesClassDecoratorResults() throws Exception {
        Analyzer analyzer = analyze(
                "def replace():\n" +
                "    def decorate(original):\n" +
                "        class Wrapped:\n" +
                "            marker: int\n" +
                "        return Wrapped()\n" +
                "    return decorate\n\n" +
                "@replace()\n" +
                "class Original:\n" +
                "    pass\n\n" +
                "decorated_marker = Original.marker\n");

        assertType(analyzer, "decorated_marker", "int");
    }

    @Test
    public void isolatesPythonThreeComprehensionTargets() throws Exception {
        Analyzer analyzer = analyze(
                "items = [number * 2 for number in [1, 2]]\n" +
                "outside = number\n");

        assertType(analyzer, "items", "[int]");
        assertTrue("comprehension targets must not leak into the surrounding Python 3 scope",
                analyzer.unresolved.stream().anyMatch(name -> "number".equals(name.id)
                        && name.line == 2));
    }

    @Test
    public void distributesIterationAndAwaitAcrossUnionMembers() throws Exception {
        Analyzer analyzer = analyze(
                "iterables: list[int] | tuple[str]\n" +
                "for member in iterables:\n" +
                "    observed = member\n\n" +
                "from typing import Awaitable\n" +
                "pending: Awaitable[int] | Awaitable[str]\n\n" +
                "async def resolve():\n" +
                "    resolved = await pending\n" +
                "    return resolved\n\n" +
                "resolve()\n");

        assertType(analyzer, "member", "int");
        assertType(analyzer, "member", "str");
        assertType(analyzer, "resolved", "int");
        assertType(analyzer, "resolved", "str");
    }

    private Analyzer analyze(String source) throws Exception {
        File file = temporaryFolder.newFile("expanded_" + System.nanoTime() + ".py");
        Files.write(file.toPath(), source.getBytes(StandardCharsets.UTF_8));
        HashMap<String, Object> options = new HashMap<>();
        options.put("quiet", true);
        Analyzer analyzer = new Analyzer(options);
        try {
            analyzer.analyze(file.getAbsolutePath());
            analyzer.finish();
            return analyzer;
        } catch (Throwable failure) {
            analyzer.close();
            throw failure;
        }
    }

    private void assertType(Analyzer analyzer, String name, String expected) {
        Binding binding = analyzer.allBindings.stream()
                .filter(candidate -> name.equals(candidate.name))
                .reduce((first, second) -> second)
                .orElse(null);
        assertNotNull("missing binding for " + name, binding);
        Type type = binding.type;
        assertFalse("unknown type for " + name, type.isUnknownType());
        assertTrue(name + " expected " + expected + " but was " + type,
                type.toString().contains(expected));
    }
}
