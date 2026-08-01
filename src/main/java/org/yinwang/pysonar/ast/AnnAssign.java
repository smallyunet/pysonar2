package org.yinwang.pysonar.ast;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class AnnAssign extends Node {

    @NotNull
    public Node target;
    @NotNull
    public Node annotation;
    @Nullable
    public Node value;

    public AnnAssign(@NotNull Node target, @NotNull Node annotation, @Nullable Node value,
                     String file, int start, int end, int line, int col) {
        super(NodeType.ANNASSIGN, file, start, end, line, col);
        this.target = target;
        this.annotation = annotation;
        this.value = value;
        addChildren(target, annotation, value);
    }

    @NotNull
    @Override
    public String toString() {
        return "(annassign:" + target + ":" + annotation + ":" + value + ")";
    }
}
