package org.yinwang.pysonar.lsp;

import org.yinwang.pysonar.Analyzer;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Owns serialized rebuilds and atomically publishes completed analysis snapshots. */
public final class AnalysisSession implements AutoCloseable {

    private static final long REBUILD_DEBOUNCE_MILLIS = 600;
    private static final long PROGRESS_THROTTLE_MILLIS = 250;
    private static final Set<String> DEFAULT_EXCLUDED_DIRECTORIES = new HashSet<>(Arrays.asList(
            ".git", ".hg", ".svn", ".idea", ".vscode", ".venv", "venv", "env",
            "node_modules", "target", "build", "dist", "__pycache__", ".tox", ".mypy_cache",
            ".pytest_cache", ".ruff_cache"));

    private final Path root;
    private final List<String> excludeGlobs;
    private final AtomicReference<AnalysisSnapshot> snapshot;
    private final ScheduledExecutorService executor;
    private final Consumer<AnalysisProgress> progressListener;
    private final DiagnosticPolicy diagnosticPolicy;
    private final Path astCacheDirectory;
    private Map<Path, String> fileHashes = Collections.emptyMap();
    private ImportGraph importGraph;
    private boolean initialized;
    private volatile RebuildMetrics lastMetrics = RebuildMetrics.empty();
    private ScheduledFuture<?> scheduled;

    public AnalysisSession(Path root, Collection<String> excludeGlobs) {
        this(root, excludeGlobs, DiagnosticPolicy.conservative(), progress -> { });
    }

    public AnalysisSession(Path root, Collection<String> excludeGlobs,
                           Consumer<AnalysisProgress> progressListener) {
        this(root, excludeGlobs, DiagnosticPolicy.conservative(), progressListener);
    }

    public AnalysisSession(Path root, Collection<String> excludeGlobs,
                           Consumer<AnalysisProgress> progressListener, Path astCacheDirectory) {
        this(root, excludeGlobs, DiagnosticPolicy.conservative(), progressListener, astCacheDirectory);
    }

    AnalysisSession(Path root, Collection<String> excludeGlobs, DiagnosticPolicy diagnosticPolicy,
                    Consumer<AnalysisProgress> progressListener) {
        this(root, excludeGlobs, diagnosticPolicy, progressListener, null);
    }

    AnalysisSession(Path root, Collection<String> excludeGlobs, DiagnosticPolicy diagnosticPolicy,
                    Consumer<AnalysisProgress> progressListener, Path astCacheDirectory) {
        this.root = realPath(root);
        this.excludeGlobs = Collections.unmodifiableList(new ArrayList<>(excludeGlobs));
        this.diagnosticPolicy = diagnosticPolicy == null ? DiagnosticPolicy.conservative() : diagnosticPolicy;
        this.progressListener = progressListener == null ? progress -> { } : progressListener;
        this.astCacheDirectory = astCacheDirectory == null
                ? defaultAstCacheDirectory(this.root) : astCacheDirectory.toAbsolutePath().normalize();
        this.snapshot = new AtomicReference<>(AnalysisSnapshot.empty(root));
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pysonar2-analysis");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Path getRoot() {
        return root;
    }

    public AnalysisSnapshot current() {
        return snapshot.get();
    }

    public RebuildMetrics lastMetrics() {
        return lastMetrics;
    }

    public synchronized CompletableFuture<AnalysisSnapshot> scheduleRebuild() {
        if (scheduled != null && !scheduled.isDone()) {
            scheduled.cancel(false);
        }
        CompletableFuture<AnalysisSnapshot> result = new CompletableFuture<>();
        scheduled = executor.schedule(() -> {
            try {
                result.complete(rebuildNow());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        }, REBUILD_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        return result;
    }

    public AnalysisSnapshot rebuildNow() throws IOException {
        long rebuildStarted = System.nanoTime();
        ProgressReporter progress = new ProgressReporter();
        progress.report("discovering", 0, 0, root.toString(), true);
        List<String> files = collectPythonFiles(progress);
        List<Path> workspaceFiles = new ArrayList<>();
        for (String file : files) {
            workspaceFiles.add(Path.of(file).toAbsolutePath().normalize());
        }
        WorkspaceContent workspaceContent = readWorkspaceFiles(workspaceFiles);
        Map<Path, String> nextHashes = workspaceContent.hashes;
        ImportGraph nextGraph = ImportGraph.build(root, workspaceContent.sources);
        Set<Path> changed = changedFiles(fileHashes, nextHashes);

        if (initialized && changed.isEmpty()) {
            fileHashes = nextHashes;
            importGraph = nextGraph;
            lastMetrics = new RebuildMetrics(RebuildMetrics.Mode.NO_CHANGE, workspaceFiles.size(),
                    0, 0, 0, 0, 0, elapsedMillis(rebuildStarted), "content hashes unchanged");
            progress.report("up-to-date", workspaceFiles.size(), workspaceFiles.size(),
                    "Workspace hashes are unchanged", true);
            return snapshot.get();
        }

        boolean full = !initialized;
        String reason = full ? "initial analysis" : "changed files and reverse import dependencies";
        Set<Path> affected = new LinkedHashSet<>();
        if (!full && (importGraph == null || !importGraph.isComplete() || !nextGraph.isComplete())) {
            full = true;
            reason = "unsupported continued import syntax";
        }
        if (!full) {
            affected.addAll(importGraph.affectedBy(changed));
            affected.addAll(nextGraph.affectedBy(changed));
            if (workspaceFiles.size() >= 20
                    && affected.size() * 100L >= workspaceFiles.size() * 60L) {
                full = true;
                reason = "affected closure covers at least 60% of the workspace";
            }
        }
        if (full) {
            affected.clear();
            affected.addAll(workspaceFiles);
            affected.addAll(changed);
        }

        List<String> analysisFiles = new ArrayList<>();
        for (Path file : workspaceFiles) {
            if (full || affected.contains(file)) {
                analysisFiles.add(file.toString());
            }
        }
        progress.report("analyzing", 0, analysisFiles.size(), "", true);
        AnalysisRun run = analyze(files, analysisFiles, progress);
        AnalysisSnapshot next = full
                ? run.snapshot
                : snapshot.get().replaceFiles(run.snapshot, affected);
        snapshot.set(next);
        initialized = true;
        fileHashes = nextHashes;
        importGraph = nextGraph;
        lastMetrics = new RebuildMetrics(full ? RebuildMetrics.Mode.FULL : RebuildMetrics.Mode.INCREMENTAL,
                workspaceFiles.size(), changed.size(), affected.size(), run.analyzedFiles,
                run.astCacheHits, run.astCacheMisses, elapsedMillis(rebuildStarted), reason);
        return next;
    }

    private AnalysisRun analyze(List<String> workspaceFiles, List<String> analysisFiles,
                                ProgressReporter progress) throws IOException {
        HashMap<String, Object> options = new HashMap<>();
        options.put("quiet", true);
        options.put("astCacheDir", astCacheDirectory.toString());
        Analyzer analyzer = new Analyzer(options);
        boolean finished = false;
        try {
            analyzer.addPath(root.toString());
            for (Path analysisPath : discoverAnalysisPaths(workspaceFiles)) {
                analyzer.addPath(analysisPath.toString());
            }
            int[] current = {0};
            analyzer.analyzeFiles(root.toString(), analysisFiles, file -> {
                current[0]++;
                progress.report("analyzing", current[0], analysisFiles.size(), relativePath(file),
                        current[0] == 1 || current[0] == analysisFiles.size());
            });
            progress.report("finalizing", analysisFiles.size(), analysisFiles.size(),
                    "Resolving inferred types and references", true);
            analyzer.finish();
            finished = true;
            progress.report("snapshot", analysisFiles.size(), analysisFiles.size(),
                    "Building editor snapshot", true);
            List<Path> discoveredFiles = new ArrayList<>();
            for (String workspaceFile : workspaceFiles) {
                discoveredFiles.add(Path.of(workspaceFile).toAbsolutePath().normalize());
            }
            AnalysisSnapshot next = AnalysisSnapshot.from(root, analyzer, diagnosticPolicy, discoveredFiles);
            return new AnalysisRun(next, analyzer.getLoadedFiles().size(),
                    analyzer.getStat("astCacheHits"), analyzer.getStat("astCacheMisses"));
        } finally {
            if (!finished) {
                analyzer.close();
            }
            analyzer.releaseGlobalReference();
        }
    }

    private static WorkspaceContent readWorkspaceFiles(Collection<Path> files) throws IOException {
        Map<Path, String> hashes = new LinkedHashMap<>();
        Map<Path, String> sources = new LinkedHashMap<>();
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            hashes.put(file, sha256(bytes));
            sources.put(file, new String(bytes, StandardCharsets.UTF_8));
        }
        return new WorkspaceContent(Collections.unmodifiableMap(hashes),
                Collections.unmodifiableMap(sources));
    }

    private static Set<Path> changedFiles(Map<Path, String> previous, Map<Path, String> current) {
        Set<Path> changed = new LinkedHashSet<>();
        Set<Path> all = new LinkedHashSet<>(previous.keySet());
        all.addAll(current.keySet());
        for (Path file : all) {
            if (!java.util.Objects.equals(previous.get(file), current.get(file))) {
                changed.add(file);
            }
        }
        return changed;
    }

    private static Path defaultAstCacheDirectory(Path root) {
        String configured = System.getenv("PYSONAR_CACHE_DIR");
        Path base = configured == null || configured.trim().isEmpty()
                ? Path.of(System.getProperty("java.io.tmpdir"), "pysonar2-ast-cache")
                : Path.of(configured);
        String interpreter = System.getenv().getOrDefault("PYSONAR_PYTHON", "python3");
        String namespace = sha256((root.toString() + "\n" + interpreter + "\nast-v1")
                .getBytes(StandardCharsets.UTF_8)).substring(0, 24);
        return base.toAbsolutePath().normalize().resolve("ast-v1").resolve(namespace);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static final class AnalysisRun {
        final AnalysisSnapshot snapshot;
        final int analyzedFiles;
        final long astCacheHits;
        final long astCacheMisses;

        AnalysisRun(AnalysisSnapshot snapshot, int analyzedFiles, long astCacheHits, long astCacheMisses) {
            this.snapshot = snapshot;
            this.analyzedFiles = analyzedFiles;
            this.astCacheHits = astCacheHits;
            this.astCacheMisses = astCacheMisses;
        }
    }

    private static final class WorkspaceContent {
        final Map<Path, String> hashes;
        final Map<Path, String> sources;

        WorkspaceContent(Map<Path, String> hashes, Map<Path, String> sources) {
            this.hashes = hashes;
            this.sources = sources;
        }
    }

    private Set<Path> discoverAnalysisPaths(List<String> files) {
        Set<Path> paths = new HashSet<>();
        for (String filename : files) {
            Path directory = Path.of(filename).toAbsolutePath().normalize().getParent();
            Path packageDirectory = directory;
            boolean inPackage = false;
            while (packageDirectory != null && packageDirectory.startsWith(root)
                    && Files.isRegularFile(packageDirectory.resolve("__init__.py"))) {
                inPackage = true;
                packageDirectory = packageDirectory.getParent();
            }
            if (inPackage && packageDirectory != null && packageDirectory.startsWith(root)) {
                paths.add(packageDirectory);
            }

            Path project = directory;
            while (project != null && project.startsWith(root)) {
                if (Files.isRegularFile(project.resolve("pyproject.toml"))
                        || Files.isRegularFile(project.resolve("setup.py"))
                        || Files.isRegularFile(project.resolve("setup.cfg"))) {
                    paths.add(project);
                    Path src = project.resolve("src");
                    if (Files.isDirectory(src)) {
                        paths.add(src);
                    }
                    break;
                }
                if (project.equals(root)) {
                    break;
                }
                project = project.getParent();
            }
        }
        paths.remove(root);
        return paths;
    }

    private List<String> collectPythonFiles(ProgressReporter progress) throws IOException {
        List<String> files = new ArrayList<>();
        List<PathMatcher> matchers = new ArrayList<>();
        for (String glob : excludeGlobs) {
            if (glob != null && !glob.trim().isEmpty()) {
                matchers.add(root.getFileSystem().getPathMatcher("glob:" + glob.trim()));
            }
        }

        Files.walkFileTree(root, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                if (!directory.equals(root) && excluded(directory, matchers)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                progress.report("discovering", files.size(), 0, relativePath(directory), false);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".py")
                        && !excluded(file, matchers)) {
                    files.add(file.toAbsolutePath().normalize().toString());
                    progress.report("discovering", files.size(), 0, relativePath(file), false);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) {
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(files);
        progress.report("discovering", files.size(), files.size(), "Found " + files.size() + " Python files", true);
        return files;
    }

    private String relativePath(String path) {
        return relativePath(Path.of(path));
    }

    private String relativePath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            String relative = root.relativize(normalized).toString();
            return relative.isEmpty() ? "." : relative;
        } catch (IllegalArgumentException ignored) {
            return normalized.toString();
        }
    }

    private boolean excluded(Path path, List<PathMatcher> matchers) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        for (Path part : relative) {
            if (DEFAULT_EXCLUDED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relative)) {
                return true;
            }
        }
        return false;
    }

    private static Path realPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException ignored) {
            return normalized;
        }
    }

    private final class ProgressReporter {
        private final long startedAt = System.currentTimeMillis();
        private long lastReportAt;
        private String lastPhase = "";

        void report(String phase, int current, int total, String path, boolean force) {
            long now = System.currentTimeMillis();
            if (!force && phase.equals(lastPhase) && now - lastReportAt < PROGRESS_THROTTLE_MILLIS) {
                return;
            }
            lastPhase = phase;
            lastReportAt = now;
            progressListener.accept(new AnalysisProgress(
                    phase, current, total, path, Math.max(0, now - startedAt)));
        }
    }

    @Override
    public synchronized void close() {
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        executor.shutdownNow();
    }
}
