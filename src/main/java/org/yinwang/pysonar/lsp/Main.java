package org.yinwang.pysonar.lsp;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        PySonarLanguageServer server = new PySonarLanguageServer();
        Launcher<PySonarLanguageClient> launcher = new LSPLauncher.Builder<PySonarLanguageClient>()
                .setLocalService(server)
                .setRemoteInterface(PySonarLanguageClient.class)
                .setInput(System.in)
                .setOutput(System.out)
                .create();
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }
}
