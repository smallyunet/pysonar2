package org.yinwang.pysonar.ast;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class FunctionDef extends Node {

    public Name name;
    public List<Node> args;
    public List<Node> defaults;
    public Name vararg;  // *args
    public Name kwarg;   // **kwarg
    public final List<Node> decorators;
    public final List<Node> kwOnlyArgs;
    public final List<Node> kwDefaults;
    public final List<Node> annotations;
    public final List<TypeParameter> typeParams;
    @Nullable
    public final Node returnAnnotation;
    public final int posOnlyArgCount;
    public List<Node> afterRest = null;   // after rest arg of Ruby
    public Node body;
    public boolean called = false;
    public boolean isLamba = false;
    public boolean isAsync = false;

    public FunctionDef(Name name, List<Node> args, Node body, List<Node> defaults,
        Name vararg, Name kwarg, List<Node> decorators, String file, boolean isAsync, int start, int end, int line, int col) {
        this(name, args, body, defaults, vararg, kwarg, decorators,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null, 0,
                new ArrayList<>(),
                file, isAsync, start, end, line, col);
    }

    public FunctionDef(Name name, List<Node> args, Node body, List<Node> defaults,
        Name vararg, Name kwarg, List<Node> decorators,
        List<Node> kwOnlyArgs, List<Node> kwDefaults, List<Node> annotations,
        @Nullable Node returnAnnotation, int posOnlyArgCount,
        List<TypeParameter> typeParams,
        String file, boolean isAsync, int start, int end, int line, int col) {
        super(NodeType.FUNCTIONDEF, file, start, end, line, col);
        if (name != null) {
            this.name = name;
        } else {
            isLamba = true;
            String fn = genLambdaName();
            this.name = new Name(fn, file, start, start + "lambda".length(), line, col + "lambda".length());
            addChildren(this.name);
        }

        this.args = args;
        this.body = body;
        this.defaults = defaults;
        this.vararg = vararg;
        this.kwarg = kwarg;
        this.decorators = decorators;
        this.kwOnlyArgs = kwOnlyArgs;
        this.kwDefaults = kwDefaults;
        this.annotations = annotations;
        this.returnAnnotation = returnAnnotation;
        this.posOnlyArgCount = posOnlyArgCount;
        this.typeParams = typeParams;
        this.isAsync = isAsync;
        addChildren(name);
        addChildren(args);
        addChildren(defaults);
        addChildren(kwOnlyArgs);
        addChildren(kwDefaults);
        addChildren(decorators);
        addChildren(annotations);
        addChildren(returnAnnotation);
        addChildren(typeParams);
        addChildren(vararg, kwarg, this.body);
    }

    public String getArgumentExpr() {
        StringBuilder argExpr = new StringBuilder();
        argExpr.append("(");
        boolean first = true;

        for (int i = 0; i < args.size(); i++) {
            Node n = args.get(i);
            if (!first) {
                argExpr.append(", ");
            }
            first = false;
            argExpr.append(n.toDisplay());
            if (posOnlyArgCount > 0 && i + 1 == posOnlyArgCount) {
                argExpr.append(", /");
            }
        }

        if (vararg != null) {
            if (!first) {
                argExpr.append(", ");
            }
            first = false;
            argExpr.append("*").append(vararg.toDisplay());
        } else if (!kwOnlyArgs.isEmpty()) {
            if (!first) {
                argExpr.append(", ");
            }
            first = false;
            argExpr.append("*");
        }

        for (Node n : kwOnlyArgs) {
            if (!first) {
                argExpr.append(", ");
            }
            first = false;
            argExpr.append(n.toDisplay());
        }

        if (kwarg != null) {
            if (!first) {
                argExpr.append(", ");
            }
            argExpr.append("**").append(kwarg.toDisplay());
        }

        argExpr.append(")");
        return argExpr.toString();
    }

    public boolean isStaticMethod() {
        for (Node d : decorators) {
            if (d instanceof Name && ((Name) d).id.equals("staticmethod")) {
                return true;
            }
        }
        return false;
    }

    public boolean isClassMethod() {
        for (Node d : decorators) {
            if (d instanceof Name && ((Name) d).id.equals("classmethod")) {
                return true;
            }
        }
        return false;
    }


    private static int lambdaCounter = 0;

    @NotNull
    public static String genLambdaName() {
        lambdaCounter = lambdaCounter + 1;
        return "lambda%" + lambdaCounter;
    }

    @NotNull
    @Override
    public String toString() {
        return "(func:" + start + ":" + name + ")";
    }

}
