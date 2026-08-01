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

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        Path root = workspaceRoot(params);
        session = new AnalysisSession(root, excludeGlobs(params.getInitializationOptions()));

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
        result.setServerInfo(new ServerInfo("PySonar2 Language Server", "3.1.0"));
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
        status("indexing", reason);
        current.scheduleRebuild().whenComplete((snapshot, error) -> {
            if (error != null) {
                status("error", rootCauseMessage(error));
                log(MessageType.Error, "PySonar2 analysis failed: " + rootCauseMessage(error));
                return;
            }
            publishDiagnostics(snapshot);
            status("ready", "Indexed " + snapshot.fileCount() + " Python files");
            log(MessageType.Info, "PySonar2 indexed " + snapshot.fileCount() + " Python files");
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

    private static String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message;
    }
}
