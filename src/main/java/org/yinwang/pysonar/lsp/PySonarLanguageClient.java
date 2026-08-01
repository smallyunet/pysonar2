package org.yinwang.pysonar.lsp;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;

public interface PySonarLanguageClient extends LanguageClient {
    @JsonNotification("pysonar2/status")
    void status(PySonarStatus status);
}
