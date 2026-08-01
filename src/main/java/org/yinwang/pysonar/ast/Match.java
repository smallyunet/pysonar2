package org.yinwang.pysonar.ast;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public class Match extends Node {

    @NotNull
    public Node subject;
    @NotNull
    public List<MatchCase> cases;

    public Match(@NotNull Node subject, @NotNull List<MatchCase> cases,
                 String file, int start, int end, int line, int col) {
        super(NodeType.MATCH, file, start, end, line, col);
        this.subject = subject;
        this.cases = cases;
        addChildren(subject);
        addChildren(cases);
    }
}
