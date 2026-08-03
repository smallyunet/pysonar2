package org.yinwang.pysonar.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class PySonarLanguageServer implements LanguageServer, LanguageClientAware {

    private final PySonarTextDocumentService textDocumentService = new PySonarTextDocumentService(this);
    private final PySonarWorkspaceService workspaceService = new PySonarWorkspaceService(this);
    private final Set<String> publishedDiagnosticUris = new LinkedHashSet<>();
    private volatile PySonarLanguageClient client;
    private volatile AnalysisSession session;
    private volatile boolean shutdownRequested;
    private volatile String lastProgressMessage = "workspace discovery";

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        Path root = workspaceRoot(params);
        Object options = params.getInitializationOptions();
        session = new AnalysisSession(root, excludeGlobs(options), diagnosticPolicy(options), this::reportProgress);

        ServerCapabilities capabilities = new ServerCapabilities();
        TextDocumentSyncOptions sync = new TextDocumentSyncOptions();
        sync.setOpenClose(true);
        sync.setChange(TextDocumentSyncKind.Full);
        sync.setSave(true);
        capabilities.setTextDocumentSync(sync);
        capabilities.setDefinitionProvider(true);
        capabilities.setReferencesProvider(true);
        capabilities.setHoverProvider(true);
        capabilities.setDocumentSymbolProvider(true);
        capabilities.setWorkspaceSymbolProvider(true);

        InitializeResult result = new InitializeResult(capabilities);
        result.setServerInfo(new ServerInfo("PySonar2 Language Server", "3.3.0"));
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void initialized(InitializedParams params) {
        scheduleRebuild("Initial workspace analysis");
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        shutdownRequested = true;
        if (session != null) {
            session.close();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        if (session != null) {
            session.close();
        }
        System.exit(shutdownRequested ? 0 : 1);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(org.eclipse.lsp4j.services.LanguageClient client) {
        this.client = (PySonarLanguageClient) client;
    }

    AnalysisSnapshot snapshot() {
        AnalysisSession current = session;
        return current == null ? AnalysisSnapshot.empty(Path.of(".")) : current.current();
    }

    void markStale() {
        status("stale", "Unsaved changes; navigation uses the last saved snapshot");
    }

    void scheduleRebuild(String reason) {
        AnalysisSession current = session;
        if (current == null) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        lastProgressMessage = reason;
        status("indexing", reason);
        current.scheduleRebuild().whenComplete((snapshot, error) -> {
            if (error != null) {
                String message = analysisErrorMessage(error);
                status("error", message);
                log(MessageType.Error, "PySonar2 analysis failed: " + message);
                return;
            }
            publishDiagnostics(snapshot);
            String duration = formatDuration(System.currentTimeMillis() - startedAt);
            String summary = "Indexed " + snapshot.fileCount() + " Python files in " + duration
                    + " · " + heapUsage();
            status("ready", summary);
            log(MessageType.Info, "PySonar2 " + summary);
        });
    }

    private synchronized void publishDiagnostics(AnalysisSnapshot snapshot) {
        if (client == null) {
            return;
        }
        Map<String, List<Diagnostic>> next = snapshot.diagnosticsByUri();
        for (String oldUri : new ArrayList<>(publishedDiagnosticUris)) {
            if (!next.containsKey(oldUri)) {
                client.publishDiagnostics(new PublishDiagnosticsParams(oldUri, Collections.emptyList()));
            }
        }
        for (Map.Entry<String, List<Diagnostic>> entry : next.entrySet()) {
            client.publishDiagnostics(new PublishDiagnosticsParams(entry.getKey(), entry.getValue()));
        }
        publishedDiagnosticUris.clear();
        publishedDiagnosticUris.addAll(next.keySet());
    }

    private void status(String state, String message) {
        PySonarLanguageClient current = client;
        if (current != null) {
            current.status(new PySonarStatus(state, message));
        }
    }

    private void reportProgress(AnalysisProgress progress) {
        PySonarLanguageClient current = client;
        if (current == null) {
            return;
        }
        PySonarStatus status = new PySonarStatus("indexing", progressMessage(progress));
        lastProgressMessage = status.getMessage();
        status.setPhase(progress.getPhase());
        status.setCurrent(progress.getCurrent());
        status.setTotal(progress.getTotal());
        status.setPath(progress.getPath());
        status.setElapsedMillis(progress.getElapsedMillis());
        current.status(status);
    }

    private static String progressMessage(AnalysisProgress progress) {
        String elapsed = formatDuration(progress.getElapsedMillis());
        String heap = heapUsage();
        if ("discovering".equals(progress.getPhase())) {
            String count = progress.getCurrent() == 0 ? "" : " (" + progress.getCurrent() + " found)";
            return "Discovering Python files" + count + " · " + progress.getPath()
                    + " · " + elapsed + " · " + heap;
        }
        if ("analyzing".equals(progress.getPhase())) {
            if (progress.getTotal() == 0) {
                return "No Python files found · " + elapsed + " · " + heap;
            }
            int percent = (int) Math.min(100, progress.getCurrent() * 100L / progress.getTotal());
            String path = progress.getPath().isEmpty() ? "Preparing analyzer" : progress.getPath();
            return "Analyzing " + progress.getCurrent() + "/" + progress.getTotal()
                    + " (" + percent + "%) · " + path + " · " + elapsed + " · " + heap;
        }
        return progress.getPath() + " · " + elapsed + " · " + heap;
    }

    private String analysisErrorMessage(Throwable error) {
        String rootCause = rootCauseMessage(error);
        if (rootCause.toLowerCase(java.util.Locale.ROOT).contains("java heap space")) {
            return "Java heap exhausted during " + lastProgressMessage
                    + ". Narrow pysonar2.analysis.exclude or set pysonar2.java.maxHeapMb, then reindex.";
        }
        return rootCause;
    }

    private static String heapUsage() {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMb = runtime.maxMemory() / (1024 * 1024);
        return "heap " + usedMb + "/" + maxMb + " MB";
    }

    private static String formatDuration(long millis) {
        if (millis < 1_000) {
            return millis + "ms";
        }
        long seconds = millis / 1_000;
        if (seconds < 60) {
            return seconds + "s";
        }
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private void log(MessageType type, String message) {
        PySonarLanguageClient current = client;
        if (current != null) {
            current.logMessage(new MessageParams(type, message));
        }
    }

    private static Path workspaceRoot(InitializeParams params) {
        List<WorkspaceFolder> folders = params.getWorkspaceFolders();
        if (folders != null && !folders.isEmpty()) {
            return uriPath(folders.get(0).getUri());
        }
        if (params.getRootUri() != null) {
            return uriPath(params.getRootUri());
        }
        if (params.getRootPath() != null) {
            return Path.of(params.getRootPath()).toAbsolutePath().normalize();
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static Path uriPath(String uri) {
        return Path.of(URI.create(uri)).toAbsolutePath().normalize();
    }

    private static Collection<String> excludeGlobs(Object initializationOptions) {
        if (!(initializationOptions instanceof Map)) {
            return Collections.emptyList();
        }
        Object excludes = ((Map<?, ?>) initializationOptions).get("exclude");
        if (!(excludes instanceof Collection)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (Object exclude : (Collection<?>) excludes) {
            if (exclude != null) {
                result.add(exclude.toString());
            }
        }
        return result;
    }

    private static DiagnosticPolicy diagnosticPolicy(Object initializationOptions) {
        if (!(initializationOptions instanceof Map)) {
            return DiagnosticPolicy.conservative();
        }
        Map<?, ?> options = (Map<?, ?>) initializationOptions;
        return DiagnosticPolicy.from(options.get("diagnosticsMode"), options.get("diagnosticsMaxPerFile"));
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
