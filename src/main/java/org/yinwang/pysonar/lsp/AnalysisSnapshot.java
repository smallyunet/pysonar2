package org.yinwang.pysonar.lsp;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.yinwang.pysonar.Analyzer;
import org.yinwang.pysonar.Binding;
import org.yinwang.pysonar.ast.Node;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, editor-oriented projection of a completed PySonar2 analysis. */
public final class AnalysisSnapshot {

    private static final int WORKSPACE_SYMBOL_LIMIT = 200;

    private final Path root;
    private final Map<Path, String> sources;
    private final Map<Path, PositionCodec.LineIndex> positionIndexes;
    private final Map<Path, List<Occurrence>> occurrences;
    private final Map<Path, List<SymbolInformation>> symbols;
    private final Map<Path, List<org.eclipse.lsp4j.Diagnostic>> diagnostics;
    private final Set<Path> discoveredFiles;
    private final Set<Path> parsedFiles;
    private final Set<Path> failedFiles;
    private final Set<String> unsupportedNodeTypes;
    private final Set<String> pytestFixtureNames;

    private static final Pattern PYTEST_FIXTURE_DECORATOR = Pattern.compile(
            "(?ms)^\\s*@pytest\\.(?:fixture|yield_fixture)(?:\\s*\\([^\\n]*\\))?\\s*\\n"
                    + "(?:\\s*@[^\\n]+\\n)*\\s*(?:async\\s+)?def\\s+([A-Za-z_]\\w*)\\s*\\(");

    private AnalysisSnapshot(Path root,
                             Map<Path, String> sources,
                             Map<Path, PositionCodec.LineIndex> positionIndexes,
                             Map<Path, List<Occurrence>> occurrences,
                             Map<Path, List<SymbolInformation>> symbols,
                             Map<Path, List<org.eclipse.lsp4j.Diagnostic>> diagnostics,
                             Collection<Path> discoveredFiles,
                             Collection<Path> parsedFiles,
                             Collection<Path> failedFiles,
                             Collection<String> unsupportedNodeTypes,
                             Collection<String> pytestFixtureNames) {
        this.root = normalize(root);
        this.sources = immutableSources(sources);
        this.positionIndexes = Collections.unmodifiableMap(new LinkedHashMap<>(positionIndexes));
        this.occurrences = immutableMapOfLists(occurrences);
        this.symbols = immutableMapOfLists(symbols);
        this.diagnostics = immutableMapOfLists(diagnostics);
        this.discoveredFiles = immutablePaths(discoveredFiles);
        this.parsedFiles = immutablePaths(parsedFiles);
        this.failedFiles = immutablePaths(failedFiles);
        this.unsupportedNodeTypes = Collections.unmodifiableSet(new LinkedHashSet<>(unsupportedNodeTypes));
        this.pytestFixtureNames = Collections.unmodifiableSet(new LinkedHashSet<>(pytestFixtureNames));
    }

    public static AnalysisSnapshot empty(Path root) {
        return new AnalysisSnapshot(root, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
    }

    static AnalysisSnapshot from(Path root, Analyzer analyzer) {
        return from(root, analyzer, DiagnosticPolicy.conservative());
    }

    static AnalysisSnapshot from(Path root, Analyzer analyzer, DiagnosticPolicy diagnosticPolicy) {
        List<Path> discoveredFiles = new ArrayList<>();
        for (String filename : analyzer.getLoadedFiles()) {
            Path file = pathOf(filename);
            if (file != null) {
                discoveredFiles.add(file);
            }
        }
        return from(root, analyzer, diagnosticPolicy, discoveredFiles);
    }

    static AnalysisSnapshot from(Path root, Analyzer analyzer, DiagnosticPolicy diagnosticPolicy,
                                 Collection<Path> discoveredFiles) {
        Path normalizedRoot = normalize(root);
        Map<Path, String> sources = loadSources(analyzer.getLoadedFiles());
        Map<Path, PositionCodec.LineIndex> positionIndexes = indexSources(sources);
        Map<Path, Map<String, MutableOccurrence>> occurrenceBuilders = new LinkedHashMap<>();
        Map<Path, List<SymbolInformation>> symbols = new LinkedHashMap<>();

        for (Binding binding : analyzer.getAllBindings()) {
            Path file = pathOf(binding.getFile());
            if (file == null || binding.start < 0 || !sources.containsKey(file)) {
                continue;
            }
            mutableOccurrence(occurrenceBuilders, file, binding.start, binding.end).bindings.add(binding);
            if (file.startsWith(normalizedRoot) && !binding.isSynthetic()) {
                symbols.computeIfAbsent(file, ignored -> new ArrayList<>())
                        .add(symbol(binding, file, positionIndexes.get(file)));
            }
        }

        for (Node reference : analyzer.references.keys()) {
            Path file = pathOf(reference.file);
            if (file == null || !sources.containsKey(file)) {
                continue;
            }
            MutableOccurrence occurrence = mutableOccurrence(
                    occurrenceBuilders, file, reference.start, reference.end);
            occurrence.bindings.addAll(analyzer.references.get(reference));
        }

        Map<Path, List<Occurrence>> occurrences = new LinkedHashMap<>();
        Map<Binding, BindingView> bindingViews = new IdentityHashMap<>();
        for (Map.Entry<Path, Map<String, MutableOccurrence>> entry : occurrenceBuilders.entrySet()) {
            List<Occurrence> fileOccurrences = new ArrayList<>();
            for (MutableOccurrence mutable : entry.getValue().values()) {
                fileOccurrences.add(Occurrence.freeze(mutable, sources, positionIndexes, bindingViews));
            }
            fileOccurrences.sort(Comparator.comparingInt(Occurrence::length));
            occurrences.put(entry.getKey(), fileOccurrences);
        }

        for (List<SymbolInformation> fileSymbols : symbols.values()) {
            fileSymbols.sort(Comparator.comparingInt(symbol -> symbol.getLocation().getRange().getStart().getLine()));
        }

        Map<Path, List<org.eclipse.lsp4j.Diagnostic>> diagnostics = new LinkedHashMap<>();
        for (String filename : analyzer.semanticErrors.keySet()) {
            if (!diagnosticPolicy.enabled()) {
                break;
            }
            Path file = pathOf(filename);
            if (file == null || !sources.containsKey(file) || !file.startsWith(normalizedRoot)) {
                continue;
            }
            PositionCodec.LineIndex positionIndex = positionIndexes.get(file);
            List<org.eclipse.lsp4j.Diagnostic> converted = new ArrayList<>();
            for (org.yinwang.pysonar.Diagnostic problem : analyzer.getDiagnosticsForFile(filename)) {
                if (!diagnosticPolicy.shouldPublish(problem.msg)) {
                    continue;
                }
                if (converted.size() >= diagnosticPolicy.maxPerFile()) {
                    break;
                }
                org.eclipse.lsp4j.Diagnostic diagnostic = new org.eclipse.lsp4j.Diagnostic();
                diagnostic.setRange(range(positionIndex, problem.start, problem.end));
                diagnostic.setMessage(problem.msg);
                diagnostic.setSource("pysonar2");
                DiagnosticSeverity severity = diagnosticPolicy.severity(problem.msg);
                diagnostic.setSeverity(severity);
                if (severity == DiagnosticSeverity.Hint) {
                    diagnostic.setTags(Collections.singletonList(DiagnosticTag.Unnecessary));
                }
                converted.add(diagnostic);
            }
            if (!converted.isEmpty()) {
                diagnostics.put(file, converted);
            }
        }

        Set<Path> discovered = workspacePaths(normalizedRoot, discoveredFiles);
        Set<Path> parsed = workspacePaths(normalizedRoot, pathsOf(analyzer.getLoadedFiles()));
        Set<Path> failed = workspacePaths(normalizedRoot, pathsOf(analyzer.failedToParse));
        parsed.retainAll(discovered);
        failed.retainAll(discovered);
        return new AnalysisSnapshot(normalizedRoot, sources, positionIndexes, occurrences, symbols, diagnostics,
                discovered, parsed, failed, analyzer.unsupportedNodeTypes,
                detectPytestFixtureNames(sources));
    }

    /**
     * Replaces editor data for a conservative affected-file closure while retaining the
     * immutable slices for unrelated files. Reference lists are reconciled by definition
     * location so queries from either side of an incremental boundary remain complete.
     */
    AnalysisSnapshot replaceFiles(AnalysisSnapshot replacement, Collection<Path> affectedFiles) {
        Set<Path> affected = new LinkedHashSet<>();
        Set<String> affectedUris = new LinkedHashSet<>();
        for (Path file : affectedFiles) {
            Path normalized = normalize(file);
            affected.add(normalized);
            affectedUris.add(normalized.toUri().toString());
        }

        Map<String, BindingView> newBindings = bindingViewsByDefinition(
                replacement.occurrences, null);
        Map<String, BindingView> oldBindings = bindingViewsByDefinition(
                occurrences, newBindings.keySet());

        Map<Path, String> mergedSources = new LinkedHashMap<>(sources);
        Map<Path, PositionCodec.LineIndex> mergedIndexes = new LinkedHashMap<>(positionIndexes);
        Map<Path, List<Occurrence>> mergedOccurrences = new LinkedHashMap<>();
        Map<Path, List<SymbolInformation>> mergedSymbols = new LinkedHashMap<>(symbols);
        Map<Path, List<org.eclipse.lsp4j.Diagnostic>> mergedDiagnostics = new LinkedHashMap<>(diagnostics);

        for (Path file : affected) {
            mergedSources.remove(file);
            mergedIndexes.remove(file);
            mergedSymbols.remove(file);
            mergedDiagnostics.remove(file);
        }
        for (Map.Entry<Path, String> entry : replacement.sources.entrySet()) {
            if (affected.contains(entry.getKey()) || !entry.getKey().startsWith(root)) {
                mergedSources.put(entry.getKey(), entry.getValue());
                mergedIndexes.put(entry.getKey(), replacement.positionIndexes.get(entry.getKey()));
            }
        }
        copyAffectedLists(replacement.symbols, mergedSymbols, affected);
        copyAffectedLists(replacement.diagnostics, mergedDiagnostics, affected);

        for (Map.Entry<Path, List<Occurrence>> entry : occurrences.entrySet()) {
            if (!affected.contains(entry.getKey())) {
                mergedOccurrences.put(entry.getKey(), reconcileOccurrences(
                        entry.getValue(), newBindings, affectedUris, false));
            }
        }
        for (Map.Entry<Path, List<Occurrence>> entry : replacement.occurrences.entrySet()) {
            if (affected.contains(entry.getKey())) {
                mergedOccurrences.put(entry.getKey(), reconcileOccurrences(
                        entry.getValue(), oldBindings, affectedUris, true));
            } else if (!entry.getKey().startsWith(root) && !mergedOccurrences.containsKey(entry.getKey())) {
                mergedOccurrences.put(entry.getKey(), reconcileOccurrences(
                        entry.getValue(), oldBindings, affectedUris, true));
            }
        }

        Set<Path> mergedParsed = new LinkedHashSet<>(parsedFiles);
        Set<Path> mergedFailed = new LinkedHashSet<>(failedFiles);
        mergedParsed.removeAll(affected);
        mergedFailed.removeAll(affected);
        mergedParsed.addAll(replacement.parsedFiles);
        mergedFailed.addAll(replacement.failedFiles);
        Set<String> mergedUnsupported = new LinkedHashSet<>(unsupportedNodeTypes);
        mergedUnsupported.addAll(replacement.unsupportedNodeTypes);

        return new AnalysisSnapshot(root, mergedSources, mergedIndexes, mergedOccurrences,
                mergedSymbols, mergedDiagnostics, replacement.discoveredFiles, mergedParsed, mergedFailed,
                mergedUnsupported, detectPytestFixtureNames(mergedSources));
    }

    public Optional<Hover> hover(Path file, Position position) {
        Optional<Occurrence> occurrence = occurrenceAt(file, position);
        if (!occurrence.isPresent()) {
            return Optional.empty();
        }

        LinkedHashSet<String> types = new LinkedHashSet<>();
        String documentation = null;
        for (BindingView binding : occurrence.get().bindings) {
            if (!binding.type.isEmpty()) {
                types.add(binding.type);
            }
            if (documentation == null && binding.documentation != null && !binding.documentation.trim().isEmpty()) {
                documentation = binding.documentation.trim();
            }
        }
        if (types.isEmpty() && documentation == null) {
            return Optional.empty();
        }

        StringBuilder markdown = new StringBuilder();
        if (!types.isEmpty()) {
            markdown.append("```python\n");
            markdown.append(String.join(" | ", types));
            markdown.append("\n```");
        }
        if (documentation != null) {
            if (markdown.length() > 0) {
                markdown.append("\n\n");
            }
            markdown.append(documentation);
        }
        MarkupContent content = new MarkupContent(MarkupKind.MARKDOWN, markdown.toString());
        Hover hover = new Hover();
        hover.setContents(Either.forRight(content));
        hover.setRange(toRange(normalize(file), occurrence.get().start, occurrence.get().end));
        return Optional.of(hover);
    }

    public List<Location> definitions(Path file, Position position) {
        Optional<Occurrence> occurrence = occurrenceAt(file, position);
        if (!occurrence.isPresent()) {
            return Collections.emptyList();
        }
        LinkedHashMap<String, Location> result = new LinkedHashMap<>();
        for (BindingView binding : occurrence.get().bindings) {
            if (binding.definition != null) {
                result.put(locationKey(binding.definition), binding.definition);
            }
        }
        return new ArrayList<>(result.values());
    }

    public List<Location> references(Path file, Position position, boolean includeDeclaration) {
        Optional<Occurrence> occurrence = occurrenceAt(file, position);
        if (!occurrence.isPresent()) {
            return Collections.emptyList();
        }
        LinkedHashMap<String, Location> result = new LinkedHashMap<>();
        for (BindingView binding : occurrence.get().bindings) {
            if (includeDeclaration && binding.definition != null) {
                result.put(locationKey(binding.definition), binding.definition);
            }
            for (Location reference : binding.references) {
                result.put(locationKey(reference), reference);
            }
        }
        return new ArrayList<>(result.values());
    }

    public List<Either<SymbolInformation, DocumentSymbol>> documentSymbols(Path file) {
        List<Either<SymbolInformation, DocumentSymbol>> result = new ArrayList<>();
        for (SymbolInformation symbol : symbols.getOrDefault(normalize(file), Collections.emptyList())) {
            result.add(Either.forLeft(symbol));
        }
        return result;
    }

    public List<Either<SymbolInformation, WorkspaceSymbol>> workspaceSymbols(String query) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<Either<SymbolInformation, WorkspaceSymbol>> result = new ArrayList<>();
        for (List<SymbolInformation> fileSymbols : symbols.values()) {
            for (SymbolInformation symbol : fileSymbols) {
                String haystack = (symbol.getName() + " " + symbol.getContainerName()).toLowerCase(Locale.ROOT);
                if (haystack.contains(needle)) {
                    result.add(Either.forLeft(symbol));
                    if (result.size() >= WORKSPACE_SYMBOL_LIMIT) {
                        return result;
                    }
                }
            }
        }
        return result;
    }

    /** Returns exact symbol-name or qualified-name matches without fuzzy-result truncation. */
    public List<SymbolInformation> exactWorkspaceSymbols(String query, int limit) {
        String needle = query == null ? "" : query;
        int boundedLimit = Math.max(1, limit);
        List<SymbolInformation> result = new ArrayList<>();
        for (List<SymbolInformation> fileSymbols : symbols.values()) {
            for (SymbolInformation symbol : fileSymbols) {
                if (needle.equals(symbol.getName()) || needle.equals(symbol.getContainerName())) {
                    result.add(symbol);
                    if (result.size() >= boundedLimit) {
                        return result;
                    }
                }
            }
        }
        return result;
    }

    /** Returns exact identifier text occurrences as a conservative fallback to semantic references. */
    public List<Location> identifierLocations(String identifier, int limit) {
        if (identifier == null || identifier.isEmpty()) {
            return Collections.emptyList();
        }
        for (int index = 0; index < identifier.length(); index++) {
            if (!Character.isJavaIdentifierPart(identifier.charAt(index))) {
                return Collections.emptyList();
            }
        }
        int boundedLimit = Math.max(1, limit);
        List<Location> result = new ArrayList<>();
        for (Map.Entry<Path, String> entry : sources.entrySet()) {
            if (!entry.getKey().startsWith(root)) {
                continue;
            }
            String source = entry.getValue();
            int offset = 0;
            while ((offset = source.indexOf(identifier, offset)) >= 0) {
                int end = offset + identifier.length();
                boolean leftBoundary = offset == 0 || !Character.isJavaIdentifierPart(source.charAt(offset - 1));
                boolean rightBoundary = end == source.length()
                        || !Character.isJavaIdentifierPart(source.charAt(end));
                if (leftBoundary && rightBoundary) {
                    result.add(new Location(entry.getKey().toUri().toString(),
                            toRange(entry.getKey(), offset, end)));
                    if (result.size() >= boundedLimit) {
                        return result;
                    }
                }
                offset = end;
            }
        }
        return result;
    }

    public Map<String, List<org.eclipse.lsp4j.Diagnostic>> diagnosticsByUri() {
        Map<String, List<org.eclipse.lsp4j.Diagnostic>> result = new LinkedHashMap<>();
        for (Map.Entry<Path, List<org.eclipse.lsp4j.Diagnostic>> entry : diagnostics.entrySet()) {
            result.put(entry.getKey().toUri().toString(), entry.getValue());
        }
        return result;
    }

    public int fileCount() {
        int count = 0;
        for (Path file : sources.keySet()) {
            if (file.startsWith(root)) {
                count++;
            }
        }
        return count;
    }

    public int discoveredFileCount() {
        return discoveredFiles.size();
    }

    public int parsedFileCount() {
        return parsedFiles.size();
    }

    public List<String> failedFiles() {
        List<String> result = new ArrayList<>();
        for (Path file : failedFiles) {
            result.add(relativePath(file));
        }
        Collections.sort(result);
        return result;
    }

    public List<String> unsupportedNodeTypes() {
        List<String> result = new ArrayList<>(unsupportedNodeTypes);
        Collections.sort(result);
        return result;
    }

    public List<String> unsupportedSemantics() {
        List<String> result = new ArrayList<>();
        if (!pytestFixtureNames.isEmpty()) {
            result.add("pytest-fixture-parameter-injection");
        }
        return result;
    }

    public List<String> unsupportedSemanticSymbols() {
        List<String> result = new ArrayList<>(pytestFixtureNames);
        Collections.sort(result);
        return result;
    }

    public List<String> unsupportedSemanticsFor(String symbol) {
        if (symbol != null && pytestFixtureNames.contains(symbol)) {
            return Collections.singletonList("pytest-fixture-parameter-injection");
        }
        return Collections.emptyList();
    }

    public String coverageStatus() {
        if (discoveredFiles.isEmpty()) {
            return "empty";
        }
        return failedFiles.isEmpty() && unsupportedNodeTypes.isEmpty()
                && parsedFiles.containsAll(discoveredFiles) ? "complete" : "partial";
    }

    private static Set<String> detectPytestFixtureNames(Map<Path, String> sources) {
        Set<String> result = new LinkedHashSet<>();
        for (String source : sources.values()) {
            java.util.regex.Matcher matcher = PYTEST_FIXTURE_DECORATOR.matcher(source);
            while (matcher.find()) {
                result.add(matcher.group(1));
            }
        }
        return result;
    }

    public boolean isCoverageComplete() {
        return "complete".equals(coverageStatus());
    }

    public boolean isParsed(Path file) {
        return parsedFiles.contains(normalize(file));
    }

    public boolean hasOccurrence(Path file, Position position) {
        return occurrenceAt(file, position).isPresent();
    }

    private Optional<Occurrence> occurrenceAt(Path file, Position position) {
        Path normalized = normalize(file);
        String source = sources.get(normalized);
        if (source == null) {
            return Optional.empty();
        }
        PositionCodec.LineIndex positionIndex = positionIndexes.get(normalized);
        int offset = positionIndex == null
                ? PositionCodec.toCodePointOffset(source, position)
                : positionIndex.toCodePointOffset(position);
        for (Occurrence occurrence : occurrences.getOrDefault(normalized, Collections.emptyList())) {
            int effectiveEnd = Math.max(occurrence.end, occurrence.start + 1);
            if (offset >= occurrence.start && offset <= effectiveEnd) {
                return Optional.of(occurrence);
            }
        }
        return Optional.empty();
    }

    private Range toRange(Path file, int start, int end) {
        PositionCodec.LineIndex positionIndex = positionIndexes.get(file);
        return positionIndex == null
                ? new Range(new Position(0, 0), new Position(0, 0))
                : range(positionIndex, start, end);
    }

    private static SymbolInformation symbol(Binding binding, Path file, PositionCodec.LineIndex positionIndex) {
        SymbolInformation symbol = new SymbolInformation();
        symbol.setName(binding.name);
        symbol.setKind(symbolKind(binding.kind));
        symbol.setLocation(new Location(file.toUri().toString(), range(positionIndex, binding.start, binding.end)));
        symbol.setContainerName(binding.qname);
        return symbol;
    }

    private static SymbolKind symbolKind(Binding.Kind kind) {
        switch (kind) {
            case CLASS:
                return SymbolKind.Class;
            case FUNCTION:
                return SymbolKind.Function;
            case METHOD:
                return SymbolKind.Method;
            case CONSTRUCTOR:
                return SymbolKind.Constructor;
            case MODULE:
                return SymbolKind.Module;
            case ATTRIBUTE:
                return SymbolKind.Field;
            case PARAMETER:
                return SymbolKind.Variable;
            case SCOPE:
            case VARIABLE:
            default:
                return SymbolKind.Variable;
        }
    }

    private static MutableOccurrence mutableOccurrence(
            Map<Path, Map<String, MutableOccurrence>> occurrences, Path file, int start, int end) {
        Map<String, MutableOccurrence> byRange = occurrences.computeIfAbsent(file, ignored -> new LinkedHashMap<>());
        String key = start + ":" + end;
        return byRange.computeIfAbsent(key, ignored -> new MutableOccurrence(start, end));
    }

    private static Map<Path, String> loadSources(Collection<String> files) {
        Map<Path, String> result = new LinkedHashMap<>();
        for (String filename : files) {
            Path file = pathOf(filename);
            if (file == null || result.containsKey(file)) {
                continue;
            }
            try {
                result.put(file, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // A file may disappear between analysis and snapshot creation.
            }
        }
        return result;
    }

    private static Map<Path, PositionCodec.LineIndex> indexSources(Map<Path, String> sources) {
        Map<Path, PositionCodec.LineIndex> result = new LinkedHashMap<>();
        for (Map.Entry<Path, String> entry : sources.entrySet()) {
            result.put(entry.getKey(), PositionCodec.index(entry.getValue()));
        }
        return result;
    }

    private static Range range(PositionCodec.LineIndex positionIndex, int start, int end) {
        int boundedStart = Math.max(0, start);
        int boundedEnd = Math.max(boundedStart, end);
        return new Range(positionIndex.toPosition(boundedStart), positionIndex.toPosition(boundedEnd));
    }

    private static Path pathOf(String filename) {
        if (filename == null || filename.startsWith("http://") || filename.startsWith("https://")) {
            return null;
        }
        try {
            return normalize(Path.of(filename));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Collection<Path> pathsOf(Collection<String> filenames) {
        List<Path> result = new ArrayList<>();
        for (String filename : filenames) {
            Path file = pathOf(filename);
            if (file != null) {
                result.add(file);
            }
        }
        return result;
    }

    private static Set<Path> workspacePaths(Path root, Collection<Path> paths) {
        Set<Path> result = new LinkedHashSet<>();
        for (Path path : paths) {
            Path normalized = normalize(path);
            if (normalized.startsWith(root)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static Set<Path> immutablePaths(Collection<Path> paths) {
        Set<Path> result = new LinkedHashSet<>();
        for (Path path : paths) {
            result.add(normalize(path));
        }
        return Collections.unmodifiableSet(result);
    }

    private String relativePath(Path file) {
        return file.startsWith(root) ? root.relativize(file).toString().replace('\\', '/') : file.toString();
    }

    private static Path normalize(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException ignored) {
            return normalized;
        }
    }

    private static String locationKey(Location location) {
        Range range = location.getRange();
        return location.getUri() + ":" + range.getStart().getLine() + ":" + range.getStart().getCharacter();
    }

    private static <T> void copyAffectedLists(Map<Path, List<T>> source, Map<Path, List<T>> target,
                                              Set<Path> affected) {
        for (Map.Entry<Path, List<T>> entry : source.entrySet()) {
            if (affected.contains(entry.getKey())) {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static Map<String, BindingView> bindingViewsByDefinition(
            Map<Path, List<Occurrence>> occurrenceMap, Set<String> selectedDefinitions) {
        Map<String, BindingView> result = new LinkedHashMap<>();
        boolean selectAll = selectedDefinitions == null;
        for (List<Occurrence> fileOccurrences : occurrenceMap.values()) {
            for (Occurrence occurrence : fileOccurrences) {
                for (BindingView binding : occurrence.bindings) {
                    if (binding.definition != null) {
                        String key = locationKey(binding.definition);
                        if (!selectAll && !selectedDefinitions.contains(key)) {
                            continue;
                        }
                        BindingView previous = result.get(key);
                        result.put(key, previous == null ? binding : previous.unionReferences(binding));
                    }
                }
            }
        }
        return result;
    }

    private static List<Occurrence> reconcileOccurrences(List<Occurrence> input,
                                                         Map<String, BindingView> counterparts,
                                                         Set<String> affectedUris,
                                                         boolean preferInputFields) {
        List<Occurrence> result = new ArrayList<>();
        for (Occurrence occurrence : input) {
            List<BindingView> bindings = null;
            int bindingIndex = 0;
            for (BindingView binding : occurrence.bindings) {
                BindingView counterpart = binding.definition == null
                        ? null : counterparts.get(locationKey(binding.definition));
                boolean needsReconciliation = counterpart != null || binding.hasReferenceIn(affectedUris);
                if (needsReconciliation) {
                    if (bindings == null) {
                        bindings = new ArrayList<>(occurrence.bindings.subList(0, bindingIndex));
                    }
                    bindings.add(binding.reconcileReferences(counterpart, affectedUris, preferInputFields));
                } else if (bindings != null) {
                    bindings.add(binding);
                }
                bindingIndex++;
            }
            result.add(bindings == null ? occurrence : new Occurrence(occurrence.start, occurrence.end,
                    Collections.unmodifiableList(bindings)));
        }
        return result;
    }

    private static <T> Map<Path, List<T>> immutableMapOfLists(Map<Path, List<T>> input) {
        Map<Path, List<T>> copy = new LinkedHashMap<>();
        for (Map.Entry<Path, List<T>> entry : input.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<Path, String> immutableSources(Map<Path, String> input) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    private static final class MutableOccurrence {
        final int start;
        final int end;
        final Set<Binding> bindings = new LinkedHashSet<>();

        MutableOccurrence(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class Occurrence {
        final int start;
        final int end;
        final List<BindingView> bindings;

        Occurrence(int start, int end, List<BindingView> bindings) {
            this.start = start;
            this.end = end;
            this.bindings = bindings;
        }

        static Occurrence freeze(MutableOccurrence mutable, Map<Path, String> sources,
                                 Map<Path, PositionCodec.LineIndex> positionIndexes,
                                 Map<Binding, BindingView> bindingViews) {
            List<BindingView> bindings = new ArrayList<>();
            for (Binding binding : mutable.bindings) {
                bindings.add(bindingViews.computeIfAbsent(
                        binding, key -> BindingView.from(key, sources, positionIndexes)));
            }
            return new Occurrence(mutable.start, mutable.end, Collections.unmodifiableList(bindings));
        }

        int length() {
            return Math.max(1, end - start);
        }
    }

    private static final class BindingView {
        final String type;
        final String documentation;
        final Location definition;
        final List<Location> references;

        BindingView(String type, String documentation, Location definition, List<Location> references) {
            this.type = type;
            this.documentation = documentation;
            this.definition = definition;
            this.references = references;
        }

        static BindingView from(Binding binding, Map<Path, String> sources,
                                Map<Path, PositionCodec.LineIndex> positionIndexes) {
            String type = binding.type == null ? "" : binding.type.toString();
            org.yinwang.pysonar.ast.Str docstring = binding.getDocstring();
            String documentation = docstring == null ? null : docstring.value;
            Location definition = location(
                    binding.getFile(), binding.start, binding.end, sources, positionIndexes);
            List<Location> references = new ArrayList<>();
            for (Node reference : binding.refs) {
                Location location = location(
                        reference.file, reference.start, reference.end, sources, positionIndexes);
                if (location != null) {
                    references.add(location);
                }
            }
            return new BindingView(type, documentation, definition, Collections.unmodifiableList(references));
        }

        BindingView unionReferences(BindingView other) {
            LinkedHashMap<String, Location> merged = new LinkedHashMap<>();
            for (Location reference : references) {
                merged.put(locationKey(reference), reference);
            }
            for (Location reference : other.references) {
                merged.put(locationKey(reference), reference);
            }
            return new BindingView(type, documentation, definition,
                    Collections.unmodifiableList(new ArrayList<>(merged.values())));
        }

        BindingView reconcileReferences(BindingView counterpart, Set<String> affectedUris,
                                        boolean preferThisFields) {
            LinkedHashMap<String, Location> merged = new LinkedHashMap<>();
            BindingView oldSide = preferThisFields ? counterpart : this;
            BindingView newSide = preferThisFields ? this : counterpart;
            if (oldSide != null) {
                for (Location reference : oldSide.references) {
                    if (!affectedUris.contains(reference.getUri())) {
                        merged.put(locationKey(reference), reference);
                    }
                }
            }
            if (newSide != null) {
                for (Location reference : newSide.references) {
                    if (affectedUris.contains(reference.getUri()) || oldSide == null) {
                        merged.put(locationKey(reference), reference);
                    }
                }
            }
            boolean definitionAffected = definition != null && affectedUris.contains(definition.getUri());
            BindingView fields;
            if (definitionAffected) {
                fields = newSide == null ? this : newSide;
            } else {
                fields = oldSide == null ? this : oldSide;
            }
            return new BindingView(fields.type, fields.documentation, fields.definition,
                    Collections.unmodifiableList(new ArrayList<>(merged.values())));
        }

        boolean hasReferenceIn(Set<String> uris) {
            for (Location reference : references) {
                if (uris.contains(reference.getUri())) {
                    return true;
                }
            }
            return false;
        }

        private static Location location(String filename, int start, int end, Map<Path, String> sources,
                                         Map<Path, PositionCodec.LineIndex> positionIndexes) {
            Path file = pathOf(filename);
            if (file == null) {
                return null;
            }
            if (!sources.containsKey(file)) {
                return null;
            }
            PositionCodec.LineIndex positionIndex = positionIndexes.get(file);
            return positionIndex == null
                    ? null
                    : new Location(file.toUri().toString(), range(positionIndex, start, end));
        }
    }
}
