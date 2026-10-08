package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script;

import java.util.List;

/**
 * AST node definitions for the rule language. The node hierarchy is closed;
 * the interpreter only ever walks these nodes and {@link Value} instances,
 * which is what keeps arbitrary Java reachable surface out of the sandbox.
 */
public sealed interface AstNode permits AstNode.Program, AstNode.FunctionDecl, AstNode.Block,
        AstNode.VarStmt, AstNode.IfStmt, AstNode.WhileStmt, AstNode.ForStmt,
        AstNode.ReturnStmt, AstNode.LogStmt, AstNode.ExprStmt,
        AstNode.NumberLit, AstNode.StringLit, AstNode.BoolLit, AstNode.NullLit,
        AstNode.VarRef, AstNode.ArrayLit, AstNode.ObjectLit, AstNode.Unary,
        AstNode.Binary, AstNode.Logical, AstNode.Assign, AstNode.Call,
        AstNode.Member, AstNode.MethodCall, AstNode.Index {

    int line();

    record Program(List<AstNode> body) implements AstNode {
        @Override
        public int line() {
            return 1;
        }
    }

    record FunctionDecl(String name, List<String> params, List<AstNode> body, int line)
            implements AstNode {
    }

    record Block(List<AstNode> statements, int line) implements AstNode {
    }

    record VarStmt(String name, AstNode initializer, int line) implements AstNode {
    }

    record IfStmt(AstNode condition, AstNode thenBranch, AstNode elseBranch, int line)
            implements AstNode {
    }

    record WhileStmt(AstNode condition, AstNode body, int line) implements AstNode {
    }

    record ForStmt(String variable, AstNode iterable, AstNode body, int line)
            implements AstNode {
    }

    record ReturnStmt(AstNode value, int line) implements AstNode {
    }

    record LogStmt(AstNode value, int line) implements AstNode {
    }

    record ExprStmt(AstNode expression, int line) implements AstNode {
    }

    record NumberLit(double value, int line) implements AstNode {
    }

    record StringLit(String value, int line) implements AstNode {
    }

    record BoolLit(boolean value, int line) implements AstNode {
    }

    record NullLit(int line) implements AstNode {
    }

    record VarRef(String name, int line) implements AstNode {
    }

    record ArrayLit(List<AstNode> elements, int line) implements AstNode {
    }

    record ObjectLit(List<Property> properties, int line) implements AstNode {
    }

    record Property(String name, AstNode value) {
    }

    record Unary(String operator, AstNode operand, int line) implements AstNode {
    }

    record Binary(String operator, AstNode left, AstNode right, int line)
            implements AstNode {
    }

    record Logical(String operator, AstNode left, AstNode right, int line)
            implements AstNode {
    }

    record Assign(String name, AstNode value, int line) implements AstNode {
    }

    record Call(String callee, List<AstNode> arguments, int line) implements AstNode {
    }

    /** {@code root.field} - the member syntax is where runtime capabilities would be requested. */
    record Member(AstNode object, String field, int line) implements AstNode {
    }

    /**
     * {@code object.method(args)} - a call hanging off a dotted chain; the
     * static policy scan classifies the chain root and rejects forbidden
     * capabilities, while the interpreter denies it at runtime as well
     * (no method handles exist in the closed value universe).
     */
    record MethodCall(AstNode object, String method, List<AstNode> arguments, int line)
            implements AstNode {
    }

    record Index(AstNode object, AstNode index, int line) implements AstNode {
    }
}
