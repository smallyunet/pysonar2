package org.yinwang.pysonar;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@RunWith(Parameterized.class)
public class TestRefs
{
    private final String testPath;

    public TestRefs(String testPath)
    {
        this.testPath = testPath;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> testCases() throws IOException
    {
        Path tests = Paths.get("tests");
        assertTrue("tests directory is missing", Files.isDirectory(tests));

        List<Path> paths;
        try (java.util.stream.Stream<Path> stream = Files.walk(tests))
        {
            paths = stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().endsWith(".test"))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
        }

        assertTrue("no .test fixtures were discovered", !paths.isEmpty());

        List<Object[]> parameters = new ArrayList<>();
        for (Path path : paths)
        {
            Path refs = path.resolve("refs.json");
            assertTrue("missing reference snapshot: " + refs, Files.isRegularFile(refs));
            parameters.add(new Object[]{path.toString()});
        }
        return parameters;
    }

    @Test
    public void testRefs()
    {
        assertTrue("reference inference failed for " + testPath,
                new TestInference(testPath).runTest());
    }
}
