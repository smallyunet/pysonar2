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
        File inputDir = temporaryFolder.newFolder("demo_project");
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
        assertTrue(html.contains("class='analysis-context'"));
        assertTrue(html.contains("definitions</span>"));
        assertTrue(html.contains("references</span>"));
        assertTrue(html.contains("data-tooltip="));
        assertTrue(html.contains("aria-describedby='symbol-tooltip'"));
        assertTrue(html.contains("class='topbar-path'>demo_project/sample.py</span>"));
        assertTrue(html.contains("answer"));
        assertTrue(html.contains("helper.py.html#helper.answer"));
        assertFalse("native title tooltips should not be generated", html.contains(" title='"));
        assertFalse("source paths should be project-relative", html.contains(inputDir.getAbsolutePath()));
        assertFalse("local file links should not be generated", html.contains("file://"));

        File indexFile = new File(outputDir, "index.html");
        assertTrue("demo index was not generated", indexFile.isFile());
        String index = new String(Files.readAllBytes(indexFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(index.contains("See what the analyzer knows—not just what it parsed"));
        assertTrue(index.contains("class='analysis-preview'"));
        assertTrue(index.contains("Six short paths through the semantic graph"));
        assertTrue(index.contains("class='guided-grid'"));
        assertTrue(index.contains("Useful evidence includes knowing when to stop"));
        assertTrue(index.contains("class='contract-console'"));
        assertTrue(index.contains("More than go-to-definition"));
        assertTrue(index.contains("Imports &amp; re-exports"));
        assertTrue(index.contains("C3 override families"));
        assertTrue(index.contains("Decorator factories"));
        assertTrue(index.contains("Pattern captures"));
        assertTrue(index.contains("class='project-root'>demo_project/</code>"));
        assertTrue(index.contains("sample.py.html"));
        assertFalse("the project root is a label, not a duplicated output directory",
                index.contains("demo_project/sample.py.html"));
        assertFalse(index.contains(inputDir.getAbsolutePath()));
    }

    @Test
    public void generatesNavigationForModernPythonSyntax() throws Exception
    {
        File inputDir = temporaryFolder.newFolder("modern_demo");
        File source = new File(inputDir, "modern.py");
        Files.write(source.toPath(), (
                "class Item:\n" +
                "    value: int\n\n" +
                "def render(item, /, *, prefix=''):\n" +
                "    text = f'{prefix}{item.value}'\n" +
                "    if (size := len(text)) > 0:\n" +
                "        match {'text': text}:\n" +
                "            case {'text': captured}:\n" +
                "                return f'{captured}:{size}'\n" +
                "    return text\n").getBytes(StandardCharsets.UTF_8));
        File outputDir = temporaryFolder.newFolder("modern_output");

        Demo.main(new String[]{inputDir.getAbsolutePath(), outputDir.getAbsolutePath()});

        String html = new String(
                Files.readAllBytes(new File(outputDir, "modern.py.html").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(html.contains("class='topbar-path'>modern_demo/modern.py</span>"));
        assertTrue("walrus targets should be linkable definitions", html.contains("id='modern.render.size'"));
        assertTrue("walrus references should link back to their definition",
                html.contains("href='#modern.render.size'"));
        assertTrue("match captures should be linkable definitions",
                html.contains("id='modern.render.captured'"));
        assertTrue("match capture references should link back to their definition",
                html.contains("href='#modern.render.captured'"));
        assertTrue("f-string expressions should retain navigation", html.contains("href='#modern.render.prefix'"));

        String index = new String(
                Files.readAllBytes(new File(outputDir, "index.html").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(index.contains("class='project-root'>modern_demo/</code>"));
    }
}
