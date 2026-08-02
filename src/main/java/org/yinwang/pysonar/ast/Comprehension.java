package org.yinwang.pysonar.ast;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class Comprehension extends Node {

    public Node target;
    public Node iter;
    public List<Node> ifs;
    public boolean isAsync;

    public Comprehension(Node target, Node iter, List<Node> ifs, String file, int start, int end, int line, int col) {
        this(target, iter, ifs, false, file, start, end, line, col);
    }

    public Comprehension(Node target, Node iter, List<Node> ifs, boolean isAsync,
        String file, int start, int end, int line, int col) {
        super(NodeType.COMPREHENSION, file, start, end, line, col);
        this.target = target;
        this.iter = iter;
        this.ifs = ifs;
        this.isAsync = isAsync;
        addChildren(target, iter);
        addChildren(ifs);
    }

    @NotNull
    @Override
    public String toString() {
        return "<Comprehension:" + start + ":" + target + ":" + iter + ":" + ifs + ">";
    }

}
