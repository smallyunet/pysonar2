package org.yinwang.pysonar.types;

import org.jetbrains.annotations.NotNull;

/** Element-aware type for set and frozenset values. */
public class SetType extends Type {

    @NotNull
    public Type eltType;
    public final boolean frozen;

    public SetType(@NotNull Type eltType) {
        this(eltType, false);
    }

    public SetType(@NotNull Type eltType, boolean frozen) {
        this.eltType = eltType;
        this.frozen = frozen;
        table.addSuper(Types.ObjectClass.table);
        table.setPath(frozen ? "frozenset" : "set");
    }

    public void add(@NotNull Type type) {
        eltType = UnionType.union(eltType, type);
    }

    @Override
    public boolean typeEquals(Object other) {
        return other instanceof SetType
                && frozen == ((SetType) other).frozen
                && eltType.typeEquals(((SetType) other).eltType);
    }

    @Override
    public int hashCode() {
        return frozen ? "FrozenSetType".hashCode() : "SetType".hashCode();
    }

    @Override
    protected String printType(@NotNull CyclicTypeRecorder ctr) {
        return (frozen ? "frozenset[" : "set[") + eltType.printType(ctr) + "]";
    }
}
