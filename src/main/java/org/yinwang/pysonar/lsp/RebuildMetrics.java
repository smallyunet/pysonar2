package org.yinwang.pysonar.lsp;

/** Auditable measurements for the most recent workspace rebuild decision. */
public final class RebuildMetrics {
    public enum Mode {
        FULL,
        INCREMENTAL,
        NO_CHANGE
    }

    private final Mode mode;
    private final int workspaceFiles;
    private final int changedFiles;
    private final int affectedFiles;
    private final int analyzedFiles;
    private final long astCacheHits;
    private final long astCacheMisses;
    private final long elapsedMillis;
    private final String reason;

    RebuildMetrics(Mode mode, int workspaceFiles, int changedFiles, int affectedFiles,
                   int analyzedFiles, long astCacheHits, long astCacheMisses,
                   long elapsedMillis, String reason) {
        this.mode = mode;
        this.workspaceFiles = workspaceFiles;
        this.changedFiles = changedFiles;
        this.affectedFiles = affectedFiles;
        this.analyzedFiles = analyzedFiles;
        this.astCacheHits = astCacheHits;
        this.astCacheMisses = astCacheMisses;
        this.elapsedMillis = elapsedMillis;
        this.reason = reason == null ? "" : reason;
    }

    static RebuildMetrics empty() {
        return new RebuildMetrics(Mode.NO_CHANGE, 0, 0, 0, 0, 0, 0, 0, "not run");
    }

    public Mode getMode() {
        return mode;
    }

    public int getWorkspaceFiles() {
        return workspaceFiles;
    }

    public int getChangedFiles() {
        return changedFiles;
    }

    public int getAffectedFiles() {
        return affectedFiles;
    }

    public int getAnalyzedFiles() {
        return analyzedFiles;
    }

    public long getAstCacheHits() {
        return astCacheHits;
    }

    public long getAstCacheMisses() {
        return astCacheMisses;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public String getReason() {
        return reason;
    }
}
