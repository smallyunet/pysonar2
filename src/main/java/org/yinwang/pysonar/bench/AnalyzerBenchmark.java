package org.yinwang.pysonar.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.yinwang.pysonar.lsp.AnalysisSession;
import org.yinwang.pysonar.lsp.RebuildMetrics;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Reproducible full, persistent-cache, and no-change analyzer benchmark. */
public final class AnalyzerBenchmark {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> EXCLUDED_DIRECTORIES = Arrays.asList(
            ".git", ".venv", "venv", "node_modules", "target", "build", "dist", "__pycache__");

    private AnalyzerBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        Path root = arguments.root.toRealPath();
        Corpus corpus = Corpus.inspect(root);
        Path cacheBase = arguments.cacheDirectory == null
                ? Path.of(System.getProperty("java.io.tmpdir"), "pysonar2-benchmark-cache")
                : arguments.cacheDirectory.toAbsolutePath().normalize();
        Path runCache = cacheBase.resolve("run-" + UUID.randomUUID());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("generatedAt", Instant.now().toString());
        result.put("root", root.toString());
        result.put("corpus", corpus.toMap());
        result.put("runtime", runtimeMetadata());
        result.put("implementation", implementationMetadata());
        result.put("method", method(arguments, runCache));

        List<Map<String, Object>> cold = new ArrayList<>();
        List<Map<String, Object>> warmFull = new ArrayList<>();
        List<Map<String, Object>> noChange = new ArrayList<>();
        List<Map<String, Object>> incrementalChange = new ArrayList<>();

        try (AnalysisSession session = session(root, runCache)) {
            cold.add(measure(session));
        }
        for (int index = 0; index < arguments.warmups; index++) {
            try (AnalysisSession session = session(root, runCache)) {
                session.rebuildNow();
            }
        }
        for (int index = 0; index < arguments.iterations; index++) {
            try (AnalysisSession session = session(root, runCache)) {
                warmFull.add(measure(session));
                noChange.add(measure(session));
            }
        }
        if (arguments.changeFile != null) {
            Path copiedRoot = runCache.resolve("incremental-workspace");
            copyCorpus(root, copiedRoot);
            Path changedFile = copiedRoot.resolve(arguments.changeFile).normalize();
            if (!changedFile.startsWith(copiedRoot) || !Files.isRegularFile(changedFile)
                    || !changedFile.getFileName().toString().endsWith(".py")) {
                throw new IllegalArgumentException("--change-file must name a Python file under the corpus root: "
                        + arguments.changeFile);
            }
            byte[] original = Files.readAllBytes(changedFile);
            try (AnalysisSession session = session(copiedRoot, runCache.resolve("incremental-cache"))) {
                session.rebuildNow();
                for (int index = 0; index < arguments.iterations; index++) {
                    String marker = "\n# pysonar2 controlled benchmark mutation " + index + "\n";
                    byte[] suffix = marker.getBytes(StandardCharsets.UTF_8);
                    byte[] mutated = Arrays.copyOf(original, original.length + suffix.length);
                    System.arraycopy(suffix, 0, mutated, original.length, suffix.length);
                    Files.write(changedFile, mutated);
                    incrementalChange.add(measure(session));
                }
            }
        }

        Map<String, Object> samples = new LinkedHashMap<>();
        samples.put("coldFull", cold);
        samples.put("persistentCacheFull", warmFull);
        samples.put("noChange", noChange);
        if (!incrementalChange.isEmpty()) {
            samples.put("incrementalChange", incrementalChange);
        }
        result.put("samples", samples);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("coldFull", summarize(cold));
        summary.put("persistentCacheFull", summarize(warmFull));
        summary.put("noChange", summarize(noChange));
        if (!incrementalChange.isEmpty()) {
            summary.put("incrementalChange", summarize(incrementalChange));
        }
        result.put("summary", summary);
        System.out.println(GSON.toJson(result));
    }

    private static AnalysisSession session(Path root, Path cache) {
        return new AnalysisSession(root, Collections.emptyList(), progress -> { }, cache);
    }

    private static Map<String, Object> measure(AnalysisSession session) throws Exception {
        Runtime runtime = Runtime.getRuntime();
        forceGc();
        long wallStarted = System.nanoTime();
        long cpuStarted = processCpuNanos();
        long allocatedStarted = allocatedBytes();
        long heapStarted = runtime.totalMemory() - runtime.freeMemory();
        long[] gcStarted = gcCounters();

        session.rebuildNow();

        long wallNanos = System.nanoTime() - wallStarted;
        long cpuNanos = nonNegativeDelta(processCpuNanos(), cpuStarted);
        long allocatedBytes = nonNegativeDelta(allocatedBytes(), allocatedStarted);
        long heapAfter = runtime.totalMemory() - runtime.freeMemory();
        long[] gcAfter = gcCounters();
        RebuildMetrics metrics = session.lastMetrics();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", metrics.getMode().name().toLowerCase(Locale.ROOT));
        result.put("wallMillis", nanosToMillis(wallNanos));
        result.put("jvmProcessCpuMillis", nanosToMillis(cpuNanos));
        result.put("currentThreadAllocatedBytes", allocatedBytes);
        result.put("heapUsedBeforeBytes", heapStarted);
        result.put("heapUsedAfterBytes", heapAfter);
        result.put("gcCollections", nonNegativeDelta(gcAfter[0], gcStarted[0]));
        result.put("gcMillis", nonNegativeDelta(gcAfter[1], gcStarted[1]));
        result.put("workspaceFiles", metrics.getWorkspaceFiles());
        result.put("changedFiles", metrics.getChangedFiles());
        result.put("affectedFiles", metrics.getAffectedFiles());
        result.put("analyzedFiles", metrics.getAnalyzedFiles());
        result.put("astCacheHits", metrics.getAstCacheHits());
        result.put("astCacheMisses", metrics.getAstCacheMisses());
        result.put("reason", metrics.getReason());
        return result;
    }

    private static Map<String, Object> summarize(List<Map<String, Object>> samples) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleCount", samples.size());
        result.put("wallMillisMedian", percentile(samples, "wallMillis", 0.50));
        result.put("wallMillisP95", percentile(samples, "wallMillis", 0.95));
        result.put("jvmProcessCpuMillisMedian", percentile(samples, "jvmProcessCpuMillis", 0.50));
        result.put("currentThreadAllocatedBytesMedian",
                percentile(samples, "currentThreadAllocatedBytes", 0.50));
        return result;
    }

    private static long percentile(List<Map<String, Object>> samples, String key, double percentile) {
        if (samples.isEmpty()) {
            return 0;
        }
        List<Long> values = new ArrayList<>();
        for (Map<String, Object> sample : samples) {
            values.add(((Number) sample.get(key)).longValue());
        }
        Collections.sort(values);
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(values.size() - 1, index)));
    }

    private static Map<String, Object> runtimeMetadata() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVm", System.getProperty("java.vm.name"));
        result.put("os", System.getProperty("os.name"));
        result.put("osVersion", System.getProperty("os.version"));
        result.put("architecture", System.getProperty("os.arch"));
        result.put("processors", runtime.availableProcessors());
        result.put("maxHeapBytes", runtime.maxMemory());
        result.put("pythonCommand", System.getenv().getOrDefault("PYSONAR_PYTHON", "python3"));
        result.put("pythonVersion", commandOutput(
                System.getenv().getOrDefault("PYSONAR_PYTHON", "python3"), "--version"));
        result.put("cpuScope", "JVM process only; CPython parser child CPU is excluded");
        result.put("allocationScope", "benchmark thread Java allocations only");
        return result;
    }

    private static Map<String, Object> implementationMetadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gitCommit", commandOutput("git", "rev-parse", "HEAD"));
        result.put("gitDirty", !commandOutput("git", "status", "--porcelain").isEmpty());
        try {
            Path source = Path.of(AnalyzerBenchmark.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            result.put("codeSource", source.toString());
            if (Files.isRegularFile(source)) {
                result.put("codeSourceSha256", Corpus.hex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
            }
        } catch (Exception error) {
            result.put("codeSourceError", error.getMessage());
        }
        return result;
    }

    private static String commandOutput(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (InputStream input = process.getInputStream()) {
                String output = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
                return process.waitFor() == 0 ? output : "unavailable: " + output;
            }
        } catch (Exception error) {
            return "unavailable: " + error.getMessage();
        }
    }

    private static Map<String, Object> method(Arguments arguments, Path runCache) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("warmups", arguments.warmups);
        result.put("iterations", arguments.iterations);
        result.put("cacheDirectory", runCache.toString());
        result.put("coldFull", "first fresh AnalysisSession with an empty unique cache namespace");
        result.put("persistentCacheFull", "new AnalysisSession instances reusing serialized ASTs");
        result.put("noChange", "second rebuild in the same session with unchanged content hashes");
        if (arguments.changeFile != null) {
            result.put("incrementalChangeFile", arguments.changeFile.toString());
            result.put("incrementalChange", "controlled comment-only edits in a benchmark-owned corpus copy");
        }
        result.put("notes", Arrays.asList(
                "Each sample reports raw counters; summaries are derived from those samples.",
                "Run on an otherwise idle machine and compare identical corpus digests.",
                "Use the JFR profile command for sampled CPU stacks and allocation hot spots."));
        return result;
    }

    private static void copyCorpus(Path sourceRoot, Path targetRoot) throws IOException {
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                if (!directory.equals(sourceRoot)
                        && EXCLUDED_DIRECTORIES.contains(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(targetRoot.resolve(sourceRoot.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Path target = targetRoot.resolve(sourceRoot.relativize(file));
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void forceGc() throws InterruptedException {
        System.gc();
        Thread.sleep(50);
    }

    private static long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) bean).getProcessCpuTime();
        }
        return -1;
    }

    private static long allocatedBytes() {
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean) {
            com.sun.management.ThreadMXBean allocationBean = (com.sun.management.ThreadMXBean) bean;
            if (allocationBean.isThreadAllocatedMemorySupported()) {
                if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
                    allocationBean.setThreadAllocatedMemoryEnabled(true);
                }
                return allocationBean.getThreadAllocatedBytes(Thread.currentThread().getId());
            }
        }
        return -1;
    }

    private static long[] gcCounters() {
        long collections = 0;
        long millis = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            collections += Math.max(0, bean.getCollectionCount());
            millis += Math.max(0, bean.getCollectionTime());
        }
        return new long[]{collections, millis};
    }

    private static long nonNegativeDelta(long after, long before) {
        if (after < 0 || before < 0) {
            return -1;
        }
        return Math.max(0, after - before);
    }

    private static long nanosToMillis(long nanos) {
        return nanos < 0 ? -1 : nanos / 1_000_000L;
    }

    private static final class Arguments {
        final Path root;
        final int warmups;
        final int iterations;
        final Path cacheDirectory;
        final Path changeFile;

        Arguments(Path root, int warmups, int iterations, Path cacheDirectory, Path changeFile) {
            this.root = root;
            this.warmups = warmups;
            this.iterations = iterations;
            this.cacheDirectory = cacheDirectory;
            this.changeFile = changeFile;
        }

        static Arguments parse(String[] args) {
            Path root = Path.of(".");
            int warmups = 1;
            int iterations = 5;
            Path cache = null;
            Path changeFile = null;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--root".equals(argument)) {
                    root = Path.of(value(args, ++index, argument));
                } else if ("--warmups".equals(argument)) {
                    warmups = nonNegativeInt(value(args, ++index, argument), argument);
                } else if ("--iterations".equals(argument)) {
                    iterations = positiveInt(value(args, ++index, argument), argument);
                } else if ("--cache-dir".equals(argument)) {
                    cache = Path.of(value(args, ++index, argument));
                } else if ("--change-file".equals(argument)) {
                    changeFile = Path.of(value(args, ++index, argument));
                } else if ("--help".equals(argument) || "-h".equals(argument)) {
                    System.out.println("Usage: AnalyzerBenchmark [--root PATH] [--warmups N] "
                            + "[--iterations N] [--cache-dir PATH] [--change-file RELATIVE_PYTHON_PATH]");
                    System.exit(0);
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + argument);
                }
            }
            return new Arguments(root, warmups, iterations, cache, changeFile);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static int nonNegativeInt(String value, String option) {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(option + " must be non-negative");
            }
            return parsed;
        }

        private static int positiveInt(String value, String option) {
            int parsed = nonNegativeInt(value, option);
            if (parsed == 0) {
                throw new IllegalArgumentException(option + " must be positive");
            }
            return parsed;
        }
    }

    private static final class Corpus {
        final int fileCount;
        final long sourceBytes;
        final String digest;

        Corpus(int fileCount, long sourceBytes, String digest) {
            this.fileCount = fileCount;
            this.sourceBytes = sourceBytes;
            this.digest = digest;
        }

        static Corpus inspect(Path root) throws Exception {
            List<Path> files = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    if (!directory.equals(root)
                            && EXCLUDED_DIRECTORIES.contains(directory.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".py")) {
                        files.add(file.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            files.sort(Comparator.naturalOrder());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long bytes = 0;
            for (Path file : files) {
                byte[] content = Files.readAllBytes(file);
                bytes += content.length;
                digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(content);
                digest.update((byte) 0);
            }
            return new Corpus(files.size(), bytes, hex(digest.digest()));
        }

        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pythonFiles", fileCount);
            result.put("sourceBytes", sourceBytes);
            result.put("sha256", digest);
            return result;
        }

        static String hex(byte[] bytes) {
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        }
    }
}
