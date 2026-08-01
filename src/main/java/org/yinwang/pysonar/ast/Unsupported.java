package org.yinwang.pysonar.ast;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class Unsupported extends Node {

    @NotNull
    public String originalType;
    @NotNull
    public List<Node> children;

    public Unsupported(String file, int start, int end, int line, int col) {
        this("unknown", new ArrayList<>(), file, start, end, line, col);
    }

    public Unsupported(@NotNull String originalType, @NotNull List<Node> children,
                       String file, int start, int end, int line, int col) {
        super(NodeType.UNSUPPORTED, file, start, end, line, col);
        this.originalType = originalType;
        this.children = children;
        addChildren(children);
    }

    @NotNull
    @Override
    public String toString() {
        return "(unsupported:" + originalType + ")";
    }
}
