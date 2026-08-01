package org.yinwang.pysonar;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.yinwang.pysonar.demos.Demo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

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
                "from helper import answer\n\n" +
                "result = answer()\n").getBytes(StandardCharsets.UTF_8));
        File helper = new File(inputDir, "helper.py");
        Files.write(helper.toPath(), (
                "def answer():\n" +
                "    return 42\n").getBytes(StandardCharsets.UTF_8));
        File outputDir = temporaryFolder.newFolder("output");

        Demo.main(new String[]{inputDir.getAbsolutePath(), outputDir.getAbsolutePath()});

        File htmlFile = new File(outputDir, "sample.py.html");
        assertTrue("demo HTML was not generated", htmlFile.isFile());
        String html = new String(Files.readAllBytes(htmlFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(html.contains("<meta charset=\"utf-8\">"));
        assertTrue(html.contains("class='browser-shell'"));
        assertTrue(html.contains("class='skip-link'"));
        assertTrue(html.contains("class='symbol-tooltip'"));
        assertTrue(html.contains("data-tooltip="));
        assertTrue(html.contains("aria-describedby='symbol-tooltip'"));
        assertTrue(html.contains("answer"));
        assertTrue(html.contains("helper.py.html#helper.answer"));
        assertFalse("native title tooltips should not be generated", html.contains(" title='"));
        assertFalse("source paths should be project-relative", html.contains(inputDir.getAbsolutePath()));
        assertFalse("local file links should not be generated", html.contains("file://"));

        File indexFile = new File(outputDir, "index.html");
        assertTrue("demo index was not generated", indexFile.isFile());
        String index = new String(Files.readAllBytes(indexFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(index.contains("Code intelligence"));
        assertTrue(index.contains("sample.py.html"));
        assertFalse(index.contains(inputDir.getAbsolutePath()));
    }
}
