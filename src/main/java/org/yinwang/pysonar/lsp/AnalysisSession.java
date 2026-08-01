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

/** Owns serialized rebuilds and atomically publishes completed analysis snapshots. */
public final class AnalysisSession implements AutoCloseable {

    private static final long REBUILD_DEBOUNCE_MILLIS = 600;
    private static final Set<String> DEFAULT_EXCLUDED_DIRECTORIES = new HashSet<>(Arrays.asList(
            ".git", ".hg", ".svn", ".idea", ".vscode", ".venv", "venv", "env",
            "node_modules", "target", "build", "dist", "__pycache__", ".tox", ".mypy_cache",
            ".pytest_cache", ".ruff_cache"));

    private final Path root;
    private final List<String> excludeGlobs;
    private final AtomicReference<AnalysisSnapshot> snapshot;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> scheduled;

    public AnalysisSession(Path root, Collection<String> excludeGlobs) {
        this.root = realPath(root);
        this.excludeGlobs = Collections.unmodifiableList(new ArrayList<>(excludeGlobs));
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
        List<String> files = collectPythonFiles();
        HashMap<String, Object> options = new HashMap<>();
        options.put("quiet", true);
        Analyzer analyzer = new Analyzer(options);
        boolean finished = false;
        try {
            analyzer.addPath(root.toString());
            analyzer.analyzeFiles(root.toString(), files);
            analyzer.finish();
            finished = true;
            AnalysisSnapshot next = AnalysisSnapshot.from(root, analyzer);
            snapshot.set(next);
            return next;
        } finally {
            if (!finished) {
                analyzer.close();
            }
        }
    }

    private List<String> collectPythonFiles() throws IOException {
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
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".py")
                        && !excluded(file, matchers)) {
                    files.add(file.toAbsolutePath().normalize().toString());
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
        return files;
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

    @Override
    public synchronized void close() {
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        executor.shutdownNow();
    }
}
