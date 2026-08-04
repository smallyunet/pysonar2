package org.yinwang.pysonar.lsp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A conservative workspace-local Python import graph used to bound LSP rebuilds. */
final class ImportGraph {

    private static final Pattern IMPORT = Pattern.compile(
            "(?m)(?:^|;)\\s*import\\s+([^;\\n]+)");
    private static final Pattern FROM_IMPORT = Pattern.compile(
            "(?m)(?:^|;)\\s*from\\s+([.A-Za-z_][.A-Za-z0-9_]*)\\s+import\\s+"
                    + "(\\([^)]*\\)|[^;\\n]+)");
    private static final Pattern CONTINUED_IMPORT = Pattern.compile(
            "(?m)(?:^|;)\\s*(?:from|import)\\b[^\\n]*\\\\\\s*$");
    private static final Pattern DYNAMIC_IMPORT = Pattern.compile(
            "(?:\\b__import__\\s*\\(|\\bimportlib\\s*\\.\\s*import_module\\s*\\()");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Map<Path, Set<Path>> dependencies;
    private final Map<Path, Set<Path>> reverseDependencies;
    private final boolean complete;

    private ImportGraph(Map<Path, Set<Path>> dependencies, boolean complete) {
        this.dependencies = immutable(dependencies);
        Map<Path, Set<Path>> reverse = new LinkedHashMap<>();
        for (Map.Entry<Path, Set<Path>> entry : dependencies.entrySet()) {
            reverse.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashSet<>());
            for (Path dependency : entry.getValue()) {
                reverse.computeIfAbsent(dependency, ignored -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }
        this.reverseDependencies = immutable(reverse);
        this.complete = complete;
    }

    static ImportGraph build(Path root, Collection<Path> inputFiles) throws IOException {
        Map<Path, String> sources = new LinkedHashMap<>();
        for (Path input : inputFiles) {
            Path file = normalize(input);
            sources.put(file, Files.readString(file, StandardCharsets.UTF_8));
        }
        return build(root, sources);
    }

    static ImportGraph build(Path root, Map<Path, String> inputSources) {
        Path normalizedRoot = normalize(root);
        List<Path> files = new ArrayList<>();
        Map<Path, String> sources = new LinkedHashMap<>();
        for (Map.Entry<Path, String> input : inputSources.entrySet()) {
            Path normalized = normalize(input.getKey());
            sources.put(normalized, input.getValue());
        }
        for (Path input : sources.keySet()) {
            files.add(normalize(input));
        }
        Collections.sort(files);

        Map<String, Path> modules = new LinkedHashMap<>();
        Map<Path, String> preferredModules = new HashMap<>();
        for (Path file : files) {
            List<String> aliases = moduleAliases(normalizedRoot, file);
            for (String alias : aliases) {
                modules.putIfAbsent(alias, file);
            }
            if (!aliases.isEmpty()) {
                preferredModules.put(file, aliases.get(aliases.size() - 1));
            }
        }

        Map<Path, Set<Path>> dependencies = new LinkedHashMap<>();
        boolean complete = true;
        for (Path file : files) {
            String source = sources.get(file);
            String code = withoutCommentsAndStrings(source);
            Set<Path> fileDependencies = dependencies.computeIfAbsent(file, ignored -> new LinkedHashSet<>());
            Matcher imports = IMPORT.matcher(code);
            while (imports.find()) {
                for (String item : imports.group(1).split(",")) {
                    String module = item.trim().split("\\s+as\\s+", 2)[0].trim();
                    addResolvedModule(module, modules, fileDependencies);
                }
            }

            Matcher fromImports = FROM_IMPORT.matcher(code);
            while (fromImports.find()) {
                String rawModule = fromImports.group(1);
                String module = absoluteModule(rawModule, preferredModules.get(file), file.getFileName().toString());
                addResolvedModule(module, modules, fileDependencies);
                Matcher importedNames = IDENTIFIER.matcher(fromImports.group(2));
                while (importedNames.find()) {
                    String name = importedNames.group();
                    if (!"as".equals(name) && !module.isEmpty()) {
                        addResolvedModule(module + "." + name, modules, fileDependencies);
                    }
                }
            }

            if (DYNAMIC_IMPORT.matcher(code).find()) {
                fileDependencies.addAll(files);
                fileDependencies.remove(file);
            }
            if (CONTINUED_IMPORT.matcher(code).find()) {
                complete = false;
            }
        }
        return new ImportGraph(dependencies, complete);
    }

    boolean isComplete() {
        return complete;
    }

    Set<Path> affectedBy(Collection<Path> changed) {
        Set<Path> affected = new LinkedHashSet<>();
        Deque<Path> queue = new ArrayDeque<>();
        for (Path path : changed) {
            Path normalized = normalize(path);
            if (affected.add(normalized)) {
                queue.add(normalized);
            }
            if ("__init__.py".equals(String.valueOf(normalized.getFileName()))) {
                Path packageRoot = normalized.getParent();
                for (Path workspaceFile : dependencies.keySet()) {
                    if (workspaceFile.startsWith(packageRoot) && affected.add(workspaceFile)) {
                        queue.add(workspaceFile);
                    }
                }
            }
        }
        while (!queue.isEmpty()) {
            Path dependency = queue.removeFirst();
            for (Path dependent : reverseDependencies.getOrDefault(dependency, Collections.emptySet())) {
                if (affected.add(dependent)) {
                    queue.addLast(dependent);
                }
            }
        }
        return affected;
    }

    private static void addResolvedModule(String module, Map<String, Path> modules, Set<Path> dependencies) {
        if (module == null || module.isEmpty()) {
            return;
        }
        String candidate = module;
        while (!candidate.isEmpty()) {
            Path resolved = modules.get(candidate);
            if (resolved != null) {
                dependencies.add(resolved);
            }
            int dot = candidate.lastIndexOf('.');
            candidate = dot < 0 ? "" : candidate.substring(0, dot);
        }
    }

    private static String absoluteModule(String raw, String currentModule, String filename) {
        int dots = 0;
        while (dots < raw.length() && raw.charAt(dots) == '.') {
            dots++;
        }
        if (dots == 0) {
            return raw;
        }
        String suffix = raw.substring(dots);
        if (currentModule == null) {
            return suffix;
        }
        List<String> packageParts = new ArrayList<>();
        Collections.addAll(packageParts, currentModule.split("\\."));
        if (!"__init__.py".equals(filename) && !packageParts.isEmpty()) {
            packageParts.remove(packageParts.size() - 1);
        }
        int parents = Math.max(0, dots - 1);
        while (parents-- > 0 && !packageParts.isEmpty()) {
            packageParts.remove(packageParts.size() - 1);
        }
        if (!suffix.isEmpty()) {
            Collections.addAll(packageParts, suffix.split("\\."));
        }
        return String.join(".", packageParts);
    }

    private static List<String> moduleAliases(Path root, Path file) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        addRelativeAlias(aliases, root, file);

        Path cursor = file.getParent();
        while (cursor != null && cursor.startsWith(root)) {
            if ("src".equals(String.valueOf(cursor.getFileName()))) {
                addRelativeAlias(aliases, cursor, file);
            }
            if (cursor.equals(root)) {
                break;
            }
            cursor = cursor.getParent();
        }

        List<String> packageParts = new ArrayList<>();
        Path parent = file.getParent();
        while (parent != null && parent.startsWith(root)
                && Files.isRegularFile(parent.resolve("__init__.py"))) {
            packageParts.add(0, parent.getFileName().toString());
            parent = parent.getParent();
        }
        if (!"__init__.py".equals(file.getFileName().toString())) {
            packageParts.add(stripPythonSuffix(file.getFileName().toString()));
        }
        if (!packageParts.isEmpty()) {
            aliases.add(String.join(".", packageParts));
        }
        return new ArrayList<>(aliases);
    }

    private static void addRelativeAlias(Set<String> aliases, Path base, Path file) {
        if (!file.startsWith(base)) {
            return;
        }
        Path relative = base.relativize(file);
        List<String> parts = new ArrayList<>();
        for (Path part : relative) {
            parts.add(part.toString());
        }
        if (parts.isEmpty()) {
            return;
        }
        String filename = parts.remove(parts.size() - 1);
        if (!"__init__.py".equals(filename)) {
            parts.add(stripPythonSuffix(filename));
        }
        if (!parts.isEmpty()) {
            aliases.add(String.join(".", parts));
        }
    }

    private static String stripPythonSuffix(String filename) {
        return filename.endsWith(".py") ? filename.substring(0, filename.length() - 3) : filename;
    }

    private static String withoutCommentsAndStrings(String source) {
        StringBuilder result = new StringBuilder(source.length());
        char quote = 0;
        boolean triple = false;
        boolean comment = false;
        boolean escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            char nextNext = i + 2 < source.length() ? source.charAt(i + 2) : 0;
            if (comment) {
                if (current == '\n' || current == '\r') {
                    comment = false;
                    result.append(current);
                } else {
                    result.append(' ');
                }
                continue;
            }
            if (quote != 0) {
                if (current == '\n' || current == '\r') {
                    result.append(current);
                } else {
                    result.append(' ');
                }
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (triple && current == quote && next == quote && nextNext == quote) {
                    result.append("  ");
                    i += 2;
                    quote = 0;
                    triple = false;
                } else if (!triple && current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '#') {
                comment = true;
                result.append(' ');
            } else if (current == '\'' || current == '"') {
                quote = current;
                triple = next == current && nextNext == current;
                result.append(' ');
                if (triple) {
                    result.append("  ");
                    i += 2;
                }
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static Map<Path, Set<Path>> immutable(Map<Path, Set<Path>> input) {
        Map<Path, Set<Path>> result = new LinkedHashMap<>();
        for (Map.Entry<Path, Set<Path>> entry : input.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
