package org.yinwang.pysonar.lsp;

import org.eclipse.lsp4j.Position;

/** Converts PySonar2 code-point offsets to LSP UTF-16 positions. */
final class PositionCodec {

    private PositionCodec() {
    }

    static Position toPosition(String source, int codePointOffset) {
        int bounded = Math.max(0, Math.min(codePointOffset, source.codePointCount(0, source.length())));
        int charOffset = source.offsetByCodePoints(0, bounded);
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < charOffset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, charOffset - lineStart);
    }

    static int toCodePointOffset(String source, Position position) {
        int requestedLine = Math.max(0, position.getLine());
        int line = 0;
        int lineStart = 0;
        while (line < requestedLine) {
            int newline = source.indexOf('\n', lineStart);
            if (newline < 0) {
                return source.codePointCount(0, source.length());
            }
            line++;
            lineStart = newline + 1;
        }

        int lineEnd = source.indexOf('\n', lineStart);
        if (lineEnd < 0) {
            lineEnd = source.length();
        }
        int charOffset = Math.min(lineStart + Math.max(0, position.getCharacter()), lineEnd);
        return source.codePointCount(0, charOffset);
    }
}
