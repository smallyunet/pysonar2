package org.yinwang.pysonar.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolInformation;
import org.yinwang.pysonar.demos.Demo;
import org.yinwang.pysonar.lsp.AnalysisSession;
import org.yinwang.pysonar.lsp.AnalysisSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Command-line interface intended for coding agents and local automation. */
public final class Main {

    public static final String VERSION = "3.3.0";
    public static final int SCHEMA_VERSION = 1;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> COMMANDS = new LinkedHashSet<>(Arrays.asList(
            "doctor", "plan", "session", "context", "impact", "check", "skill", "help", "--help", "-h"));
    private static final List<String> SKILL_FILES = Arrays.asList(
            "SKILL.md", "agents/openai.yaml", "references/cli-schema.md");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !COMMANDS.contains(args[0])) {
            Demo.main(args);
            return;
        }
        int exitCode = run(args, System.in, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, System.in, out, err);
    }

    public static int run(String[] args, InputStream input, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0]) || "-h".equals(args[0])) {
                usage(out);
                return 0;
            }
            Arguments options = Arguments.parse(Arrays.copyOfRange(args, 1, args.length));
            switch (args[0]) {
                case "doctor":
                    options.assertOnly("format");
                    options.requireJson();
                    writeJson(out, doctor());
                    return 0;
                case "context":
                    options.assertOnly("root", "file", "line", "character", "max-results", "format");
                    options.requireJson();
                    writeJson(out, context(options, false));
                    return 0;
                case "plan":
                    options.assertOnly("root", "symbol", "intent", "max-results", "format");
                    options.requireFormat("compact-json", "json");
                    writeJson(out, plan(options));
                    return 0;
                case "session":
                    options.assertOnly("root", "format");
                    options.requireJson();
                    return session(options, input, out);
                case "impact":
                    options.assertOnly("root", "file", "line", "character", "max-results", "format");
                    options.requireJson();
                    writeJson(out, context(options, true));
                    return 0;
                case "check":
                    options.assertOnly("root", "changed", "format");
                    options.requireJson();
                    writeJson(out, check(options));
                    return 0;
                case "skill":
                    options.assertOnly("agent", "scope", "root");
                    return skill(options.positionals, options, out);
                default:
                    throw new CliException("Unknown command: " + args[0], 2);
            }
        } catch (CliException error) {
            writeJson(err, error(error.getMessage(), error.exitCode));
            return error.exitCode;
        } catch (Exception error) {
            writeJson(err, error(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), 1));
            return 1;
        }
    }

    private static Map<String, Object> doctor() {
        Map<String, Object> result = envelope("doctor");
        result.put("javaVersion", System.getProperty("java.version"));
        String pythonCommand = System.getenv().getOrDefault("PYSONAR_PYTHON", "python3");
        result.put("pythonCommand", pythonCommand);
        Map<String, Object> python = probePython(pythonCommand);
        result.put("python", python);
        result.put("status", Boolean.TRUE.equals(python.get("available")) ? "ok" : "degraded");
        result.put("capabilities", Arrays.asList(
                "symbol-plan", "persistent-session", "context", "reference-impact", "diagnostics", "skill-install"));
        return result;
    }

    private static Map<String, Object> probePython(String command) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        try {
            Process process = new ProcessBuilder(command, "--version").start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                result.put("error", "Timed out while checking the Python interpreter");
                return result;
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String version = stdout.isEmpty() ? stderr : stdout;
            result.put("available", process.exitValue() == 0);
            result.put("version", version);
            if (process.exitValue() != 0) {
                result.put("error", "Python interpreter exited with " + process.exitValue());
            }
        } catch (IOException error) {
            result.put("error", error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            result.put("error", "Interrupted while checking the Python interpreter");
        }
        return result;
    }

    private static Map<String, Object> context(Arguments args, boolean impact) throws Exception {
        Path root = root(args);
        Path file = requiredFile(root, args.required("file"));
        int line = positive(args.integer("line", -1), "line") - 1;
        int character = positive(args.integer("character", 1), "character") - 1;
        int maxResults = Math.max(1, args.integer("max-results", 50));
        long started = System.nanoTime();
        AnalysisSnapshot snapshot = analyze(root);
        Position position = new Position(line, character);

        List<Location> definitions = snapshot.definitions(file, position);
        List<Location> references = snapshot.references(file, position, false);
        Map<String, Object> result = envelope(impact ? "impact" : "context");
        result.put("root", root.toString());
        result.put("query", query(root, file, line, character));
        result.put("symbol", symbolAt(file, line, character));
        result.put("inferredType", hoverText(snapshot.hover(file, position)));
        result.put("definitions", locations(root, definitions, maxResults, true));
        result.put("references", locations(root, references, maxResults, true));
        result.put("truncated", definitions.size() > maxResults || references.size() > maxResults);
        result.put("analysisMillis", elapsedMillis(started));
        if (impact) {
            result.put("impactKind", "reference-based");
            result.put("affectedFiles", affectedFiles(root, definitions, references));
            result.put("limitations", Arrays.asList(
                    "This is a definition/reference impact surface, not a complete runtime call graph.",
                    "Dynamic imports, reflection, monkey patching, and unresolved types may be omitted."));
        } else {
            result.put("limitations", Collections.singletonList(
                    "Results describe the saved workspace state and may be incomplete for dynamic Python behavior."));
        }
        return result;
    }

    private static Map<String, Object> plan(Arguments args) throws Exception {
        List<String> symbols = args.all("symbol");
        if (symbols.isEmpty()) {
            throw new CliException("Missing required option --symbol", 2);
        }
        String intent = args.value("intent", "inspect");
        if (!"inspect".equals(intent) && !"change".equals(intent)) {
            throw new CliException("--intent must be inspect or change", 2);
        }
        int maxResults = Math.max(1, args.integer("max-results", 8));
        boolean compact = "compact-json".equals(args.value("format", "compact-json"));
        Path root = root(args);
        long started = System.nanoTime();
        AnalysisSnapshot snapshot = analyze(root);
        List<Map<String, Object>> queries = new ArrayList<>();
        for (String symbol : symbols) {
            queries.add(symbolPlan(root, snapshot, symbol, intent, maxResults, compact));
        }
        Map<String, Object> result = envelope("plan");
        result.put("queries", queries);
        if (!compact) {
            result.put("root", root.toString());
            result.put("analysisMillis", elapsedMillis(started));
            result.put("limitations", Arrays.asList(
                    "Plans use saved-workspace definitions and references, not a complete runtime call graph.",
                    "Dynamic imports, reflection, monkey patching, and unresolved types may be omitted."));
        }
        return result;
    }

    private static int session(Arguments args, InputStream input, PrintStream out) throws Exception {
        Path root = root(args);
        try (AnalysisSession session = new AnalysisSession(root, Collections.emptyList());
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            long started = System.nanoTime();
            AnalysisSnapshot snapshot = session.rebuildNow();
            Map<String, Object> ready = envelope("session-ready");
            ready.put("root", root.toString());
            ready.put("fileCount", snapshot.fileCount());
            ready.put("analysisMillis", elapsedMillis(started));
            writeJson(out, ready);

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                JsonObject request;
                try {
                    request = JsonParser.parseString(line).getAsJsonObject();
                } catch (Exception error) {
                    writeJson(out, error("Session input must be one JSON object per line", 2));
                    continue;
                }
                String requestId = jsonString(request, "id", null);
                String command = jsonString(request, "command", "");
                Map<String, Object> response;
                try {
                    switch (command) {
                        case "plan":
                            response = sessionPlan(root, snapshot, request);
                            break;
                        case "refresh":
                            long refreshStarted = System.nanoTime();
                            snapshot = session.rebuildNow();
                            response = envelope("refresh");
                            response.put("fileCount", snapshot.fileCount());
                            response.put("analysisMillis", elapsedMillis(refreshStarted));
                            break;
                        case "quit":
                            response = envelope("quit");
                            if (requestId != null) {
                                response.put("id", requestId);
                            }
                            writeJson(out, response);
                            return 0;
                        default:
                            throw new CliException("Session command must be plan, refresh, or quit", 2);
                    }
                } catch (CliException error) {
                    response = error(error.getMessage(), error.exitCode);
                }
                if (requestId != null) {
                    response.put("id", requestId);
                }
                writeJson(out, response);
            }
        }
        return 0;
    }

    private static Map<String, Object> sessionPlan(Path root, AnalysisSnapshot snapshot, JsonObject request) {
        String intent = jsonString(request, "intent", "inspect");
        if (!"inspect".equals(intent) && !"change".equals(intent)) {
            throw new CliException("intent must be inspect or change", 2);
        }
        int maxResults = jsonInteger(request, "maxResults", 8);
        if (maxResults < 1) {
            throw new CliException("maxResults must be positive", 2);
        }
        List<String> symbols = new ArrayList<>();
        JsonElement symbolValue = request.get("symbol");
        if (symbolValue != null && symbolValue.isJsonArray()) {
            for (JsonElement value : symbolValue.getAsJsonArray()) {
                symbols.add(value.getAsString());
            }
        } else if (symbolValue != null && symbolValue.isJsonPrimitive()) {
            symbols.add(symbolValue.getAsString());
        }
        if (symbols.isEmpty()) {
            throw new CliException("Session plan requires symbol", 2);
        }
        List<Map<String, Object>> queries = new ArrayList<>();
        for (String symbol : symbols) {
            queries.add(symbolPlan(root, snapshot, symbol, intent, maxResults, true));
        }
        Map<String, Object> response = envelope("plan");
        response.put("queries", queries);
        return response;
    }

    private static String jsonString(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static int jsonInteger(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static Map<String, Object> symbolPlan(Path root, AnalysisSnapshot snapshot, String symbol,
                                                   String intent, int maxResults, boolean compact) {
        List<SymbolInformation> matches = snapshot.exactWorkspaceSymbols(symbol, Integer.MAX_VALUE);
        List<Location> occurrences = snapshot.identifierLocations(symbol, maxResults + 1);
        boolean truncated = matches.size() > maxResults;
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (SymbolInformation match : matches.subList(0, Math.min(matches.size(), maxResults))) {
            Location declaration = match.getLocation();
            Path file = Paths.get(URI.create(declaration.getUri())).toAbsolutePath().normalize();
            Position position = declaration.getRange().getStart();
            List<Location> definitions = snapshot.definitions(file, position);
            if (definitions.isEmpty()) {
                definitions = Collections.singletonList(declaration);
            }
            List<Location> references = snapshot.references(file, position, false);
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("name", match.getName());
            candidate.put("qualifiedName", qualifiedName(root, file, match.getName()));
            if (!compact) {
                candidate.put("kind", match.getKind() == null ? null : match.getKind().toString());
                candidate.put("inferredType", hoverText(snapshot.hover(file, position)));
            }
            candidate.put("definitions", locations(root, definitions, maxResults, true));
            candidate.put("references", locations(root, references, maxResults, true));
            if ("change".equals(intent)) {
                candidate.put("affectedFiles", affectedFiles(root, definitions, references));
            }
            candidate.put("truncated", definitions.size() > maxResults || references.size() > maxResults);
            candidates.add(candidate);
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("symbol", symbol);
        query.put("intent", intent);
        query.put("matchCount", matches.size());
        query.put("candidates", candidates);
        query.put("occurrenceKind", "exact-identifier-text");
        List<Location> limitedOccurrences = occurrences.subList(0, Math.min(occurrences.size(), maxResults));
        query.put("returnedOccurrenceCount", limitedOccurrences.size());
        query.put("occurrences", locations(root, limitedOccurrences, maxResults, true));
        if ("change".equals(intent)) {
            query.put("affectedFiles", affectedFiles(root, Collections.emptyList(), limitedOccurrences));
        }
        query.put("truncated", truncated || occurrences.size() > maxResults);
        return query;
    }

    private static String qualifiedName(Path root, Path file, String name) {
        String relative = normalizeRelative(root, file);
        int extension = relative.lastIndexOf(".py");
        String module = extension == relative.length() - 3
                ? relative.substring(0, extension)
                : relative;
        module = module.replace('/', '.').replace('\\', '.');
        return module.isEmpty() ? name : module + "." + name;
    }

    private static Map<String, Object> check(Arguments args) throws Exception {
        Path root = root(args);
        Set<String> changed = new LinkedHashSet<>();
        for (String value : args.all("changed")) {
            for (String part : value.split(",")) {
                if (!part.trim().isEmpty()) {
                    changed.add(normalizeRelative(root, root.resolve(part.trim())));
                }
            }
        }
        long started = System.nanoTime();
        AnalysisSnapshot snapshot = analyze(root);
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        for (Map.Entry<String, List<Diagnostic>> entry : snapshot.diagnosticsByUri().entrySet()) {
            Path file = Paths.get(URI.create(entry.getKey())).toAbsolutePath().normalize();
            String relative = normalizeRelative(root, file);
            if (!changed.isEmpty() && !changed.contains(relative)) {
                continue;
            }
            for (Diagnostic diagnostic : entry.getValue()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("file", relative);
                item.put("startLine", diagnostic.getRange().getStart().getLine() + 1);
                item.put("startCharacter", diagnostic.getRange().getStart().getCharacter() + 1);
                item.put("endLine", diagnostic.getRange().getEnd().getLine() + 1);
                item.put("endCharacter", diagnostic.getRange().getEnd().getCharacter() + 1);
                item.put("severity", diagnostic.getSeverity() == null ? null : diagnostic.getSeverity().toString());
                item.put("message", diagnostic.getMessage());
                diagnostics.add(item);
            }
        }
        Map<String, Object> result = envelope("check");
        result.put("root", root.toString());
        result.put("changed", changed);
        result.put("diagnostics", diagnostics);
        result.put("diagnosticCount", diagnostics.size());
        result.put("analysisMillis", elapsedMillis(started));
        result.put("limitations", Collections.singletonList(
                "Diagnostics are conservative semantic findings, not a replacement for tests, lint, or Pyright."));
        return result;
    }

    private static AnalysisSnapshot analyze(Path root) throws IOException {
        return new AnalysisSession(root, Collections.emptyList()).rebuildNow();
    }

    private static int skill(List<String> positionals, Arguments args, PrintStream out) throws IOException {
        if (positionals.isEmpty()) {
            throw new CliException("Usage: pysonar skill <install|update|uninstall|doctor>", 2);
        }
        String action = positionals.get(0);
        String agent = args.value("agent", "portable");
        String scope = args.value("scope", "user");
        Path base = skillBase(agent, scope, args);
        Path target = base.resolve("pysonar-code-intelligence");
        Map<String, Object> result = envelope("skill-" + action);
        result.put("agent", agent);
        result.put("scope", scope);
        result.put("path", target.toString());
        switch (action) {
            case "install":
            case "update":
                installSkill(target, "update".equals(action));
                result.put("status", "update".equals(action) ? "updated" : "installed");
                break;
            case "uninstall":
                uninstallSkill(target);
                result.put("status", "uninstalled");
                break;
            case "doctor":
                result.put("status", Files.isRegularFile(target.resolve("SKILL.md")) ? "ok" : "missing");
                result.put("installed", Files.isRegularFile(target.resolve("SKILL.md")));
                break;
            default:
                throw new CliException("Unknown skill action: " + action, 2);
        }
        writeJson(out, result);
        return 0;
    }

    private static Path skillBase(String agent, String scope, Arguments args) {
        boolean project = "project".equals(scope);
        if (!project && !"user".equals(scope)) {
            throw new CliException("--scope must be user or project", 2);
        }
        Path home = Paths.get(System.getProperty("user.home"));
        Path root = Paths.get(args.value("root", System.getProperty("user.dir"))).toAbsolutePath().normalize();
        if (project && !Files.isDirectory(root)) {
            throw new CliException("Project root is not a directory: " + root, 2);
        }
        switch (agent) {
            case "portable":
            case "codex":
            case "gemini":
                return project ? root.resolve(".agents/skills") : home.resolve(".agents/skills");
            case "claude":
                return project ? root.resolve(".claude/skills") : home.resolve(".claude/skills");
            case "copilot":
                return project ? root.resolve(".github/skills") : home.resolve(".copilot/skills");
            case "cursor":
                return project ? root.resolve(".cursor/skills") : home.resolve(".cursor/skills");
            default:
                throw new CliException("Unsupported --agent: " + agent, 2);
        }
    }

    private static void installSkill(Path target, boolean update) throws IOException {
        Path marker = target.resolve(".pysonar-managed");
        if (update) {
            verifyManagedSkill(target);
        } else if (Files.exists(target)) {
            try (java.util.stream.Stream<Path> entries = Files.list(target)) {
                if (entries.findAny().isPresent()) {
                    throw new CliException("Skill already exists at " + target + "; use 'skill update' for a managed installation", 3);
                }
            }
        }
        Files.createDirectories(target);
        for (String relative : SKILL_FILES) {
            Path destination = target.resolve(relative);
            Files.createDirectories(destination.getParent());
            String resource = "/skills/pysonar-code-intelligence/" + relative;
            try (InputStream input = Main.class.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IOException("Packaged skill resource is missing: " + resource);
                }
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        List<String> manifest = new ArrayList<>();
        manifest.add("version=" + VERSION);
        for (String relative : SKILL_FILES) {
            manifest.add(relative + "=" + sha256(target.resolve(relative)));
        }
        Files.write(marker, manifest, StandardCharsets.UTF_8);
    }

    private static void uninstallSkill(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        verifyManagedSkill(target);
        List<Path> paths = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(target)) {
            stream.forEach(paths::add);
        }
        paths.sort(Collections.reverseOrder());
        for (Path path : paths) {
            Files.delete(path);
        }
    }

    private static void verifyManagedSkill(Path target) throws IOException {
        Path marker = target.resolve(".pysonar-managed");
        if (!Files.isRegularFile(marker)) {
            throw new CliException("Refusing to change an unmanaged skill at " + target, 3);
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator > 0 && !"version".equals(line.substring(0, separator))) {
                expected.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        for (String relative : SKILL_FILES) {
            Path file = target.resolve(relative);
            if (!Files.isRegularFile(file) || !sha256(file).equals(expected.get(relative))) {
                throw new CliException("Refusing to overwrite locally modified skill file: " + file, 3);
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder value = new StringBuilder();
            for (byte part : digest.digest()) {
                value.append(String.format("%02x", part & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Path root(Arguments args) throws IOException {
        Path root = Paths.get(args.value("root", System.getProperty("user.dir"))).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new CliException("Project root is not a directory: " + root, 2);
        }
        return root.toRealPath();
    }

    private static Path requiredFile(Path root, String value) throws IOException {
        Path file = Paths.get(value);
        if (!file.isAbsolute()) {
            file = root.resolve(file);
        }
        file = file.toAbsolutePath().normalize();
        if (!file.startsWith(root)) {
            throw new CliException("File must be inside the project root: " + value, 2);
        }
        if (!Files.isRegularFile(file)) {
            throw new CliException("File does not exist: " + file, 2);
        }
        return file.toRealPath();
    }

    private static Map<String, Object> query(Path root, Path file, int line, int character) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("file", normalizeRelative(root, file));
        query.put("line", line + 1);
        query.put("character", character + 1);
        return query;
    }

    private static String symbolAt(Path file, int line, int character) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (line < 0 || line >= lines.size()) {
            throw new CliException("Line is outside the file: " + (line + 1), 2);
        }
        String text = lines.get(line);
        int cursor = Math.min(Math.max(character, 0), text.length());
        while (cursor < text.length() && !Character.isJavaIdentifierPart(text.charAt(cursor)) && cursor > 0) {
            cursor--;
        }
        int start = cursor;
        int end = cursor;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
        }
        return start < end ? text.substring(start, end) : "";
    }

    private static String hoverText(Optional<Hover> hover) {
        if (!hover.isPresent() || hover.get().getContents() == null) {
            return null;
        }
        if (hover.get().getContents().isRight()) {
            MarkupContent content = hover.get().getContents().getRight();
            return content == null ? null : content.getValue();
        }
        return hover.get().getContents().getLeft().toString();
    }

    private static List<Map<String, Object>> locations(Path root, List<Location> values, int max, boolean snippets) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Location location : values.subList(0, Math.min(values.size(), max))) {
            Path file = Paths.get(URI.create(location.getUri())).toAbsolutePath().normalize();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("file", normalizeRelative(root, file));
            item.put("startLine", location.getRange().getStart().getLine() + 1);
            item.put("startCharacter", location.getRange().getStart().getCharacter() + 1);
            item.put("endLine", location.getRange().getEnd().getLine() + 1);
            item.put("endCharacter", location.getRange().getEnd().getCharacter() + 1);
            if (snippets) {
                item.put("snippet", snippet(file, location.getRange().getStart().getLine()));
            }
            result.add(item);
        }
        return result;
    }

    private static String snippet(Path file, int zeroBasedLine) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (zeroBasedLine < 0 || zeroBasedLine >= lines.size()) {
                return null;
            }
            return lines.get(zeroBasedLine).trim();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Set<String> affectedFiles(Path root, List<Location> definitions, List<Location> references) {
        Set<String> files = new LinkedHashSet<>();
        for (Location location : definitions) {
            files.add(normalizeRelative(root, Paths.get(URI.create(location.getUri()))));
        }
        for (Location location : references) {
            files.add(normalizeRelative(root, Paths.get(URI.create(location.getUri()))));
        }
        return files;
    }

    private static String normalizeRelative(Path root, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        return normalized.startsWith(root) ? root.relativize(normalized).toString().replace('\\', '/') : normalized.toString();
    }

    private static int positive(int value, String name) {
        if (value < 1) {
            throw new CliException("--" + name + " must be a positive integer", 2);
        }
        return value;
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static Map<String, Object> envelope(String command) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", SCHEMA_VERSION);
        result.put("cliVersion", VERSION);
        result.put("command", command);
        return result;
    }

    private static Map<String, Object> error(String message, int exitCode) {
        Map<String, Object> result = envelope("error");
        result.put("error", message);
        result.put("exitCode", exitCode);
        return result;
    }

    private static void writeJson(PrintStream stream, Object value) {
        stream.println(JSON.toJson(value));
        stream.flush();
    }

    private static void usage(PrintStream out) {
        out.println("PySonar2 agent CLI " + VERSION);
        out.println("Usage:");
        out.println("  pysonar doctor --format json");
        out.println("  pysonar plan --root DIR --symbol NAME [--intent inspect|change] [--max-results N] [--format compact-json]");
        out.println("  pysonar session --root DIR --format json  # JSONL plan/refresh/quit protocol on stdin/stdout");
        out.println("  pysonar context --root DIR --file FILE --line N [--character N] [--max-results N]");
        out.println("  pysonar impact --root DIR --file FILE --line N [--character N] [--max-results N]");
        out.println("  pysonar check --root DIR [--changed FILE[,FILE...]]");
        out.println("  pysonar skill <install|update|uninstall|doctor> [--agent portable|codex|claude|copilot|gemini|cursor] [--scope user|project]");
        out.println("Legacy static browser usage remains available: java -jar pysonar.jar SOURCE OUTPUT");
    }

    private static final class CliException extends RuntimeException {
        final int exitCode;

        CliException(String message, int exitCode) {
            super(message);
            this.exitCode = exitCode;
        }
    }

    private static final class Arguments {
        final Map<String, List<String>> values = new LinkedHashMap<>();
        final List<String> positionals = new ArrayList<>();

        static Arguments parse(String[] args) {
            Arguments parsed = new Arguments();
            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                if (!argument.startsWith("--")) {
                    parsed.positionals.add(argument);
                    continue;
                }
                String key = argument.substring(2);
                if (key.isEmpty() || i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new CliException("Missing value for " + argument, 2);
                }
                parsed.values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(args[++i]);
            }
            return parsed;
        }

        String required(String key) {
            String value = value(key, null);
            if (value == null) {
                throw new CliException("Missing required option --" + key, 2);
            }
            return value;
        }

        String value(String key, String fallback) {
            List<String> entries = values.get(key);
            return entries == null || entries.isEmpty() ? fallback : entries.get(entries.size() - 1);
        }

        List<String> all(String key) {
            return values.getOrDefault(key, Collections.emptyList());
        }

        int integer(String key, int fallback) {
            String value = value(key, null);
            if (value == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException error) {
                throw new CliException("--" + key + " must be an integer", 2);
            }
        }

        void requireJson() {
            requireFormat("json");
        }

        void requireFormat(String... supported) {
            String format = value("format", supported[0]);
            if (!Arrays.asList(supported).contains(format)) {
                throw new CliException("--format must be one of: " + String.join(", ", supported), 2);
            }
        }

        void assertOnly(String... supported) {
            Set<String> allowed = new LinkedHashSet<>(Arrays.asList(supported));
            for (String key : values.keySet()) {
                if (!allowed.contains(key)) {
                    throw new CliException("Unsupported option --" + key, 2);
                }
            }
        }
    }
}
