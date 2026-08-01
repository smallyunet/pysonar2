package org.yinwang.pysonar.lsp;

import org.eclipse.lsp4j.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Converts PySonar2 code-point offsets to LSP UTF-16 positions. */
final class PositionCodec {

    private PositionCodec() {
    }

    static Position toPosition(String source, int codePointOffset) {
        return index(source).toPosition(codePointOffset);
    }

    static int toCodePointOffset(String source, Position position) {
        return index(source).toCodePointOffset(position);
    }

    static LineIndex index(String source) {
        return new LineIndex(source);
    }

    /** Reuses per-line UTF-16/code-point offsets for fast LSP position conversion. */
    static final class LineIndex {
        private final String source;
        private final int[] lineCharStarts;
        private final int[] lineCodePointStarts;
        private final int totalCodePoints;

        LineIndex(String source) {
            this.source = source;
            List<Integer> charStarts = new ArrayList<>();
            List<Integer> codePointStarts = new ArrayList<>();
            charStarts.add(0);
            codePointStarts.add(0);
            int codePointOffset = 0;
            for (int charOffset = 0; charOffset < source.length();) {
                int codePoint = source.codePointAt(charOffset);
                int width = Character.charCount(codePoint);
                charOffset += width;
                codePointOffset++;
                if (codePoint == '\n') {
                    charStarts.add(charOffset);
                    codePointStarts.add(codePointOffset);
                }
            }
            this.lineCharStarts = toArray(charStarts);
            this.lineCodePointStarts = toArray(codePointStarts);
            this.totalCodePoints = codePointOffset;
        }

        Position toPosition(int codePointOffset) {
            int bounded = Math.max(0, Math.min(codePointOffset, totalCodePoints));
            int line = Arrays.binarySearch(lineCodePointStarts, bounded);
            if (line < 0) {
                line = -line - 2;
            }
            int lineCharStart = lineCharStarts[line];
            int charOffset = source.offsetByCodePoints(
                    lineCharStart, bounded - lineCodePointStarts[line]);
            return new Position(line, charOffset - lineCharStart);
        }

        int toCodePointOffset(Position position) {
            int line = Math.max(0, position.getLine());
            if (line >= lineCharStarts.length) {
                return totalCodePoints;
            }
            int lineStart = lineCharStarts[line];
            int lineEnd = line + 1 < lineCharStarts.length
                    ? Math.max(lineStart, lineCharStarts[line + 1] - 1)
                    : source.length();
            int charOffset = Math.min(lineStart + Math.max(0, position.getCharacter()), lineEnd);
            return lineCodePointStarts[line] + source.codePointCount(lineStart, charOffset);
        }

        private static int[] toArray(List<Integer> values) {
            int[] result = new int[values.size()];
            for (int i = 0; i < values.size(); i++) {
                result[i] = values.get(i);
            }
            return result;
        }
    }
}
