package org.yinwang.pysonar.lsp;

import org.eclipse.lsp4j.DiagnosticSeverity;

import java.util.Locale;

/** Controls how inference findings are surfaced in editors. */
final class DiagnosticPolicy {

    enum Mode {
        OFF,
        CONSERVATIVE,
        ALL
    }

    static final int DEFAULT_MAX_PER_FILE = 100;

    private final Mode mode;
    private final int maxPerFile;

    DiagnosticPolicy(Mode mode, int maxPerFile) {
        this.mode = mode == null ? Mode.CONSERVATIVE : mode;
        this.maxPerFile = Math.max(0, maxPerFile);
    }

    static DiagnosticPolicy conservative() {
        return new DiagnosticPolicy(Mode.CONSERVATIVE, DEFAULT_MAX_PER_FILE);
    }

    static DiagnosticPolicy from(Object mode, Object maxPerFile) {
        Mode parsedMode = Mode.CONSERVATIVE;
        if (mode != null) {
            try {
                parsedMode = Mode.valueOf(mode.toString().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                parsedMode = Mode.CONSERVATIVE;
            }
        }

        int parsedMax = DEFAULT_MAX_PER_FILE;
        if (maxPerFile instanceof Number) {
            parsedMax = ((Number) maxPerFile).intValue();
        } else if (maxPerFile != null) {
            try {
                parsedMax = Integer.parseInt(maxPerFile.toString());
            } catch (NumberFormatException ignored) {
                parsedMax = DEFAULT_MAX_PER_FILE;
            }
        }
        return new DiagnosticPolicy(parsedMode, parsedMax);
    }

    boolean enabled() {
        return mode != Mode.OFF && maxPerFile > 0;
    }

    int maxPerFile() {
        return maxPerFile;
    }

    boolean shouldPublish(String message) {
        if (!enabled()) {
            return false;
        }
        if (mode == Mode.CONSERVATIVE) {
            return isHighConfidence(message);
        }
        return !isCascade(message);
    }

    DiagnosticSeverity severity(String message) {
        if (message.startsWith("Unused variable:")) {
            return DiagnosticSeverity.Hint;
        }
        if (isHighConfidence(message)) {
            return DiagnosticSeverity.Error;
        }
        return DiagnosticSeverity.Warning;
    }

    private static boolean isCascade(String message) {
        return message.startsWith("Can't set attribute for UnknownType")
                || message.startsWith("attribute not found in type: ?")
                || message.startsWith("calling non-function and non-class: ?")
                || message.startsWith("unable to bind argument:")
                || message.startsWith("unable to bind keyword-only argument:")
                || message.contains(" to type ?")
                || (message.endsWith(" is not a function") && message.contains("?"))
                || message.endsWith("not an iterable type: ?");
    }

    private static boolean isHighConfidence(String message) {
        return message.startsWith("invalid location for assignment")
                || message.startsWith("Incorrect number of arguments for isinstance");
    }
}
