package org.yinwang.pysonar;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yinwang.pysonar.ast.Node;
import org.yinwang.pysonar.types.ModuleType;
import org.yinwang.pysonar.types.Type;
import org.yinwang.pysonar.types.Types;
import org.yinwang.pysonar.types.UnionType;

import java.util.*;
import java.util.Map.Entry;


public class State {
    public enum StateType {
        CLASS,
        INSTANCE,
        FUNCTION,
        MODULE,
        GLOBAL,
        SCOPE
    }


    @NotNull
    public Map<String, Set<Binding>> table = new HashMap<>(0);
    @Nullable
    public State parent;      // all are non-null except global table
    @Nullable
    public State forwarding; // link to the closest non-class scope, for lifting functions out
    @Nullable
    public List<State> supers;
    @Nullable
    public Set<String> globalNames;
    public StateType stateType;
    public Type type;
    @NotNull
    public String path = "";


    public State(@Nullable State parent, StateType type) {
        this.parent = parent;
        this.stateType = type;

        if (type == StateType.CLASS) {
            this.forwarding = parent == null ? null : parent.getForwarding();
        } else {
            this.forwarding = this;
        }
    }


    public State(@NotNull State s) {
        this.table = new HashMap<>();
        this.table.putAll(s.table);
        this.parent = s.parent;
        this.stateType = s.stateType;
        this.forwarding = s.forwarding;
        this.supers = s.supers;
        this.globalNames = s.globalNames;
        this.type = s.type;
        this.path = s.path;
    }


    // erase and overwrite this to s's contents
    public void overwrite(@NotNull State s) {
        this.table = s.table;
        this.parent = s.parent;
        this.stateType = s.stateType;
        this.forwarding = s.forwarding;
        this.supers = s.supers;
        this.globalNames = s.globalNames;
        this.type = s.type;
        this.path = s.path;
    }


    @NotNull
    public State copy() {
        return new State(this);
    }


    public void merge(State other) {
        for (Map.Entry<String, Set<Binding>> e2 : other.table.entrySet()) {
            Set<Binding> b1 = table.get(e2.getKey());
            Set<Binding> b2 = e2.getValue();

            if (b1 != null && b2 != null) {
                b1.addAll(b2);
            } else if (b1 == null && b2 != null) {
                table.put(e2.getKey(), b2);
            }
        }
    }


    public static State merge(State state1, State state2) {
        State ret = state1.copy();
        ret.merge(state2);
        return ret;
    }


    public void setParent(@Nullable State parent) {
        this.parent = parent;
    }


    public State getForwarding() {
        if (forwarding != null) {
            return forwarding;
        } else {
            return this;
        }
    }


    public void addSuper(State sup) {
        if (supers == null) {
            supers = new ArrayList<>();
        }
        supers.add(sup);
    }


    public void setStateType(StateType type) {
        this.stateType = type;
    }


    public void addGlobalName(@NotNull String name) {
        if (globalNames == null) {
            globalNames = new HashSet<>(1);
        }
        globalNames.add(name);
    }


    public boolean isGlobalName(@NotNull String name) {
        if (globalNames != null) {
            return globalNames.contains(name);
        } else if (parent != null) {
            return parent.isGlobalName(name);
        } else {
            return false;
        }
    }


    public void remove(String id) {
        table.remove(id);
    }


    // create new binding and insert
    public void insert(String id, @NotNull Node node, @NotNull Type type, Binding.Kind kind) {
        Binding b = new Binding(id, node, type, kind);
        if (type instanceof ModuleType) {
            b.setQname(type.asModuleType().qname);
        } else {
            b.setQname(extendPath(id));
        }
        update(id, b);
    }


    // directly insert a given binding
    @NotNull
    public Set<Binding> update(String id, @NotNull Set<Binding> bs) {
        table.put(id, bs);
        return bs;
    }


    @NotNull
    public Set<Binding> update(String id, @NotNull Binding b) {
        Set<Binding> bs = new HashSet<>(1);
        bs.add(b);
        table.put(id, bs);
        return bs;
    }


    public void setPath(@NotNull String path) {
        this.path = path;
    }


    public void setType(Type type) {
        this.type = type;
    }


    /**
     * Look up a name in the current symbol table only. Don't recurse on the
     * parent table.
     */
    @Nullable
    public Set<Binding> lookupLocal(String name) {
        return table.get(name);
    }


    /**
     * Look up a name (String) in the current symbol table.  If not found,
     * recurse on the parent table.
     */
    @Nullable
    public Set<Binding> lookup(@NotNull String name) {
        Set<Binding> b = getModuleBindingIfGlobal(name);
        if (b != null) {
            return b;
        } else {
            Set<Binding> ent = lookupLocal(name);
            if (ent != null) {
                return ent;
            } else {
                if (parent != null) {
                    return parent.lookup(name);
                } else {
                    return null;
                }
            }
        }
    }


    /**
     * Look up a name in the module if it is declared as global, otherwise look
     * it up locally.
     */
    @Nullable
    public Set<Binding> lookupScope(String name) {
        Set<Binding> b = getModuleBindingIfGlobal(name);
        if (b != null) {
            return b;
        } else {
            return lookupLocal(name);
        }
    }


    /**
     * Look up an attribute using Python's C3 method-resolution order. Parent
     * links are lexical scopes and intentionally do not participate.
     */
    @Nullable
    public Set<Binding> lookupAttr(String attr) {
        for (State state : resolutionOrder()) {
            Set<Binding> binding = state.lookupLocal(attr);
            if (binding != null) {
                return binding;
            }
        }
        return null;
    }


    /**
     * Return every inherited binding for an attribute in C3 order.  Normal
     * attribute lookup intentionally stops at the first match, but change
     * impact also needs the complete override family so a base declaration,
     * an override, and calls resolved to either side remain connected.
     */
    @NotNull
    public Set<Binding> lookupInheritedAttrs(String attr) {
        Set<Binding> result = new LinkedHashSet<>();
        List<State> order = resolutionOrder();
        for (int i = 1; i < order.size(); i++) {
            Set<Binding> binding = order.get(i).lookupLocal(attr);
            if (binding != null) {
                result.addAll(binding);
            }
        }
        return result;
    }


    /** Returns a deterministic C3 linearization, with this state first. */
    @NotNull
    List<State> resolutionOrder() {
        return linearize(this, new IdentityHashMap<>(),
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }


    @NotNull
    private static List<State> linearize(@NotNull State state,
                                         @NotNull Map<State, List<State>> memo,
                                         @NotNull Set<State> active) {
        List<State> cached = memo.get(state);
        if (cached != null) {
            return new ArrayList<>(cached);
        }
        if (!active.add(state)) {
            return new ArrayList<>(Collections.singletonList(state));
        }

        List<List<State>> sequences = new ArrayList<>();
        if (state.supers != null) {
            for (State parent : state.supers) {
                sequences.add(linearize(parent, memo, active));
            }
            sequences.add(new ArrayList<>(state.supers));
        }

        List<State> result = new ArrayList<>();
        result.add(state);
        mergeLinearizations(result, sequences);
        active.remove(state);
        memo.put(state, new ArrayList<>(result));
        return result;
    }


    private static void mergeLinearizations(@NotNull List<State> result,
                                            @NotNull List<List<State>> sequences) {
        while (sequences.stream().anyMatch(sequence -> !sequence.isEmpty())) {
            State candidate = null;
            for (List<State> sequence : sequences) {
                if (sequence.isEmpty()) {
                    continue;
                }
                State head = sequence.get(0);
                boolean appearsInTail = sequences.stream().anyMatch(other ->
                        other.size() > 1 && other.subList(1, other.size()).contains(head));
                if (!appearsInTail) {
                    candidate = head;
                    break;
                }
            }

            // Invalid Python hierarchies are rejected at runtime. Keep analysis
            // conservative and deterministic instead of recursing forever.
            if (candidate == null) {
                for (List<State> sequence : sequences) {
                    if (!sequence.isEmpty()) {
                        candidate = sequence.get(0);
                        break;
                    }
                }
            }

            if (!result.contains(candidate)) {
                result.add(candidate);
            }
            final State selected = candidate;
            for (List<State> sequence : sequences) {
                sequence.removeIf(item -> item == selected);
            }
        }
    }


    /**
     * Look for a binding named {@code name} and if found, return its type.
     */
    @Nullable
    public Type lookupType(String name) {
        Set<Binding> bs = lookup(name);
        if (bs == null) {
            return null;
        } else {
            return makeUnion(bs);
        }
    }


    /**
     * Look for a attribute named {@code attr} and if found, return its type.
     */
    @Nullable
    public Type lookupAttrType(String attr) {
        Set<Binding> bs = lookupAttr(attr);
        if (bs == null) {
            return null;
        } else {
            return makeUnion(bs);
        }
    }


    public static Type makeUnion(Set<Binding> bs) {
        Type t = Types.UNKNOWN;
        for (Binding b : bs) {
            t = UnionType.union(t, b.type);
        }
        return t;
    }


    /**
     * Find a symbol table of a certain type in the enclosing scopes.
     */
    @Nullable
    public State getStateOfType(StateType type) {
        if (stateType == type) {
            return this;
        } else if (parent == null) {
            return null;
        } else {
            return parent.getStateOfType(type);
        }
    }


    /**
     * Returns the global scope (i.e. the module scope for the current module).
     */
    @NotNull
    public State getGlobalTable() {
        State result = getStateOfType(StateType.MODULE);
        if (result != null) {
            return result;
        } else {
            $.die("Couldn't find global table. Shouldn't happen");
            return this;
        }
    }


    /**
     * If {@code name} is declared as a global, return the module binding.
     */
    @Nullable
    private Set<Binding> getModuleBindingIfGlobal(@NotNull String name) {
        if (isGlobalName(name)) {
            State module = getGlobalTable();
            if (module != this) {
                return module.lookupLocal(name);
            }
        }
        return null;
    }


    public void putAll(@NotNull State other) {
        table.putAll(other.table);
    }


    @NotNull
    public Set<String> keySet() {
        return table.keySet();
    }


    @NotNull
    public Collection<Binding> values() {
        Set<Binding> ret = new HashSet<>();
        for (Set<Binding> bs : table.values()) {
            ret.addAll(bs);
        }
        return ret;
    }


    @NotNull
    public Set<Entry<String, Set<Binding>>> entrySet() {
        return table.entrySet();
    }


    public boolean isEmpty() {
        return table.isEmpty();
    }


    @NotNull
    public String extendPath(@NotNull String name) {
        name = $.moduleName(name);
        if (path.equals("")) {
            return name;
        }
        return path + "." + name;
    }


    @NotNull
    @Override
    public String toString() {
        return "<State:" + stateType + ":" + table.keySet() + ">";
    }

}
