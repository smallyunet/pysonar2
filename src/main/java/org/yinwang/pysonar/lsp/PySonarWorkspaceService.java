package org.yinwang.pysonar.lsp;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

final class PySonarWorkspaceService implements WorkspaceService {

    private final PySonarLanguageServer server;

    PySonarWorkspaceService(PySonarLanguageServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
            WorkspaceSymbolParams params) {
        List<Either<SymbolInformation, WorkspaceSymbol>> symbols = server.snapshot().workspaceSymbols(params.getQuery());
        // LSP4J 0.24 models the response as either all SymbolInformation or all WorkspaceSymbol.
        return CompletableFuture.completedFuture(Either.forLeft(symbols.stream()
                .filter(Either::isLeft)
                .map(Either::getLeft)
                .collect(java.util.stream.Collectors.toList())));
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // Configuration that affects file selection is applied when the client restarts the server.
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        server.scheduleRebuild("Workspace files changed");
    }

    @Override
    public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams params) {
        // The VS Code client runs one server process per workspace folder.
    }
}
