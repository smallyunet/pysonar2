package org.yinwang.pysonar.ast;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class TypeAlias extends Node {

    @NotNull
    public final Name nameNode;
    @NotNull
    public final List<TypeParameter> typeParams;
    @NotNull
    public final Node value;

    public TypeAlias(@NotNull Name nameNode, @NotNull List<TypeParameter> typeParams,
        @NotNull Node value, String file, int start, int end, int line, int col) {
        super(NodeType.TYPEALIAS, file, start, end, line, col);
        this.nameNode = nameNode;
        this.typeParams = typeParams;
        this.value = value;
        addChildren(nameNode, value);
        addChildren(typeParams);
    }

    @NotNull
    @Override
    public String toString() {
        return "(type-alias:" + nameNode + ")";
    }
}
