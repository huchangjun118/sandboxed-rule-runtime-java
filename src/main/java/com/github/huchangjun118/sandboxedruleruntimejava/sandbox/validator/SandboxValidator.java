package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.validator;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.SandboxViolationException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ScriptCompileException;
import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script.AstNode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pre-execution AST policy scan. It runs strictly before the interpreter
 * starts, so every request for a denied capability is rejected before a
 * single script instruction runs.
 *
 * <p>The interpreter exposes no host capability at all (its only reachable
 * types are the closed {@code Value} universe plus an allow-listed builtin
 * set), so this scan is both the required explicit rejection point and
 * defence in depth.</p>
 *
 * <p>Rejections use distinct reason codes:</p>
 * <ul>
 *   <li>{@code SANDBOX_FILESYSTEM} - file/path/Files style access;</li>
 *   <li>{@code SANDBOX_PROCESS}    - exec/Runtime/process/exit style access;</li>
 *   <li>{@code SANDBOX_NETWORK}    - http/socket/fetch/url style access;</li>
 *   <li>{@code SANDBOX_REFLECTION} - Class/System/Thread/reflection style access;</li>
 *   <li>{@code SANDBOX_DENIED}     - any other capability not present in the sandbox.</li>
 * </ul>
 *
 * <p>The dot/member syntax is the capability-request surface ({@code System.exit},
 * {@code Runtime.exec}, {@code http.get}, ...); those reserved root names are
 * denied regardless of the surrounding code. Index access ({@code a["x"]})
 * needs no check because the value universe carries no method handles.</p>
 */
public final class SandboxValidator {

    /** Pure/builtin functions available to scripts. Everything else is denied. */
    private static final Set<String> ALLOWED_BUILTINS = Set.of(
            // numeric
            "abs", "floor", "ceil", "round", "min", "max", "pow", "sqrt",
            // conversion / inspection
            "str", "num", "bool", "len", "type",
            // string
            "contains", "startsWith", "endsWith", "lower", "upper",
            "trim", "substring", "split", "join",
            // collection
            "keys", "values", "range",
            // deterministic randomness (seeded per execution)
            "random", "randInt"
    );

    private static final Map<String, String> DENIED_CALLS = Map.ofEntries(
            // file system
            Map.entry("readFile", SandboxViolationException.FILESYSTEM),
            Map.entry("writeFile", SandboxViolationException.FILESYSTEM),
            Map.entry("appendFile", SandboxViolationException.FILESYSTEM),
            Map.entry("deleteFile", SandboxViolationException.FILESYSTEM),
            Map.entry("readdir", SandboxViolationException.FILESYSTEM),
            Map.entry("mkdir", SandboxViolationException.FILESYSTEM),
            Map.entry("open", SandboxViolationException.FILESYSTEM),
            Map.entry("fopen", SandboxViolationException.FILESYSTEM),
            // process / runtime control
            Map.entry("exec", SandboxViolationException.PROCESS),
            Map.entry("spawn", SandboxViolationException.PROCESS),
            Map.entry("system", SandboxViolationException.PROCESS),
            Map.entry("exit", SandboxViolationException.PROCESS),
            Map.entry("kill", SandboxViolationException.PROCESS),
            Map.entry("getenv", SandboxViolationException.PROCESS),
            Map.entry("loadLibrary", SandboxViolationException.PROCESS),
            // network
            Map.entry("fetch", SandboxViolationException.NETWORK),
            Map.entry("httpGet", SandboxViolationException.NETWORK),
            Map.entry("httpPost", SandboxViolationException.NETWORK),
            Map.entry("openSocket", SandboxViolationException.NETWORK),
            Map.entry("connect", SandboxViolationException.NETWORK),
            // reflection / dynamic evaluation
            Map.entry("eval", SandboxViolationException.REFLECTION),
            Map.entry("forName", SandboxViolationException.REFLECTION),
            Map.entry("getClass", SandboxViolationException.REFLECTION),
            Map.entry("newInstance", SandboxViolationException.REFLECTION),
            Map.entry("defineClass", SandboxViolationException.REFLECTION),
            Map.entry("loadClass", SandboxViolationException.REFLECTION),
            // generic module/loader surface
            Map.entry("import", SandboxViolationException.DENIED),
            Map.entry("require", SandboxViolationException.DENIED),
            Map.entry("load", SandboxViolationException.DENIED)
    );

    private static final Map<String, String> DENIED_MEMBER_ROOTS = Map.ofEntries(
            // file system
            Map.entry("file", SandboxViolationException.FILESYSTEM),
            Map.entry("files", SandboxViolationException.FILESYSTEM),
            Map.entry("fs", SandboxViolationException.FILESYSTEM),
            Map.entry("io", SandboxViolationException.FILESYSTEM),
            Map.entry("path", SandboxViolationException.FILESYSTEM),
            Map.entry("paths", SandboxViolationException.FILESYSTEM),
            Map.entry("File", SandboxViolationException.FILESYSTEM),
            Map.entry("Files", SandboxViolationException.FILESYSTEM),
            Map.entry("Path", SandboxViolationException.FILESYSTEM),
            Map.entry("Paths", SandboxViolationException.FILESYSTEM),
            // process / runtime control
            Map.entry("process", SandboxViolationException.PROCESS),
            Map.entry("Process", SandboxViolationException.PROCESS),
            Map.entry("ProcessBuilder", SandboxViolationException.PROCESS),
            Map.entry("Runtime", SandboxViolationException.PROCESS),
            Map.entry("runtime", SandboxViolationException.PROCESS),
            // network
            Map.entry("http", SandboxViolationException.NETWORK),
            Map.entry("https", SandboxViolationException.NETWORK),
            Map.entry("net", SandboxViolationException.NETWORK),
            Map.entry("network", SandboxViolationException.NETWORK),
            Map.entry("socket", SandboxViolationException.NETWORK),
            Map.entry("Socket", SandboxViolationException.NETWORK),
            Map.entry("url", SandboxViolationException.NETWORK),
            Map.entry("URL", SandboxViolationException.NETWORK),
            Map.entry("ajax", SandboxViolationException.NETWORK),
            // reflection / runtime internals
            Map.entry("System", SandboxViolationException.REFLECTION),
            Map.entry("Class", SandboxViolationException.REFLECTION),
            Map.entry("Thread", SandboxViolationException.REFLECTION),
            Map.entry("ClassLoader", SandboxViolationException.REFLECTION),
            Map.entry("reflect", SandboxViolationException.REFLECTION),
            Map.entry("Reflect", SandboxViolationException.REFLECTION),
            Map.entry("Method", SandboxViolationException.REFLECTION),
            // generic host escape / module surface
            Map.entry("java", SandboxViolationException.DENIED),
            Map.entry("javax", SandboxViolationException.DENIED),
            Map.entry("jvm", SandboxViolationException.DENIED),
            Map.entry("kotlin", SandboxViolationException.DENIED),
            Map.entry("import", SandboxViolationException.DENIED),
            Map.entry("globalThis", SandboxViolationException.DENIED),
            Map.entry("window", SandboxViolationException.DENIED),
            Map.entry("global", SandboxViolationException.DENIED)
    );

    public void validate(AstNode.Program program) throws SandboxViolationException {
        Set<String> userFunctions = collectUserFunctions(program);
        for (AstNode node : program.body()) {
            if (node instanceof AstNode.FunctionDecl fn) {
                checkFunctionBody(fn, userFunctions);
            } else {
                check(node, userFunctions);
            }
        }
    }

    private Set<String> collectUserFunctions(AstNode.Program program) {
        Set<String> names = new HashSet<>();
        for (AstNode node : program.body()) {
            if (node instanceof AstNode.FunctionDecl fn) {
                if (!names.add(fn.name())) {
                    throw new ScriptCompileException(
                            "duplicate function declaration '" + fn.name() + "'", fn.line());
                }
                Set<String> params = new HashSet<>();
                for (String p : fn.params()) {
                    if (!params.add(p)) {
                        throw new ScriptCompileException(
                                "duplicate parameter '" + p + "' in function '" + fn.name() + "'",
                                fn.line());
                    }
                    if (DENIED_MEMBER_ROOTS.containsKey(p) || DENIED_CALLS.containsKey(p)) {
                        throw new SandboxViolationException(SandboxViolationException.DENIED,
                                "parameter name '" + p + "' is a reserved capability name"
                                        + " at line " + fn.line());
                    }
                }
            }
        }
        return names;
    }

    private void checkFunctionBody(AstNode.FunctionDecl fn, Set<String> userFunctions) {
        for (AstNode stmt : fn.body()) {
            check(stmt, userFunctions);
        }
    }

    private void check(AstNode node, Set<String> userFunctions) {
        if (node == null) {
            return;
        }
        if (node instanceof AstNode.Program p) {
            for (AstNode child : p.body()) {
                check(child, userFunctions);
            }
        } else if (node instanceof AstNode.FunctionDecl fn) {
            // Only top-level functions are registered by the interpreter, so a
            // nested declaration is always dead code - reject it rather than
            // silently letting its body escape the function-name collection.
            throw new ScriptCompileException(
                    "nested function declarations are not supported ('"
                            + fn.name() + "')", fn.line());
        } else if (node instanceof AstNode.Block b) {
            checkBody(b.statements(), userFunctions);
        } else if (node instanceof AstNode.VarStmt s) {
            check(s.initializer(), userFunctions);
        } else if (node instanceof AstNode.IfStmt s) {
            check(s.condition(), userFunctions);
            check(s.thenBranch(), userFunctions);
            check(s.elseBranch(), userFunctions);
        } else if (node instanceof AstNode.WhileStmt s) {
            check(s.condition(), userFunctions);
            check(s.body(), userFunctions);
        } else if (node instanceof AstNode.ForStmt s) {
            check(s.iterable(), userFunctions);
            check(s.body(), userFunctions);
        } else if (node instanceof AstNode.ReturnStmt s) {
            check(s.value(), userFunctions);
        } else if (node instanceof AstNode.LogStmt s) {
            check(s.value(), userFunctions);
        } else if (node instanceof AstNode.ExprStmt s) {
            check(s.expression(), userFunctions);
        } else if (node instanceof AstNode.Call call) {
            checkCall(call, userFunctions);
            for (AstNode arg : call.arguments()) {
                check(arg, userFunctions);
            }
        } else if (node instanceof AstNode.Member m) {
            checkMember(m);
            check(m.object(), userFunctions);
        } else if (node instanceof AstNode.MethodCall mc) {
            checkMethodCall(mc, userFunctions);
        } else if (node instanceof AstNode.Index idx) {
            check(idx.object(), userFunctions);
            check(idx.index(), userFunctions);
        } else if (node instanceof AstNode.Unary u) {
            check(u.operand(), userFunctions);
        } else if (node instanceof AstNode.Binary b) {
            check(b.left(), userFunctions);
            check(b.right(), userFunctions);
        } else if (node instanceof AstNode.Logical l) {
            check(l.left(), userFunctions);
            check(l.right(), userFunctions);
        } else if (node instanceof AstNode.Assign a) {
            check(a.value(), userFunctions);
        } else if (node instanceof AstNode.ArrayLit a) {
            for (AstNode el : a.elements()) {
                check(el, userFunctions);
            }
        } else if (node instanceof AstNode.ObjectLit o) {
            for (AstNode.Property prop : o.properties()) {
                check(prop.value(), userFunctions);
            }
        }
        // literals and variable references need no capability check
    }

    private void checkBody(List<AstNode> body, Set<String> userFunctions) {
        for (AstNode child : body) {
            check(child, userFunctions);
        }
    }

    private void checkCall(AstNode.Call call, Set<String> userFunctions) {
        String name = call.callee();
        String code = DENIED_CALLS.get(name);
        if (code != null) {
            throw new SandboxViolationException(code,
                    capabilityMessage(code, "function '" + name + "(...)' cannot be called")
                            + " at line " + call.line());
        }
        if (ALLOWED_BUILTINS.contains(name) || userFunctions.contains(name)) {
            return;
        }
        throw new SandboxViolationException(SandboxViolationException.DENIED,
                "function '" + name + "(...)' is not available in the sandbox "
                        + "(only allow-listed builtins and script-declared functions)"
                        + " at line " + call.line());
    }

    private void checkMember(AstNode.Member member) {
        String root = memberRoot(member);
        if (root == null) {
            return;
        }
        String code = DENIED_MEMBER_ROOTS.get(root);
        if (code != null) {
            throw new SandboxViolationException(code,
                    capabilityMessage(code,
                            "member access '" + root + "....' is forbidden")
                            + " at line " + member.line());
        }
    }

    private void checkMethodCall(AstNode.MethodCall call, Set<String> userFunctions) {
        // The chain root classifies the capability first (System.exit is a
        // runtime/reflection access, not merely a process control name).
        String root = memberRoot(call.object());
        if (root != null) {
            String byRoot = DENIED_MEMBER_ROOTS.get(root);
            if (byRoot != null) {
                throw new SandboxViolationException(byRoot,
                        capabilityMessage(byRoot,
                                "member call '" + root + "...." + call.method()
                                        + "(...)' is forbidden")
                                + " at line " + call.line());
            }
        }
        // Then a dangerous bare method name (exec/read/getClass/...).
        String byMethod = DENIED_CALLS.get(call.method());
        if (byMethod != null) {
            throw new SandboxViolationException(byMethod,
                    capabilityMessage(byMethod,
                            "method '" + call.method() + "(...)' cannot be called")
                            + " at line " + call.line());
        }
        // No method values exist in the closed value universe: always deny.
        throw new SandboxViolationException(SandboxViolationException.DENIED,
                "method calls on values are not available in the sandbox ('"
                        + call.method() + "(...)' at line " + call.line()
                        + "); use the allow-listed global builtins instead");
    }

    /**
     * Return the leftmost identifier of a dotted chain, e.g. {@code System}
     * for {@code System.out.exit}; null for computed roots such as
     * {@code getX().y} or {@code items[0].y}.
     */
    private String memberRoot(AstNode node) {
        AstNode current = node;
        while (current instanceof AstNode.Member m) {
            current = m.object();
        }
        if (current instanceof AstNode.VarRef ref) {
            return ref.name();
        }
        return null;
    }

    private String capabilityMessage(String code, String detail) {
        return switch (code) {
            case SandboxViolationException.FILESYSTEM ->
                    "file system access is forbidden: " + detail;
            case SandboxViolationException.PROCESS ->
                    "process/runtime control is forbidden: " + detail;
            case SandboxViolationException.NETWORK ->
                    "network access is forbidden: " + detail;
            case SandboxViolationException.REFLECTION ->
                    "reflection/runtime-internals access is forbidden: " + detail;
            default -> detail;
        };
    }
}
