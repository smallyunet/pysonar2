package org.yinwang.pysonar.lsp;

/** Structured progress emitted while a workspace snapshot is rebuilt. */
public final class AnalysisProgress {
    private final String phase;
    private final int current;
    private final int total;
    private final String path;
    private final long elapsedMillis;

    AnalysisProgress(String phase, int current, int total, String path, long elapsedMillis) {
        this.phase = phase;
        this.current = current;
        this.total = total;
        this.path = path;
        this.elapsedMillis = elapsedMillis;
    }

    public String getPhase() {
        return phase;
    }

    public int getCurrent() {
        return current;
    }

    public int getTotal() {
        return total;
    }

    public String getPath() {
        return path;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }
}
