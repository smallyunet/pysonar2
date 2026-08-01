package org.yinwang.pysonar.ast;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class MatchCase extends Node {

    @NotNull
    public MatchPattern pattern;
    @Nullable
    public Node guard;
    @NotNull
    public Block body;

    public MatchCase(@NotNull MatchPattern pattern, @Nullable Node guard, @NotNull Block body,
                     String file, int start, int end, int line, int col) {
        super(NodeType.MATCHCASE, file, start, end, line, col);
        this.pattern = pattern;
        this.guard = guard;
        this.body = body;
        addChildren(pattern, guard, body);
    }
}
