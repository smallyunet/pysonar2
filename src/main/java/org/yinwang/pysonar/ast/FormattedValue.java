package org.yinwang.pysonar.ast;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class FormattedValue extends Node {

    @NotNull
    public Node value;
    @Nullable
    public Node formatSpec;
    public int conversion;

    public FormattedValue(@NotNull Node value, @Nullable Node formatSpec, int conversion,
                          String file, int start, int end, int line, int col) {
        super(NodeType.FORMATTEDVALUE, file, start, end, line, col);
        this.value = value;
        this.formatSpec = formatSpec;
        this.conversion = conversion;
        addChildren(value, formatSpec);
    }
}
