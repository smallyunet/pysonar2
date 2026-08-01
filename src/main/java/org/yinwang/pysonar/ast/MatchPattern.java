package org.yinwang.pysonar.ast;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/** A compact representation shared by Python's seven structural pattern node kinds. */
public class MatchPattern extends Node {

    @NotNull
    public String patternKind;
    @NotNull
    public List<Node> valueExpressions;
    @NotNull
    public List<MatchPattern> patterns;
    @NotNull
    public List<Name> captures;

    public MatchPattern(@NotNull String patternKind,
                        @NotNull List<Node> valueExpressions,
                        @NotNull List<MatchPattern> patterns,
                        @NotNull List<Name> captures,
                        String file, int start, int end, int line, int col) {
        super(NodeType.MATCHPATTERN, file, start, end, line, col);
        this.patternKind = patternKind;
        this.valueExpressions = valueExpressions;
        this.patterns = patterns;
        this.captures = captures;
        addChildren(valueExpressions);
        addChildren(patterns);
        addChildren(captures);
    }

    public boolean isIrrefutable() {
        if ("MatchAs".equals(patternKind) && patterns.isEmpty()) {
            return true;
        }
        if ("MatchOr".equals(patternKind)) {
            for (MatchPattern pattern : patterns) {
                if (pattern.isIrrefutable()) {
                    return true;
                }
            }
        }
        return false;
    }

}
