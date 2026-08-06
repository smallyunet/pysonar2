package org.yinwang.pysonar.demos;

import org.jetbrains.annotations.NotNull;
import org.yinwang.pysonar.$;
import org.yinwang.pysonar.Analyzer;
import org.yinwang.pysonar.Binding;
import org.yinwang.pysonar.Options;
import org.yinwang.pysonar.Progress;
import org.yinwang.pysonar.ast.Node;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


public class Demo {

    private static File OUTPUT_DIR;

    private static final String CSS = $.readResource("org/yinwang/pysonar/css/demo.css");
    private static final String JS = $.readResource("org/yinwang/pysonar/javascript/highlight.js");
    private static final String JS_DEBUG = $.readResource("org/yinwang/pysonar/javascript/highlight-debug.js");

    private Analyzer analyzer;
    private String rootPath;
    private Linker linker;


    private void makeOutputDir() {
        if (!OUTPUT_DIR.exists()) {
            OUTPUT_DIR.mkdirs();
            $.msg("Created directory: " + OUTPUT_DIR.getAbsolutePath());
        }
    }

    private void start(@NotNull String fileOrDir, Map<String, Object> options) throws Exception
    {
        File f = new File(fileOrDir);
        File rootDir = f.isFile() ? f.getParentFile() : f;
        try
        {
            rootPath = $.unifyPath(rootDir);
        }
        catch (Exception e)
        {
            $.die("File not found: " + f);
        }

        analyzer = new Analyzer(options);
        $.msg("Loading and analyzing files");
        try
        {
            analyzer.analyze(f.getPath());
        }
        finally
        {
            analyzer.finish();
        }

        generateHtml();
    }


    private void generateHtml() {
        $.msg("\nGenerating HTML");
        makeOutputDir();

        linker = new Linker(rootPath, OUTPUT_DIR);
        linker.findLinks(analyzer);

        List<String> files = new ArrayList<>();
        for (String path : analyzer.getLoadedFiles()) {
            if (path.startsWith(rootPath)) {
                files.add(path);
            }
        }
        Collections.sort(files);

        Progress progress = new Progress(files.size(), 50);

        for (String path : files) {
            progress.tick();
            File destFile = new File(OUTPUT_DIR, relativeSourcePath(path));
            destFile.getParentFile().mkdirs();
            String destPath = destFile.getAbsolutePath() + ".html";
            String html = markup(path);
            try {
                $.writeFile(destPath, html);
            } catch (Exception e) {
                $.msg("Failed to write: " + destPath);
            }
        }

        $.writeFile(new File(OUTPUT_DIR, "index.html").getAbsolutePath(), landingMarkup(files));

        $.msg("\nWrote " + files.size() + " source pages and an index to " + OUTPUT_DIR);
    }


    @NotNull
    private String markup(String path) {
        String source;

        try {
            source = $.readFile(path);
        } catch (Exception e) {
            $.die("Failed to read file: " + path);
            return "";
        }

        List<Style> styles = new ArrayList<>(linker.getStyles(path));

        String styledSource = new StyleApplier(path, source, styles).apply();
        String outline = new HtmlOutline(analyzer, linker).generate(path);
        String relativePath = relativeSourcePath(path);
        String displayPath = projectRootName() + "/" + relativePath;
        String homeHref = homeHref(relativePath);
        int fileDefinitions = definitionCount(path);
        int fileReferences = referenceCount(path);

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html>\n<html lang='en'>\n")
            .append("<head>\n")
            .append("<meta charset=\"utf-8\">\n")
            .append("<meta name='viewport' content='width=device-width, initial-scale=1'>\n")
            .append("<title>").append(escapeText(relativePath)).append(" · PySonar2</title>\n")
            .append("<style type='text/css'>\n").append(CSS).append("\n</style>\n")
            .append("</head>\n<body class='code-page'>\n")
            .append("<a class='skip-link' href='#main-content'>Skip to source code</a>")
            .append("<header class='topbar'>")
            .append("<a class='brand' href='").append(escapeAttribute(homeHref)).append("'>")
            .append("<span class='brand-mark'>P2</span><span>PySonar2</span></a>")
            .append("<span class='topbar-path'>").append(escapeText(displayPath)).append("</span>")
            .append("<a class='topbar-link' href='https://github.com/smallyunet/pysonar2'>View source</a>")
            .append("</header>\n")
            .append("<main id='main-content' class='browser-shell'>")
            .append("<aside class='outline-panel'>")
            .append("<div class='panel-heading'><span class='eyebrow'>Outline</span></div>")
            .append("<nav class='outline-nav' aria-label='Symbols in this file'>").append(outline).append("</nav>")
            .append("</aside>")
            .append("<section class='editor-panel' aria-label='Analyzed source code'>")
            .append("<div class='editor-heading'><span class='file-dot'></span>")
            .append("<span class='file-name'>").append(escapeText(relativePath)).append("</span>")
            .append("<span class='static-badge'>Static analysis</span></div>")
            .append("<div class='analysis-context'><span>").append(escapeText(describeFile(relativePath))).append("</span>")
            .append("<span class='context-stat'>").append(fileDefinitions).append(" definitions</span>")
            .append("<span class='context-stat'>").append(fileReferences).append(" references</span></div>")
            .append("<div class='code-scroll'><pre>")
            .append(addLineNumbers(styledSource))
            .append("</pre></div></section></main>")
            .append("<div id='symbol-tooltip' class='symbol-tooltip' role='tooltip' aria-hidden='true'></div>\n")
            .append("<script>\n")
            .append(Analyzer.self.hasOption("debug") ? JS_DEBUG : JS)
            .append("\n</script>\n")
            .append("</body></html>");
        return sb.toString();
    }


    @NotNull
    private String landingMarkup(@NotNull List<String> files) {
        String entryHref = files.isEmpty() ? "#files" : sourceHref(preferredEntry(files));
        String projectRoot = projectRootName() + "/";
        StringBuilder cards = new StringBuilder();
        for (String path : files) {
            String relative = relativeSourcePath(path);
            cards.append("<a class='file-card' href='").append(escapeAttribute(sourceHref(path))).append("'>")
                    .append("<code>").append(escapeText(relative)).append("</code>")
                    .append("<span>").append(escapeText(describeFile(relative))).append("</span></a>");
        }

        int references = 0;
        for (Node reference : analyzer.references.keys()) {
            if (reference.file != null && reference.file.startsWith(rootPath)) {
                references++;
            }
        }
        int definitions = 0;
        for (Binding binding : analyzer.getAllBindings()) {
            if (binding.getFile() != null && binding.getFile().startsWith(rootPath) && binding.start >= 0) {
                definitions++;
            }
        }
        int names = analyzer.resolved.size() + analyzer.unresolved.size();
        int resolution = names == 0 ? 100 : Math.round(analyzer.resolved.size() * 100f / names);

        return "<!doctype html>\n<html lang='en'><head>"
                + "<meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<title>PySonar2 · Interactive code intelligence demo</title>"
                + "<meta name='description' content='Explore a static, cross-referenced Python code browser generated by PySonar2.'>"
                + "<style>" + CSS + "</style></head><body class='landing-page'>"
                + "<a class='skip-link' href='#main-content'>Skip to demo content</a>"
                + "<header class='topbar'><a class='brand' href='index.html'><span class='brand-mark'>P2</span><span>PySonar2</span></a>"
                + "<a class='topbar-link' href='https://github.com/smallyunet/pysonar2'>GitHub repository</a></header>"
                + "<main id='main-content' class='landing'><section class='hero-grid'><div class='hero'>"
                + "<span class='release-pill'>Local semantic engine · Python 3.10–3.14</span>"
                + "<h1>Follow Python symbols across the whole project.</h1>"
                + "<p>Inspect a realistic multi-file application with package re-exports, annotation-assisted inference, properties, C3 inheritance, and async result types. Every link is generated locally—no runtime service required.</p>"
                + "<div class='hero-actions'><a class='button primary' href='" + escapeAttribute(entryHref) + "'>Explore analyzed source</a>"
                + "<a class='button' href='https://github.com/smallyunet/pysonar2/tree/main/demo_project'>View demo on GitHub</a></div>"
                + "</div>" + analysisPreview() + "</section><section class='metrics' aria-label='Analysis summary'>"
                + metric(files.size(), "Python files") + metric(definitions, "Definitions") + metric(references, "Cross references")
                + metric(resolution, "Names resolved", "%")
                + "</section><div class='section-heading'><span class='section-kicker'>Core semantics</span><h2>What this snapshot demonstrates</h2>"
                + "<p>Each capability is exercised by source you can open, inspect, and navigate.</p></div>"
                + capabilityCards()
                + "<div class='section-heading'><span class='section-kicker'>Analyzed workspace</span><h2>Project files "
                + "<code class='project-root'>" + escapeText(projectRoot) + "</code></h2>"
                + "<p>" + resolution + "% of names resolved across this generated project. Choose a file to inspect its symbols and inferred types.</p></div>"
                + "<section id='files' class='file-grid' aria-label='Demo source files'>" + cards + "</section>"
                + "<footer class='landing-footer'>Generated locally by PySonar2 with Python 3.14 · Static output published on GitHub Pages.</footer>"
                + "</main></body></html>";
    }


    private String metric(int value, String label) {
        return "<div class='metric'><strong>" + value + "</strong><span>" + escapeText(label) + "</span></div>";
    }

    private String metric(int value, String label, String suffix) {
        return "<div class='metric'><strong>" + value + escapeText(suffix) + "</strong><span>"
                + escapeText(label) + "</span></div>";
    }

    private String analysisPreview() {
        return "<aside class='analysis-preview' aria-label='Example semantic facts'>"
                + "<div class='preview-heading'><span class='preview-dot'></span><code>semantic snapshot</code><span>local</span></div>"
                + previewRow("Binding", "PredictionEngine → service.DemoApp", "semantic")
                + previewRow("Property", "market.display_name → str", "inferred")
                + previewRow("MRO", "Audited → Weighted → Base", "C3")
                + previewRow("Async", "await fetch() → list[dict]", "unwrapped")
                + "<p>Hover symbols in the browser to inspect inferred types, then follow references back to their definitions.</p>"
                + "</aside>";
    }

    private String previewRow(String label, String value, String status) {
        return "<div class='preview-row'><span>" + escapeText(label) + "</span><code>"
                + escapeText(value) + "</code><em>" + escapeText(status) + "</em></div>";
    }

    private String capabilityCards() {
        return "<section class='capability-grid' aria-label='Demonstrated semantic capabilities'>"
                + capability("01", "Imports & re-exports", "Follow aliases through package APIs and module attributes.", "pysonar_demo/__init__.py")
                + capability("02", "Annotation seeds", "Use declared types only when runtime evidence remains unknown.", "pysonar_demo/models.py")
                + capability("03", "Properties & types", "Resolve property references to their inferred value types.", "pysonar_demo/models.py")
                + capability("04", "C3 inheritance", "Choose attributes with Python's modern multiple-inheritance order.", "pysonar_demo/strategies.py")
                + capability("05", "Async results", "Distinguish an awaitable call from its awaited result.", "pysonar_demo/feed.py")
                + capability("06", "Cross-file flow", "Trace constructor values and calls across service boundaries.", "pysonar_demo/service.py")
                + "</section>";
    }

    private String capability(String number, String title, String body, String file) {
        return "<a class='capability-card' href='" + escapeAttribute(file + ".html") + "'>"
                + "<span class='capability-number'>" + escapeText(number) + "</span><div><h3>"
                + escapeText(title) + "</h3><p>" + escapeText(body) + "</p><code>"
                + escapeText(file) + "</code></div></a>";
    }

    private int definitionCount(String path) {
        int count = 0;
        for (Binding binding : analyzer.getAllBindings()) {
            if (path.equals(binding.getFile()) && binding.start >= 0) {
                count++;
            }
        }
        return count;
    }

    private int referenceCount(String path) {
        int count = 0;
        for (Node reference : analyzer.references.keys()) {
            if (path.equals(reference.file)) {
                count++;
            }
        }
        return count;
    }


    private String preferredEntry(List<String> files) {
        for (String path : files) {
            if (relativeSourcePath(path).equals("main.py")) {
                return path;
            }
        }
        return files.get(0);
    }


    private String sourceHref(String path) {
        return relativeSourcePath(path).replace(" ", "%20") + ".html";
    }


    private String describeFile(String relativePath) {
        if (relativePath.equals("main.py")) {
            return "Application entry point and cross-module calls.";
        }
        if (relativePath.endsWith("models.py")) {
            return "Domain classes, constructors, and computed state.";
        }
        if (relativePath.endsWith("service.py")) {
            return "Orchestration, branching, and collection inference.";
        }
        if (relativePath.endsWith("scoring.py")) {
            return "Functions, recursion, and numeric type flow.";
        }
        if (relativePath.endsWith("feed.py")) {
            return "Awaitable calls, awaited result types, and data normalization.";
        }
        if (relativePath.endsWith("strategies.py")) {
            return "C3 inheritance, overrides, class methods, and static methods.";
        }
        if (relativePath.endsWith("__init__.py")) {
            return "Package exports and cross-file references.";
        }
        return "Analyzed Python source with linked symbols.";
    }


    private String relativeSourcePath(String path) {
        String relative = path.substring(rootPath.length());
        while (relative.startsWith("/") || relative.startsWith("\\")) {
            relative = relative.substring(1);
        }
        return relative.replace(File.separatorChar, '/');
    }


    private String projectRootName() {
        String name = new File(rootPath).getName();
        return name.isEmpty() ? "project" : name;
    }


    private String homeHref(String relativePath) {
        int depth = 0;
        for (int i = 0; i < relativePath.length(); i++) {
            if (relativePath.charAt(i) == '/') {
                depth++;
            }
        }
        return "../".repeat(depth) + "index.html";
    }


    private String escapeText(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }


    private String escapeAttribute(String value) {
        return escapeText(value).replace("'", "&#39;").replace("\"", "&quot;");
    }


    @NotNull
    private String addLineNumbers(@NotNull String source) {
        StringBuilder result = new StringBuilder((int) (source.length() * 1.2));
        int count = 1;
        for (String line : source.split("\n")) {
            result.append("<span class='lineno'>");
            result.append(String.format("%1$4d", count++));
            result.append("</span>");
            if (!line.isEmpty()) {
                result.append(" ").append(line);
            }
            result.append("\n");
        }
        return result.toString();
    }


    private static void usage() {
        $.msg("Usage:  java -jar pysonar-3.3.3.jar <file-or-dir> <output-dir>");
        $.msg("Example that generates an index for a Python 3 standard library:");
        $.msg(" java -jar pysonar-3.3.3.jar /usr/lib/python3 ./html");
        System.exit(0);
    }


    @NotNull
    private static File checkFile(String path) {
        File f = new File(path);
        if (!f.canRead()) {
            $.die("Path not found or not readable: " + path);
        }
        return f;
    }


    public static void main(@NotNull String[] args) throws Exception {
        Options options = new Options(args);

        List<String> argsList = options.getArgs();
        String fileOrDir = argsList.get(0);
        OUTPUT_DIR = new File(argsList.get(1));

//        System.out.println("options: " + options.getOptionsMap());
        new Demo().start(fileOrDir, options.getOptionsMap());
        $.msg($.getGCStats());
    }
}
