package org.yinwang.pysonar.ast;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class TypeParameter extends Node {

    @NotNull
    public final String parameterKind;
    @NotNull
    public final Name nameNode;
    @Nullable
    public final Node bound;
    @Nullable
    public final Node defaultValue;

    public TypeParameter(@NotNull String parameterKind, @NotNull Name nameNode,
        @Nullable Node bound, @Nullable Node defaultValue,
        String file, int start, int end, int line, int col) {
        super(NodeType.TYPEPARAMETER, file, start, end, line, col);
        this.parameterKind = parameterKind;
        this.nameNode = nameNode;
        this.bound = bound;
        this.defaultValue = defaultValue;
        addChildren(nameNode, bound, defaultValue);
    }

    @NotNull
    @Override
    public String toString() {
        return "(type-parameter:" + parameterKind + ":" + nameNode + ")";
    }
}
