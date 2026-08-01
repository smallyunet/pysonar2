package org.yinwang.pysonar.ast;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class JoinedStr extends Node {

    @NotNull
    public List<Node> values;

    public JoinedStr(@NotNull List<Node> values, String file, int start, int end, int line, int col) {
        super(NodeType.JOINEDSTR, file, start, end, line, col);
        this.values = values;
        addChildren(values);
    }
}
