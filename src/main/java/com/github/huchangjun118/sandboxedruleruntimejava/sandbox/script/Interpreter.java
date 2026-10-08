package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ScriptRuntimeException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Tree-walking evaluator over the closed {@link AstNode}/{@link Value}
 * universe.
 *
 * <p>Isolation and safety properties:</p>
 * <ul>
 *   <li>the only objects reachable are {@link Value}s - no host handles, so
 *       file/process/network/reflection operations are simply absent;</li>
 *   <li>every statement and loop iteration calls {@link RuntimeContext#tick()},
 *       which enforces step and wall-time quotas, so even {@code while(true){}}
 *       is interrupted;</li>
 *   <li>fresh allocations are charged to the memory quota and {@code log}
 *       output to the output quota;</li>
 *   <li>function entry/exit enforces recursion depth;</li>
 *   <li>all failures unwind as ordinary exceptions: the worker thread stays
 *       alive and every subsequent execution starts from clean state;</li>
 *   <li>randomness comes exclusively from the per-execution seeded
 *       {@link RuntimeContext}, so results are reproducible.</li>
 * </ul>
 */
public final class Interpreter {

    /** Non-local control flow for {@code return}; never escapes the evaluator. */
    private static final class ReturnSignal extends RuntimeException {
        private final Value value;

        private ReturnSignal(Value value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    /** User-defined function captured over its defining environment. */
    private static final class FunctionValue {
        final AstNode.FunctionDecl decl;
        final Environment closure;

        FunctionValue(AstNode.FunctionDecl decl, Environment closure) {
            this.decl = decl;
            this.closure = closure;
        }
    }

    private static final class Environment {
        private final Environment parent;
        private final LinkedHashMap<String, Object> bindings = new LinkedHashMap<>();

        private Environment(Environment parent) {
            this.parent = parent;
        }

        void define(String name, Object value) {
            bindings.put(name, value);
        }

        boolean assign(String name, Object value) {
            for (Environment env = this; env != null; env = env.parent) {
                if (env.bindings.containsKey(name)) {
                    env.bindings.put(name, value);
                    return true;
                }
            }
            return false;
        }

        Object lookup(String name) {
            for (Environment env = this; env != null; env = env.parent) {
                if (env.bindings.containsKey(name)) {
                    return env.bindings.get(name);
                }
            }
            return UNDEFINED;
        }
    }

    private static final Object UNDEFINED = new Object();

    public Interpreter() {
    }

    public EvalOutcome evaluate(AstNode.Program program, Value input, RuntimeContext context) {
        Environment global = new Environment(null);
        global.define("input", input);

        // Register all functions first so call order does not matter.
        for (AstNode node : program.body()) {
            if (node instanceof AstNode.FunctionDecl fn) {
                global.define(fn.name(), new FunctionValue(fn, global));
            }
        }

        Value returned = Value.ofNull();
        try {
            for (AstNode node : program.body()) {
                if (node instanceof AstNode.FunctionDecl) {
                    continue;
                }
                Object v = execute(node, global, context);
                if (v instanceof Value rv) {
                    returned = rv;
                }
            }
        } catch (ReturnSignal signal) {
            returned = signal.value;
        }
        return new EvalOutcome(returned, context.output());
    }

    // ---- statements --------------------------------------------------

    private Object execute(AstNode node, Environment env, RuntimeContext ctx) {
        ctx.tick();
        if (node instanceof AstNode.Block b) {
            Environment block = new Environment(env);
            for (AstNode s : b.statements()) {
                execute(s, block, ctx);
            }
            return null;
        }
        if (node instanceof AstNode.VarStmt s) {
            Value v = s.initializer() == null
                    ? Value.ofNull() : eval(s.initializer(), env, ctx);
            env.define(s.name(), v);
            return null;
        }
        if (node instanceof AstNode.IfStmt s) {
            if (Value.truthy(eval(s.condition(), env, ctx))) {
                execute(s.thenBranch(), env, ctx);
            } else if (s.elseBranch() != null) {
                execute(s.elseBranch(), env, ctx);
            }
            return null;
        }
        if (node instanceof AstNode.WhileStmt s) {
            int guard = 0;
            while (Value.truthy(eval(s.condition(), env, ctx))) {
                execute(s.body(), env, ctx);
                if (++guard < 0) {
                    break;
                }
            }
            return null;
        }
        if (node instanceof AstNode.ForStmt s) {
            Value iterable = eval(s.iterable(), env, ctx);
            runForLoop(s.variable(), s.body(), iterable, env, ctx);
            return null;
        }
        if (node instanceof AstNode.ReturnStmt s) {
            Value v = s.value() == null ? Value.ofNull() : eval(s.value(), env, ctx);
            throw new ReturnSignal(v);
        }
        if (node instanceof AstNode.LogStmt s) {
            Value v = eval(s.value(), env, ctx);
            ctx.appendOutput(stringify(v));
            return null;
        }
        if (node instanceof AstNode.ExprStmt s) {
            eval(s.expression(), env, ctx);
            return null;
        }
        throw new ScriptRuntimeException("unsupported statement: " + node.getClass().getSimpleName());
    }

    private void runForLoop(String variable, AstNode body, Value iterable,
                            Environment env, RuntimeContext ctx) {
        Environment loopEnv = new Environment(env);
        if (iterable instanceof Value.ArrVal arr) {
            for (Value item : arr.items()) {
                loopEnv.define(variable, item);
                execute(body, loopEnv, ctx);
            }
        } else if (iterable instanceof Value.ObjVal obj) {
            for (String key : obj.fields().keySet()) {
                loopEnv.define(variable, Value.of(key));
                execute(body, loopEnv, ctx);
            }
        } else if (iterable instanceof Value.StrVal str) {
            for (int i = 0; i < str.s().length(); i++) {
                loopEnv.define(variable, Value.of(String.valueOf(str.s().charAt(i))));
                execute(body, loopEnv, ctx);
            }
        } else {
            throw new ScriptRuntimeException(
                    "for-in requires an array, object or string, got "
                            + Value.typeName(iterable));
        }
    }

    // ---- expressions -------------------------------------------------

    private Value eval(AstNode node, Environment env, RuntimeContext ctx) {
        ctx.tick();
        if (node instanceof AstNode.NumberLit n) {
            return Value.of(n.value());
        }
        if (node instanceof AstNode.StringLit s) {
            Value v = Value.of(s.value());
            ctx.chargeMemory(v.estimatedBytes());
            return v;
        }
        if (node instanceof AstNode.BoolLit b) {
            return Value.of(b.value());
        }
        if (node instanceof AstNode.NullLit) {
            return Value.ofNull();
        }
        if (node instanceof AstNode.VarRef ref) {
            Object v = env.lookup(ref.name());
            if (v == UNDEFINED) {
                throw new ScriptRuntimeException(
                        "undefined variable '" + ref.name() + "' at line " + ref.line());
            }
            return (Value) v;
        }
        if (node instanceof AstNode.ArrayLit a) {
            List<Value> items = new ArrayList<>(a.elements().size());
            for (AstNode el : a.elements()) {
                items.add(eval(el, env, ctx));
            }
            Value v = new Value.ArrVal(items);
            ctx.chargeMemory(v.estimatedBytes());
            return v;
        }
        if (node instanceof AstNode.ObjectLit o) {
            LinkedHashMap<String, Value> fields = new LinkedHashMap<>();
            for (AstNode.Property p : o.properties()) {
                fields.put(p.name(), eval(p.value(), env, ctx));
            }
            Value v = new Value.ObjVal(fields);
            ctx.chargeMemory(v.estimatedBytes());
            return v;
        }
        if (node instanceof AstNode.Unary u) {
            Value operand = eval(u.operand(), env, ctx);
            return applyUnary(u.operator(), operand, u.line());
        }
        if (node instanceof AstNode.Binary b) {
            Value left = eval(b.left(), env, ctx);
            Value right = eval(b.right(), env, ctx);
            return applyBinary(b.operator(), left, right, b.line(), ctx);
        }
        if (node instanceof AstNode.Logical l) {
            Value left = eval(l.left(), env, ctx);
            if ("&&".equals(l.operator())) {
                return Value.truthy(left) ? eval(l.right(), env, ctx) : left;
            }
            return Value.truthy(left) ? left : eval(l.right(), env, ctx);
        }
        if (node instanceof AstNode.Assign a) {
            Value v = eval(a.value(), env, ctx);
            if (!env.assign(a.name(), v)) {
                throw new ScriptRuntimeException(
                        "assignment to undefined variable '" + a.name()
                                + "' at line " + a.line());
            }
            return v;
        }
        if (node instanceof AstNode.Call call) {
            return evalCall(call, env, ctx);
        }
        if (node instanceof AstNode.Member m) {
            return evalMember(eval(m.object(), env, ctx), m.field(), m.line(), ctx);
        }
        if (node instanceof AstNode.MethodCall mc) {
            // Defence in depth: the pre-execution validator rejects every
            // method call, because the closed value universe has no methods.
            throw new com.github.huchangjun118.sandboxedruleruntimejava
                    .sandbox.SandboxViolationException(
                    com.github.huchangjun118.sandboxedruleruntimejava
                            .sandbox.SandboxViolationException.DENIED,
                    "method call '" + mc.method() + "(...)' is not available in the sandbox"
                            + " at line " + mc.line());
        }
        if (node instanceof AstNode.Index idx) {
            Value target = eval(idx.object(), env, ctx);
            Value key = eval(idx.index(), env, ctx);
            return evalIndex(target, key, idx.line(), ctx);
        }
        throw new ScriptRuntimeException("unsupported expression: " + node.getClass().getSimpleName());
    }

    private Value applyUnary(String op, Value v, int line) {
        if ("!".equals(op)) {
            return Value.of(!Value.truthy(v));
        }
        if ("-".equals(op)) {
            if (v instanceof Value.NumVal n) {
                return Value.of(-n.n());
            }
            throw new ScriptRuntimeException(
                    "unary '-' requires a number, got " + Value.typeName(v) + " at line " + line);
        }
        throw new ScriptRuntimeException("unknown unary operator " + op);
    }

    private Value applyBinary(String op, Value left, Value right, int line, RuntimeContext ctx) {
        if ("==".equals(op)) {
            return Value.of(Value.valueEquals(left, right));
        }
        if ("!=".equals(op)) {
            return Value.of(!Value.valueEquals(left, right));
        }
        if ("+".equals(op)
                && !(left instanceof Value.NumVal && right instanceof Value.NumVal)) {
            Value result = Value.of(stringify(left) + stringify(right));
            ctx.chargeMemory(result.estimatedBytes());
            return result;
        }
        double l = asNumber(left, op, line);
        double r = asNumber(right, op, line);
        return switch (op) {
            case "+" -> Value.of(l + r);
            case "-" -> Value.of(l - r);
            case "*" -> Value.of(l * r);
            case "/" -> {
                if (r == 0.0d) {
                    throw new ScriptRuntimeException("division by zero at line " + line);
                }
                yield Value.of(l / r);
            }
            case "%" -> {
                if (r == 0.0d) {
                    throw new ScriptRuntimeException("modulo by zero at line " + line);
                }
                yield Value.of(l % r);
            }
            case "<" -> Value.of(l < r);
            case "<=" -> Value.of(l <= r);
            case ">" -> Value.of(l > r);
            case ">=" -> Value.of(l >= r);
            default -> throw new ScriptRuntimeException("unknown operator '" + op + "'");
        };
    }

    private double asNumber(Value v, String op, int line) {
        if (v instanceof Value.NumVal n) {
            return n.n();
        }
        throw new ScriptRuntimeException("operator '" + op + "' requires numbers, got "
                + Value.typeName(v) + " at line " + line);
    }

    // ---- member / index access --------------------------------------

    private Value evalMember(Value target, String field, int line, RuntimeContext ctx) {
        if (target instanceof Value.ObjVal obj) {
            Value v = obj.fields().get(field);
            if (v == null) {
                return Value.ofNull();
            }
            return v;
        }
        if (target instanceof Value.StrVal str && "length".equals(field)) {
            return Value.of(str.s().length());
        }
        if (target instanceof Value.ArrVal arr && "length".equals(field)) {
            return Value.of(arr.items().size());
        }
        throw new ScriptRuntimeException("cannot read field '" + field + "' of "
                + Value.typeName(target) + " at line " + line);
    }

    private Value evalIndex(Value target, Value key, int line, RuntimeContext ctx) {
        if (target instanceof Value.ArrVal arr) {
            if (!(key instanceof Value.NumVal num)) {
                throw new ScriptRuntimeException(
                        "array index must be a number, got " + Value.typeName(key)
                                + " at line " + line);
            }
            int idx = (int) num.n();
            if (idx < 0 || idx >= arr.items().size()) {
                throw new ScriptRuntimeException(
                        "array index out of bounds: " + idx + " (length "
                                + arr.items().size() + ") at line " + line);
            }
            return arr.items().get(idx);
        }
        if (target instanceof Value.ObjVal obj) {
            if (!(key instanceof Value.StrVal str)) {
                throw new ScriptRuntimeException(
                        "object key must be a string, got " + Value.typeName(key)
                                + " at line " + line);
            }
            Value v = obj.fields().get(str.s());
            return v == null ? Value.ofNull() : v;
        }
        if (target instanceof Value.StrVal str) {
            if (!(key instanceof Value.NumVal num)) {
                throw new ScriptRuntimeException(
                        "string index must be a number at line " + line);
            }
            int idx = (int) num.n();
            if (idx < 0 || idx >= str.s().length()) {
                throw new ScriptRuntimeException(
                        "string index out of bounds: " + idx + " at line " + line);
            }
            return Value.of(String.valueOf(str.s().charAt(idx)));
        }
        throw new ScriptRuntimeException("cannot index into " + Value.typeName(target)
                + " at line " + line);
    }

    // ---- calls -------------------------------------------------------

    private Value evalCall(AstNode.Call call, Environment env, RuntimeContext ctx) {
        List<Value> args = new ArrayList<>(call.arguments().size());
        for (AstNode a : call.arguments()) {
            args.add(eval(a, env, ctx));
        }
        Object callee = env.lookup(call.callee());
        if (callee instanceof FunctionValue fn) {
            return invokeUserFunction(fn, args, ctx, call.line());
        }
        // The validator guarantees only allow-listed builtins reach here.
        return invokeBuiltin(call.callee(), args, ctx, call.line());
    }

    private Value invokeUserFunction(FunctionValue fn, List<Value> args,
                                     RuntimeContext ctx, int callLine) {
        AstNode.FunctionDecl decl = fn.decl;
        if (args.size() != decl.params().size()) {
            throw new ScriptRuntimeException("function '" + decl.name() + "' expects "
                    + decl.params().size() + " arguments but got " + args.size()
                    + " at line " + callLine);
        }
        ctx.enterFunction(decl.name());
        try {
            Environment local = new Environment(fn.closure);
            for (int i = 0; i < decl.params().size(); i++) {
                local.define(decl.params().get(i), args.get(i));
            }
            try {
                for (AstNode s : decl.body()) {
                    execute(s, local, ctx);
                }
            } catch (ReturnSignal signal) {
                return signal.value;
            }
            return Value.ofNull();
        } finally {
            ctx.leaveFunction();
        }
    }

    private Value invokeBuiltin(String name, List<Value> args,
                                RuntimeContext ctx, int line) {
        switch (name) {
            case "abs":
                requireArity(name, args, 1, line);
                return Value.of(Math.abs(asBuiltinNumber(args.get(0), name, line)));
            case "floor":
                requireArity(name, args, 1, line);
                return Value.of(Math.floor(asBuiltinNumber(args.get(0), name, line)));
            case "ceil":
                requireArity(name, args, 1, line);
                return Value.of(Math.ceil(asBuiltinNumber(args.get(0), name, line)));
            case "round":
                requireArity(name, args, 1, line);
                return Value.of(Math.rint(asBuiltinNumber(args.get(0), name, line)));
            case "min": {
                requireArityAtLeast(name, args, 1, line);
                double best = Double.POSITIVE_INFINITY;
                for (Value a : args) {
                    best = Math.min(best, asBuiltinNumber(a, name, line));
                }
                return Value.of(best);
            }
            case "max": {
                requireArityAtLeast(name, args, 1, line);
                double best = Double.NEGATIVE_INFINITY;
                for (Value a : args) {
                    best = Math.max(best, asBuiltinNumber(a, name, line));
                }
                return Value.of(best);
            }
            case "pow":
                requireArity(name, args, 2, line);
                return Value.of(Math.pow(asBuiltinNumber(args.get(0), name, line),
                        asBuiltinNumber(args.get(1), name, line)));
            case "sqrt":
                requireArity(name, args, 1, line);
                return Value.of(Math.sqrt(asBuiltinNumber(args.get(0), name, line)));
            case "str":
                requireArity(name, args, 1, line);
                return charged(Value.of(stringify(args.get(0))), ctx);
            case "num":
                requireArity(name, args, 1, line);
                return builtinNum(args.get(0), line);
            case "bool":
                requireArity(name, args, 1, line);
                return Value.of(Value.truthy(args.get(0)));
            case "len":
                requireArity(name, args, 1, line);
                return builtinLen(args.get(0), line);
            case "type":
                requireArity(name, args, 1, line);
                return Value.of(Value.typeName(args.get(0)));
            case "contains":
                requireArity(name, args, 2, line);
                return builtinContains(args.get(0), args.get(1), line);
            case "startsWith":
                requireArity(name, args, 2, line);
                return Value.of(asString(args.get(0), name, line)
                        .startsWith(asString(args.get(1), name, line)));
            case "endsWith":
                requireArity(name, args, 2, line);
                return Value.of(asString(args.get(0), name, line)
                        .endsWith(asString(args.get(1), name, line)));
            case "lower":
                requireArity(name, args, 1, line);
                return charged(Value.of(asString(args.get(0), name, line).toLowerCase()), ctx);
            case "upper":
                requireArity(name, args, 1, line);
                return charged(Value.of(asString(args.get(0), name, line).toUpperCase()), ctx);
            case "trim":
                requireArity(name, args, 1, line);
                return charged(Value.of(asString(args.get(0), name, line).trim()), ctx);
            case "substring": {
                String s = asString(args.get(0), name, line);
                int start = (int) asBuiltinNumber(args.get(1), name, line);
                int end = args.size() == 3
                        ? (int) asBuiltinNumber(args.get(2), name, line) : s.length();
                if (start < 0 || end < start || end > s.length()) {
                    throw new ScriptRuntimeException(
                            "substring bounds out of range at line " + line);
                }
                return charged(Value.of(s.substring(start, end)), ctx);
            }
            case "split": {
                requireArity(name, args, 2, line);
                String s = asString(args.get(0), name, line);
                String sep = asString(args.get(1), name, line);
                if (sep.isEmpty()) {
                    throw new ScriptRuntimeException("split separator must not be empty");
                }
                List<Value> parts = new ArrayList<>();
                for (String p : s.split(java.util.regex.Pattern.quote(sep), -1)) {
                    parts.add(Value.of(p));
                }
                return charged(new Value.ArrVal(parts), ctx);
            }
            case "join": {
                requireArity(name, args, 2, line);
                Value arr = args.get(0);
                String sep = asString(args.get(1), name, line);
                if (!(arr instanceof Value.ArrVal av)) {
                    throw new ScriptRuntimeException("join expects an array at line " + line);
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < av.items().size(); i++) {
                    if (i > 0) {
                        sb.append(sep);
                    }
                    sb.append(stringify(av.items().get(i)));
                }
                return charged(Value.of(sb.toString()), ctx);
            }
            case "keys":
            case "values": {
                requireArity(name, args, 1, line);
                if (!(args.get(0) instanceof Value.ObjVal obj)) {
                    throw new ScriptRuntimeException(
                            name + " expects an object at line " + line);
                }
                List<Value> out = new ArrayList<>();
                boolean wantKeys = "keys".equals(name);
                for (java.util.Map.Entry<String, Value> e : obj.fields().entrySet()) {
                    out.add(wantKeys ? Value.of(e.getKey()) : e.getValue());
                }
                return charged(new Value.ArrVal(out), ctx);
            }
            case "range": {
                List<Value> out;
                if (args.size() == 1) {
                    int end = (int) asBuiltinNumber(args.get(0), name, line);
                    out = new ArrayList<>(Math.max(0, end));
                    for (int i = 0; i < end; i++) {
                        out.add(Value.of(i));
                    }
                } else if (args.size() == 2) {
                    int start = (int) asBuiltinNumber(args.get(0), name, line);
                    int end = (int) asBuiltinNumber(args.get(1), name, line);
                    out = new ArrayList<>(Math.max(0, end - start));
                    for (int i = start; i < end; i++) {
                        out.add(Value.of(i));
                    }
                } else {
                    throw new ScriptRuntimeException(
                            "range expects 1 or 2 arguments at line " + line);
                }
                return charged(new Value.ArrVal(out), ctx);
            }
            case "random":
                requireArity(name, args, 0, line);
                return Value.of(ctx.nextRandom());
            case "randInt":
                requireArity(name, args, 1, line);
                return Value.of(ctx.nextRandomInt((int) asBuiltinNumber(args.get(0), name, line)));
            default:
                throw new ScriptRuntimeException(
                        "function '" + name + "' is not available in the sandbox"
                                + " at line " + line);
        }
    }

    private static Value charged(Value v, RuntimeContext ctx) {
        ctx.chargeMemory(v.estimatedBytes());
        return v;
    }

    private static Value builtinNum(Value v, int line) {
        if (v instanceof Value.NumVal n) {
            return n;
        }
        if (v instanceof Value.StrVal s) {
            try {
                return Value.of(Double.parseDouble(s.s().trim()));
            } catch (NumberFormatException e) {
                throw new ScriptRuntimeException(
                        "cannot convert '" + s.s() + "' to number at line " + line);
            }
        }
        if (v instanceof Value.BoolVal b) {
            return Value.of(b.b() ? 1.0d : 0.0d);
        }
        throw new ScriptRuntimeException(
                "num() expects a string, number or boolean at line " + line);
    }

    private static Value builtinLen(Value v, int line) {
        if (v instanceof Value.StrVal s) {
            return Value.of(s.s().length());
        }
        if (v instanceof Value.ArrVal a) {
            return Value.of(a.items().size());
        }
        if (v instanceof Value.ObjVal o) {
            return Value.of(o.fields().size());
        }
        throw new ScriptRuntimeException(
                "len() expects a string, array or object at line " + line);
    }

    private static Value builtinContains(Value container, Value needle, int line) {
        if (container instanceof Value.StrVal s) {
            if (!(needle instanceof Value.StrVal n)) {
                throw new ScriptRuntimeException(
                        "contains() on a string needs a string argument at line " + line);
            }
            return Value.of(s.s().contains(n.s()));
        }
        if (container instanceof Value.ArrVal a) {
            for (Value item : a.items()) {
                if (Value.valueEquals(item, needle)) {
                    return Value.of(true);
                }
            }
            return Value.of(false);
        }
        if (container instanceof Value.ObjVal o) {
            if (!(needle instanceof Value.StrVal key)) {
                throw new ScriptRuntimeException(
                        "contains() on an object needs a string key at line " + line);
            }
            return Value.of(o.fields().containsKey(key.s()));
        }
        throw new ScriptRuntimeException(
                "contains() expects a string, array or object at line " + line);
    }

    private static double asBuiltinNumber(Value v, String name, int line) {
        if (v instanceof Value.NumVal n) {
            return n.n();
        }
        throw new ScriptRuntimeException(
                name + "() expects a number, got " + Value.typeName(v) + " at line " + line);
    }

    private static String asString(Value v, String name, int line) {
        if (v instanceof Value.StrVal s) {
            return s.s();
        }
        throw new ScriptRuntimeException(
                name + "() expects a string, got " + Value.typeName(v) + " at line " + line);
    }

    private static void requireArity(String name, List<Value> args, int expected, int line) {
        if (args.size() != expected) {
            throw new ScriptRuntimeException(name + "() expects " + expected
                    + " arguments but got " + args.size() + " at line " + line);
        }
    }

    private static void requireArityAtLeast(String name, List<Value> args,
                                            int minimum, int line) {
        if (args.size() < minimum) {
            throw new ScriptRuntimeException(name + "() expects at least " + minimum
                    + " arguments but got " + args.size() + " at line " + line);
        }
    }

    // ---- rendering ---------------------------------------------------

    /** Deterministic, seed-independent textual rendering used by {@code +} and {@code log}. */
    static String stringify(Value v) {
        if (v instanceof Value.NullVal) {
            return "null";
        }
        if (v instanceof Value.BoolVal b) {
            return Boolean.toString(b.b());
        }
        if (v instanceof Value.NumVal n) {
            double d = n.n();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        if (v instanceof Value.StrVal s) {
            return s.s();
        }
        if (v instanceof Value.ArrVal a) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.items().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                Value item = a.items().get(i);
                sb.append(item instanceof Value.StrVal ? "\"" + ((Value.StrVal) item).s() + "\""
                        : stringify(item));
            }
            sb.append(']');
            return sb.toString();
        }
        if (v instanceof Value.ObjVal o) {
            StringBuilder sb = new StringBuilder("{");
            int i = 0;
            for (java.util.Map.Entry<String, Value> e : o.fields().entrySet()) {
                if (i++ > 0) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append(": ");
                Value val = e.getValue();
                sb.append(val instanceof Value.StrVal
                        ? "\"" + ((Value.StrVal) val).s() + "\"" : stringify(val));
            }
            sb.append('}');
            return sb.toString();
        }
        return String.valueOf(v);
    }

    /** Result of a top-level evaluation: the returned value plus captured output. */
    public record EvalOutcome(Value result, String output) {
    }
}
