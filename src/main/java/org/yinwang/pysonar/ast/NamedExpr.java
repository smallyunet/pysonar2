package org.yinwang.pysonar.ast;

import org.jetbrains.annotations.NotNull;

public class NamedExpr extends Node {

    @NotNull
    public Node target;
    @NotNull
    public Node value;

    public NamedExpr(@NotNull Node target, @NotNull Node value,
                     String file, int start, int end, int line, int col) {
        super(NodeType.NAMEDEXPR, file, start, end, line, col);
        this.target = target;
        this.value = value;
        addChildren(target, value);
    }
}
