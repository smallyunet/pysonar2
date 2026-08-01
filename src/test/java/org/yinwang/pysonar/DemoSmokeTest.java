package org.yinwang.pysonar;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.yinwang.pysonar.demos.Demo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

public class DemoSmokeTest
{
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void generatesSelfContainedLinkedHtml() throws Exception
    {
        File inputDir = temporaryFolder.newFolder("input");
        File source = new File(inputDir, "sample.py");
        Files.write(source.toPath(), (
                "def answer():\n" +
                "    return 42\n\n" +
                "result = answer()\n").getBytes(StandardCharsets.UTF_8));
        File outputDir = temporaryFolder.newFolder("output");

        Demo.main(new String[]{inputDir.getAbsolutePath(), outputDir.getAbsolutePath()});

        File htmlFile = new File(outputDir, "sample.py.html");
        assertTrue("demo HTML was not generated", htmlFile.isFile());
        String html = new String(Files.readAllBytes(htmlFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(html.contains("<meta charset=\"utf-8\">"));
        assertTrue(html.contains("function highlight"));
        assertTrue(html.contains("answer"));
        assertTrue(html.contains("href="));
    }
}
