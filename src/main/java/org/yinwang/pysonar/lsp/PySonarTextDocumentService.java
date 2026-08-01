package org.yinwang.pysonar.lsp;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class PySonarTextDocumentService implements TextDocumentService {

    private final PySonarLanguageServer server;

    PySonarTextDocumentService(PySonarLanguageServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        List<Location> locations = server.snapshot().definitions(path(params.getTextDocument().getUri()), params.getPosition());
        return CompletableFuture.completedFuture(Either.forLeft(locations));
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        boolean includeDeclaration = params.getContext() != null && params.getContext().isIncludeDeclaration();
        return CompletableFuture.completedFuture(server.snapshot().references(
                path(params.getTextDocument().getUri()), params.getPosition(), includeDeclaration));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.completedFuture(server.snapshot()
                .hover(path(params.getTextDocument().getUri()), params.getPosition()).orElse(null));
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        return CompletableFuture.completedFuture(
                server.snapshot().documentSymbols(path(params.getTextDocument().getUri())));
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        // The MVP serves the last saved workspace snapshot. didSave triggers a rebuild.
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        server.markStale();
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        // No per-document state is retained yet.
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        server.scheduleRebuild("Saved " + Path.of(URI.create(params.getTextDocument().getUri())).getFileName());
    }

    private static Path path(String uri) {
        try {
            return Path.of(URI.create(uri)).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return Path.of(uri).toAbsolutePath().normalize();
        }
    }
}
