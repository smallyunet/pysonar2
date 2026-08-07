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
                + "<nav class='topbar-nav' aria-label='Demo sections'><a href='#examples'>Examples</a><a href='#capabilities'>Capabilities</a><a href='#files'>Files</a></nav>"
                + "<a class='topbar-link' href='https://github.com/smallyunet/pysonar2'>GitHub repository</a></header>"
                + "<main id='main-content' class='landing'><section class='hero-grid'><div class='hero'>"
                + "<span class='release-pill'>Local semantic engine · Python 3.10–3.14</span>"
                + "<h1>See what the analyzer knows—not just what it parsed.</h1>"
                + "<p>Walk through a real multi-file Python project and inspect definitions, references, inferred types, overrides, decorators, modern syntax, and explicit safety boundaries. Every page is generated locally—no hosted analysis service required.</p>"
                + "<div class='hero-actions'><a class='button primary' href='#examples'>Start a guided example</a>"
                + "<a class='button' href='" + escapeAttribute(entryHref) + "'>Open analyzed source</a>"
                + "<a class='button' href='https://github.com/smallyunet/pysonar2/tree/main/demo_project'>View demo on GitHub</a></div>"
                + "</div>" + analysisPreview() + "</section><section class='metrics' aria-label='Analysis summary'>"
                + metric(files.size(), "Python files") + metric(definitions, "Definitions") + metric(references, "Cross references")
                + metric(resolution, "Names resolved", "%")
                + "</section>" + guidedExamples()
                + safetyContract()
                + "<div id='capabilities' class='section-heading'><span class='section-kicker'>Capability matrix</span><h2>More than go-to-definition</h2>"
                + "<p>Every capability below is backed by a small source example you can open and navigate.</p></div>"
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
                + previewRow("Override", "Audited → Weighted → Base", "C3 MRO")
                + previewRow("Decorator", "@command → DemoCommand", "propagated")
                + previewRow("Pattern", "captured → local binding", "navigable")
                + previewRow("Async", "await fetch() → list[dict]", "unwrapped")
                + "<p>Hover a linked symbol to inspect its inferred type, then click it to move between definitions and references.</p>"
                + "</aside>";
    }

    private String previewRow(String label, String value, String status) {
        return "<div class='preview-row'><span>" + escapeText(label) + "</span><code>"
                + escapeText(value) + "</code><em>" + escapeText(status) + "</em></div>";
    }

    private String capabilityCards() {
        return "<section class='capability-grid' aria-label='Demonstrated semantic capabilities'>"
                + capability("01", "Imports & re-exports", "Follow public aliases through package APIs and module attributes.", "pysonar_demo/__init__.py")
                + capability("02", "Annotation-assisted flow", "Use declared types when runtime evidence remains unknown.", "pysonar_demo/models.py")
                + capability("03", "Properties & attributes", "Resolve computed properties to their inferred value types.", "pysonar_demo/models.py")
                + capability("04", "C3 override families", "Connect base methods, overrides, and calls in Python MRO order.", "pysonar_demo/strategies.py")
                + capability("05", "Decorator factories", "Propagate a decorator's returned object type to the exposed name.", "pysonar_demo/decorators.py")
                + capability("06", "Descriptor aliases", "Keep same-name static method aliases in one impact surface.", "pysonar_demo/decorators.py")
                + capability("07", "Walrus bindings", "Navigate assignment-expression targets and later reads.", "pysonar_demo/syntax.py")
                + capability("08", "Pattern captures", "Retain bindings created inside structural match cases.", "pysonar_demo/syntax.py")
                + capability("09", "Typed comprehensions", "Carry element types through filtered list comprehensions.", "pysonar_demo/syntax.py")
                + capability("10", "Async results", "Distinguish an awaitable call from its awaited result.", "pysonar_demo/feed.py")
                + capability("11", "Recursive inference", "Track values through recursive calls and numeric branches.", "pysonar_demo/scoring.py")
                + capability("12", "Cross-file orchestration", "Trace constructors, collections, and calls across service boundaries.", "pysonar_demo/service.py")
                + "</section>";
    }

    private String guidedExamples() {
        return "<section id='examples' class='guided-section' aria-labelledby='guided-title'>"
                + "<div class='section-heading'><span class='section-kicker'>Guided examples</span><h2 id='guided-title'>Six short paths through the semantic graph</h2>"
                + "<p>Pick a question, open the source, then follow the highlighted symbols and hover facts.</p></div>"
                + "<div class='guided-grid'>"
                + guidedExample("01", "Public API", "Where does an exported name really come from?", "Follow PredictionEngine through the package re-export to DemoApp.", "pysonar_demo/__init__.py", "cross-file binding")
                + guidedExample("02", "Type flow", "What type does this property produce?", "Open Market.display_name and trace its str result into Prediction.summary.", "pysonar_demo/models.py", "property inference")
                + guidedExample("03", "Inheritance", "Which override wins at runtime?", "Inspect adjust and audit_label across the AuditedStrategy diamond.", "pysonar_demo/strategies.py", "C3 resolution")
                + guidedExample("04", "Decorators", "What replaces a decorated function?", "Follow @command from inspect_symbol to the returned DemoCommand instance.", "pysonar_demo/decorators.py", "returned type")
                + guidedExample("05", "Modern Python", "Do new bindings stay navigable?", "Trace label from a walrus expression and captured from a match case.", "pysonar_demo/syntax.py", "binding preservation")
                + guidedExample("06", "Async", "What changes after await?", "Compare MarketFeed.fetch with the concrete payload list used by refresh.", "pysonar_demo/feed.py", "await unwrapping")
                + "</div></section>";
    }

    private String guidedExample(String number, String category, String title, String body,
                                 String file, String evidence) {
        return "<a class='guided-card' href='" + escapeAttribute(file + ".html") + "'>"
                + "<span class='guided-number'>" + escapeText(number) + "</span>"
                + "<div class='guided-content'><span class='guided-category'>" + escapeText(category) + "</span>"
                + "<h3>" + escapeText(title) + "</h3><p>" + escapeText(body) + "</p>"
                + "<div class='guided-meta'><code>" + escapeText(file) + "</code><span>" + escapeText(evidence) + "</span></div>"
                + "</div><span class='guided-arrow' aria-hidden='true'>→</span></a>";
    }

    private String safetyContract() {
        return "<section class='contract-section' aria-labelledby='contract-title'>"
                + "<div class='contract-copy'><span class='section-kicker'>Fail-closed analysis</span><h2 id='contract-title'>Useful evidence includes knowing when to stop.</h2>"
                + "<p>PySonar2 reports coverage and applicability alongside references. A resolved symbol in a fully parsed workspace can support impact review; detected dynamic injection remains explicit and inapplicable.</p>"
                + "<a class='text-link' href='https://github.com/smallyunet/pysonar2/blob/main/docs/change-safety-benchmark.md'>Read the change-safety benchmark →</a></div>"
                + "<div class='contract-console' aria-label='Example impact responses'>"
                + contractExample("static symbol", "complete", "true", "high", "ready")
                + contractExample("pytest fixture", "complete", "false", "unsupported", "stop")
                + "<p><code>unsupportedSemantics</code> is reported for fixture parameter injection instead of presenting missed references as complete.</p>"
                + "</div></section>";
    }

    private String contractExample(String label, String coverage, String applicable,
                                   String confidence, String status) {
        return "<div class='contract-example'><div><span>" + escapeText(label) + "</span>"
                + "<em class='contract-status " + escapeAttribute(status) + "'>" + escapeText(status) + "</em></div>"
                + "<code>{ &quot;coverageStatus&quot;: &quot;" + escapeText(coverage)
                + "&quot;, &quot;applicable&quot;: " + escapeText(applicable)
                + ", &quot;confidence&quot;: &quot;" + escapeText(confidence) + "&quot; }</code></div>";
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
        if (relativePath.endsWith("decorators.py")) {
            return "Decorator factories, returned object types, and descriptor aliases.";
        }
        if (relativePath.endsWith("syntax.py")) {
            return "Walrus bindings, match captures, typed comprehensions, and modern parameters.";
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
        $.msg("Usage:  java -jar pysonar-3.3.5.jar <file-or-dir> <output-dir>");
        $.msg("Example that generates an index for a Python 3 standard library:");
        $.msg(" java -jar pysonar-3.3.5.jar /usr/lib/python3 ./html");
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
