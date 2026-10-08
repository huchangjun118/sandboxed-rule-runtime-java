package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closed value universe of the rule language: null, booleans, numbers
 * (double precision), strings, arrays and ordered maps. Nothing else is
 * reachable from a script - no Java object handles, no class loaders - which
 * is a core part of the sandbox boundary.
 */
public sealed interface Value permits Value.NullVal, Value.BoolVal, Value.NumVal,
        Value.StrVal, Value.ArrVal, Value.ObjVal {

    long OBJECT_OVERHEAD = 16L;

    static Value ofNull() {
        return NullVal.INSTANCE;
    }

    static Value of(boolean b) {
        return b ? BoolVal.TRUE : BoolVal.FALSE;
    }

    static Value of(long n) {
        return new NumVal(n);
    }

    static Value of(double n) {
        return new NumVal(n);
    }

    static Value of(String s) {
        return s == null ? NullVal.INSTANCE : new StrVal(s);
    }

    /**
     * Convert JSON-shaped host data (the only kind request bodies yield:
     * null/Boolean/Number/String/Map/List) into the closed value universe.
     * Every conversion produces a fresh, detached graph, so later script
     * mutation can never reach the caller's input objects.
     */
    static Value ofJava(Object o) {
        if (o == null) {
            return NullVal.INSTANCE;
        }
        if (o instanceof Value v) {
            return v;
        }
        if (o instanceof Boolean b) {
            return of(b.booleanValue());
        }
        if (o instanceof Number n) {
            return new NumVal(n.doubleValue());
        }
        if (o instanceof String s) {
            return new StrVal(s);
        }
        if (o instanceof Character c) {
            return new StrVal(String.valueOf(c));
        }
        if (o instanceof Map<?, ?> map) {
            LinkedHashMap<String, Value> fields = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                fields.put(String.valueOf(e.getKey()), ofJava(e.getValue()));
            }
            return new ObjVal(fields);
        }
        if (o instanceof Iterable<?> it) {
            java.util.ArrayList<Value> items = new java.util.ArrayList<>();
            for (Object item : it) {
                items.add(ofJava(item));
            }
            return new ArrVal(items);
        }
        if (o.getClass().isArray()) {
            java.util.ArrayList<Value> items = new java.util.ArrayList<>();
            int length = java.lang.reflect.Array.getLength(o);
            for (int i = 0; i < length; i++) {
                items.add(ofJava(java.lang.reflect.Array.get(o, i)));
            }
            return new ArrVal(items);
        }
        throw new IllegalArgumentException(
                "unsupported input value type (outside JSON universe): " + o.getClass().getName());
    }

    Object toPlainJava();

    /** Conservative tracked allocation cost, used by the memory quota. */
    long estimatedBytes();

    static String typeName(Value v) {
        if (v instanceof NullVal) {
            return "null";
        }
        if (v instanceof BoolVal) {
            return "boolean";
        }
        if (v instanceof NumVal) {
            return "number";
        }
        if (v instanceof StrVal) {
            return "string";
        }
        if (v instanceof ArrVal) {
            return "array";
        }
        return "object";
    }

    static boolean truthy(Value v) {
        if (v instanceof NullVal) {
            return false;
        }
        if (v instanceof BoolVal b) {
            return b.b();
        }
        if (v instanceof NumVal n) {
            return n.n() != 0.0d;
        }
        if (v instanceof StrVal str) {
            return !str.s().isEmpty();
        }
        if (v instanceof ArrVal a) {
            return !a.items().isEmpty();
        }
        if (v instanceof ObjVal o) {
            return !o.fields().isEmpty();
        }
        return false;
    }

    static boolean valueEquals(Value a, Value b) {
        if (a instanceof NullVal && b instanceof NullVal) {
            return true;
        }
        if (a instanceof NumVal x && b instanceof NumVal y) {
            return x.n() == y.n();
        }
        if (a instanceof StrVal x && b instanceof StrVal y) {
            return x.s().equals(y.s());
        }
        if (a instanceof BoolVal x && b instanceof BoolVal y) {
            return x.b() == y.b();
        }
        if (a instanceof ArrVal x && b instanceof ArrVal y) {
            List<Value> xs = x.items();
            List<Value> ys = y.items();
            if (xs.size() != ys.size()) {
                return false;
            }
            for (int i = 0; i < xs.size(); i++) {
                if (!valueEquals(xs.get(i), ys.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof ObjVal x && b instanceof ObjVal y) {
            return deepEquals(x.fields(), y.fields());
        }
        return false;
    }

    private static boolean deepEquals(LinkedHashMap<String, Value> a,
                                      LinkedHashMap<String, Value> b) {
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (Map.Entry<String, Value> e : a.entrySet()) {
            if (!valueEquals(e.getValue(), b.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    record NullVal() implements Value {
        static final NullVal INSTANCE = new NullVal();

        @Override
        public Object toPlainJava() {
            return null;
        }

        @Override
        public long estimatedBytes() {
            return OBJECT_OVERHEAD;
        }
    }

    record BoolVal(boolean b) implements Value {
        static final BoolVal TRUE = new BoolVal(true);
        static final BoolVal FALSE = new BoolVal(false);

        @Override
        public Object toPlainJava() {
            return b;
        }

        @Override
        public long estimatedBytes() {
            return OBJECT_OVERHEAD;
        }
    }

    record NumVal(double n) implements Value {
        @Override
        public Object toPlainJava() {
            if (n == Math.rint(n) && !Double.isInfinite(n)
                    && n >= Long.MIN_VALUE && n <= Long.MAX_VALUE) {
                return (long) n;
            }
            return n;
        }

        @Override
        public long estimatedBytes() {
            return OBJECT_OVERHEAD + 8L;
        }
    }

    record StrVal(String s) implements Value {
        @Override
        public Object toPlainJava() {
            return s;
        }

        @Override
        public long estimatedBytes() {
            return OBJECT_OVERHEAD + 24L + 2L * s.length();
        }
    }

    record ArrVal(List<Value> items) implements Value {
        public ArrVal {
            items = List.copyOf(items);
        }

        @Override
        public Object toPlainJava() {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>(items.size());
            for (Value item : items) {
                out.add(item.toPlainJava());
            }
            return out;
        }

        @Override
        public long estimatedBytes() {
            long total = OBJECT_OVERHEAD + 24L + 4L * items.size();
            for (Value item : items) {
                total += item.estimatedBytes();
            }
            return total;
        }
    }

    record ObjVal(LinkedHashMap<String, Value> fields) implements Value {
        public ObjVal {
            LinkedHashMap<String, Value> copy = new LinkedHashMap<>();
            copy.putAll(fields);
            fields = copy;
        }

        @Override
        public Object toPlainJava() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Value> e : fields.entrySet()) {
                out.put(e.getKey(), e.getValue().toPlainJava());
            }
            return out;
        }

        @Override
        public long estimatedBytes() {
            long total = OBJECT_OVERHEAD + 32L + 32L * fields.size();
            for (Map.Entry<String, Value> e : fields.entrySet()) {
                total += 40L + 2L * e.getKey().length() + e.getValue().estimatedBytes();
            }
            return total;
        }
    }
}
