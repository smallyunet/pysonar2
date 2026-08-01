package org.yinwang.pysonar.lsp;

import org.yinwang.pysonar.Analyzer;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    private ScheduledFuture<?> scheduled;

    public AnalysisSession(Path root, Collection<String> excludeGlobs) {
        this(root, excludeGlobs, progress -> { });
    }

    public AnalysisSession(Path root, Collection<String> excludeGlobs,
                           Consumer<AnalysisProgress> progressListener) {
        this.root = realPath(root);
        this.excludeGlobs = Collections.unmodifiableList(new ArrayList<>(excludeGlobs));
        this.progressListener = progressListener == null ? progress -> { } : progressListener;
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
        ProgressReporter progress = new ProgressReporter();
        progress.report("discovering", 0, 0, root.toString(), true);
        List<String> files = collectPythonFiles(progress);
        progress.report("analyzing", 0, files.size(), "", true);
        HashMap<String, Object> options = new HashMap<>();
        options.put("quiet", true);
        Analyzer analyzer = new Analyzer(options);
        boolean finished = false;
        try {
            analyzer.addPath(root.toString());
            int[] current = {0};
            analyzer.analyzeFiles(root.toString(), files, file -> {
                current[0]++;
                progress.report("analyzing", current[0], files.size(), relativePath(file),
                        current[0] == 1 || current[0] == files.size());
            });
            progress.report("finalizing", files.size(), files.size(), "Resolving inferred types and references", true);
            analyzer.finish();
            finished = true;
            progress.report("snapshot", files.size(), files.size(), "Building editor snapshot", true);
            AnalysisSnapshot next = AnalysisSnapshot.from(root, analyzer);
            snapshot.set(next);
            return next;
        } finally {
            if (!finished) {
                analyzer.close();
            }
            analyzer.releaseGlobalReference();
        }
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
