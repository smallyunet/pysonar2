package org.yinwang.pysonar.types;

import org.jetbrains.annotations.NotNull;

/** Conservative result wrapper for values returned by an async function call. */
public class AwaitableType extends Type {

    @NotNull
    public final Type resultType;

    public AwaitableType(@NotNull Type resultType) {
        this.resultType = resultType;
        table.addSuper(Types.ObjectClass.table);
        table.setPath("Awaitable");
    }

    @Override
    public boolean typeEquals(Object other) {
        return other instanceof AwaitableType
                && resultType.typeEquals(((AwaitableType) other).resultType);
    }

    @Override
    public int hashCode() {
        return "AwaitableType".hashCode();
    }

    @Override
    protected String printType(@NotNull CyclicTypeRecorder ctr) {
        return "Awaitable[" + resultType.printType(ctr) + "]";
    }
}
