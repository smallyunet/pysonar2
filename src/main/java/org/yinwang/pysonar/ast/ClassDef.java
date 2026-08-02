package org.yinwang.pysonar.ast;

import org.jetbrains.annotations.NotNull;
import org.yinwang.pysonar.Binding;
import org.yinwang.pysonar.Builtins;
import org.yinwang.pysonar.State;
import org.yinwang.pysonar.types.Type;

import java.util.ArrayList;
import java.util.List;

public class ClassDef extends Node {

    @NotNull
    public Name name;
    public List<Node> bases;
    public Node body;
    public List<Node> decorators;
    public List<Keyword> keywords;
    public List<TypeParameter> typeParams;

    public ClassDef(@NotNull Name name, List<Node> bases, Node body, String file, int start, int end, int line, int col) {
        this(name, bases, body, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                file, start, end, line, col);
    }

    public ClassDef(@NotNull Name name, List<Node> bases, Node body, List<Node> decorators,
        List<Keyword> keywords, List<TypeParameter> typeParams,
        String file, int start, int end, int line, int col) {
        super(NodeType.CLASSDEF, file, start, end, line, col);
        this.name = name;
        this.bases = bases;
        this.body = body;
        this.decorators = decorators;
        this.keywords = keywords;
        this.typeParams = typeParams;
        addChildren(name, this.body);
        addChildren(bases);
        addChildren(decorators);
        addChildren(keywords);
        addChildren(typeParams);
    }

    public void addSpecialAttribute(@NotNull State s, String name, Type proptype) {
        Binding b = new Binding(name, Builtins.newTutUrl("classes.html"), proptype, Binding.Kind.ATTRIBUTE);
        s.update(name, b);
        b.markSynthetic();
        b.markStatic();

    }

    @NotNull
    @Override
    public String toString() {
        return "(class:" + name.id + ":" + start + ")";
    }

}
