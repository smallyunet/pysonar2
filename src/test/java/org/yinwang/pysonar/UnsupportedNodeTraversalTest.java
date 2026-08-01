package org.yinwang.pysonar;

import org.junit.Test;
import org.yinwang.pysonar.ast.Name;
import org.yinwang.pysonar.ast.Node;
import org.yinwang.pysonar.ast.Unsupported;
import org.yinwang.pysonar.types.Types;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UnsupportedNodeTraversalTest
{
    @Test
    public void retainsKnownChildrenInsideUnknownAstNodes()
    {
        Map<String, Object> options = new HashMap<>();
        options.put("quiet", true);
        Analyzer analyzer = new Analyzer(options);
        Parser parser = new Parser();
        try
        {
            Map<String, Object> child = astNode("Name", 4, 9);
            child.put("id", "value");

            Map<String, Object> future = astNode("FuturePythonNode", 0, 10);
            future.put("value", child);

            Node converted = parser.convert(future);
            assertTrue(converted instanceof Unsupported);
            Unsupported unsupported = (Unsupported) converted;
            assertEquals("FuturePythonNode", unsupported.originalType);
            assertEquals(1, unsupported.children.size());
            assertTrue(unsupported.children.get(0) instanceof Name);
            assertEquals("value", ((Name) unsupported.children.get(0)).id);
            assertTrue(analyzer.unsupportedNodeTypes.contains("FuturePythonNode"));

            State state = new State(null, State.StateType.GLOBAL);
            Name definition = new Name("value", "future.py", 0, 5, 1, 1);
            state.insert("value", definition, Types.IntInstance, Binding.Kind.VARIABLE);
            analyzer.inferencer.visit(unsupported, state);
            assertTrue("known descendants should still participate in reference analysis",
                    analyzer.resolved.contains(unsupported.children.get(0)));
        }
        finally
        {
            parser.close();
            analyzer.close();
        }
    }

    private static Map<String, Object> astNode(String type, int start, int end)
    {
        Map<String, Object> node = new HashMap<>();
        node.put("pysonar_node_type", type);
        node.put("start", (double) start);
        node.put("end", (double) end);
        node.put("lineno", 1.0);
        node.put("col_offset", (double) start);
        return node;
    }
}
