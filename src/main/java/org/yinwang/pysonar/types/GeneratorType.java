package org.yinwang.pysonar.types;

import org.jetbrains.annotations.NotNull;

/** Conservative element-aware model shared by generators and iterator annotations. */
public class GeneratorType extends Type {

    @NotNull
    public final Type elementType;
    public final boolean async;

    public GeneratorType(@NotNull Type elementType) {
        this(elementType, false);
    }

    public GeneratorType(@NotNull Type elementType, boolean async) {
        this.elementType = elementType;
        this.async = async;
        table.addSuper(Types.ObjectClass.table);
        table.setPath(async ? "AsyncIterator" : "Generator");
    }

    @Override
    public boolean typeEquals(Object other) {
        return other instanceof GeneratorType
                && async == ((GeneratorType) other).async
                && elementType.typeEquals(((GeneratorType) other).elementType);
    }

    @Override
    public int hashCode() {
        return async ? "AsyncGeneratorType".hashCode() : "GeneratorType".hashCode();
    }

    @Override
    protected String printType(@NotNull CyclicTypeRecorder ctr) {
        return (async ? "AsyncIterator[" : "Generator[") + elementType.printType(ctr) + "]";
    }
}
