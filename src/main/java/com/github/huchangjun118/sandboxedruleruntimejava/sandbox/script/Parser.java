package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ScriptCompileException;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive descent parser for the rule language.
 *
 * <p>Grammar sketch (newlines are not significant; statement terminators are
 * required at top level and inside blocks):</p>
 * <pre>
 * program     := (functionDecl | statement)*
 * functionDecl:= 'function' IDENT '(' params? ')' block
 * statement   := block | varStmt | ifStmt | whileStmt | forStmt
 *              | returnStmt | logStmt | exprStmt
 * expression  := or
 * or          := and ('||' and)*
 * and         := equality ('&&' equality)*
 * equality    := comparison (('==' | '!=') comparison)*
 * comparison  := additive (('&lt;' | '&lt;=' | '&gt;' | '&gt;=') additive)*
 * additive    := multiplicative (('+' | '-') multiplicative)*
 * multiplicative := unary (('*' | '/' | '%') unary)*
 * unary       := ('!' | '-') unary | postfix
 * postfix     := primary ('.' IDENT | '[' expression ']' | '(' args? ')')*
 * primary     := NUMBER | STRING | 'true' | 'false' | 'null'
 *              | '(' expression ')' | arrayLit | objectLit | IDENT
 * </pre>
 *
 * <p>Calls only bind to bare identifiers, which lets the sandbox policy scan
 * classify every call and every member-access root statically.</p>
 */
public final class Parser {

    private final List<Token> tokens;
    private int index;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static AstNode.Program parse(String source) throws ScriptCompileException {
        return new Parser(Lexer.tokenize(source)).parseProgram();
    }

    private AstNode.Program parseProgram() {
        List<AstNode> body = new ArrayList<>();
        while (!atEnd()) {
            body.add(parseTopLevel());
        }
        return new AstNode.Program(body);
    }

    private AstNode parseTopLevel() {
        if (matchKeyword("function")) {
            return parseFunctionDecl();
        }
        return parseStatement();
    }

    private AstNode.FunctionDecl parseFunctionDecl() {
        int line = previous().line();
        Token name = expect(Token.TokenType.IDENT, "expected function name");
        expect(Token.TokenType.LPAREN, "expected '(' after function name");
        List<String> params = new ArrayList<>();
        if (!check(Token.TokenType.RPAREN)) {
            do {
                params.add(expect(Token.TokenType.IDENT, "expected parameter name").text());
            } while (match(Token.TokenType.COMMA));
        }
        expect(Token.TokenType.RPAREN, "expected ')' after parameters");
        List<AstNode> body = parseBlockBody();
        return new AstNode.FunctionDecl(name.text(), params, body, line);
    }

    private List<AstNode> parseBlockBody() {
        expect(Token.TokenType.LBRACE, "expected '{'");
        List<AstNode> statements = new ArrayList<>();
        while (!check(Token.TokenType.RBRACE) && !atEnd()) {
            statements.add(parseStatement());
        }
        expect(Token.TokenType.RBRACE, "expected '}'");
        return statements;
    }

    private AstNode parseStatement() {
        Token t = current();
        if (t.type() == Token.TokenType.LBRACE) {
            int line = advance().line();
            List<AstNode> statements = new ArrayList<>();
            while (!check(Token.TokenType.RBRACE) && !atEnd()) {
                statements.add(parseStatement());
            }
            expect(Token.TokenType.RBRACE, "expected '}'");
            return new AstNode.Block(statements, line);
        }
        if (t.type() == Token.TokenType.KEYWORD) {
            return switch (t.text()) {
                case "var" -> parseVarStmt();
                case "if" -> parseIfStmt();
                case "while" -> parseWhileStmt();
                case "for" -> parseForStmt();
                case "return" -> parseReturnStmt();
                case "log" -> parseLogStmt();
                default -> throw error("unexpected keyword '" + t.text() + "'", t.line());
            };
        }
        return parseExpressionStatement();
    }

    private AstNode parseVarStmt() {
        int line = advance().line(); // var
        Token name = expect(Token.TokenType.IDENT, "expected variable name after 'var'");
        AstNode init = null;
        if (match(Token.TokenType.ASSIGN)) {
            init = parseExpression();
        }
        consumeTerminator();
        return new AstNode.VarStmt(name.text(), init, line);
    }

    private AstNode parseIfStmt() {
        int line = advance().line(); // if
        expect(Token.TokenType.LPAREN, "expected '(' after 'if'");
        AstNode condition = parseExpression();
        expect(Token.TokenType.RPAREN, "expected ')' after if condition");
        AstNode thenBranch = parseStatement();
        AstNode elseBranch = null;
        if (matchKeyword("else")) {
            elseBranch = parseStatement();
        }
        return new AstNode.IfStmt(condition, thenBranch, elseBranch, line);
    }

    private AstNode parseWhileStmt() {
        int line = advance().line(); // while
        expect(Token.TokenType.LPAREN, "expected '(' after 'while'");
        AstNode condition = parseExpression();
        expect(Token.TokenType.RPAREN, "expected ')' after while condition");
        AstNode body = parseStatement();
        return new AstNode.WhileStmt(condition, body, line);
    }

    private AstNode parseForStmt() {
        int line = advance().line(); // for
        expect(Token.TokenType.LPAREN, "expected '(' after 'for'");
        Token variable = expect(Token.TokenType.IDENT, "expected loop variable");
        if (!matchKeyword("in")) {
            throw error("expected 'in' in for statement", line);
        }
        AstNode iterable = parseExpression();
        expect(Token.TokenType.RPAREN, "expected ')' after for clauses");
        AstNode body = parseStatement();
        return new AstNode.ForStmt(variable.text(), iterable, body, line);
    }

    private AstNode parseReturnStmt() {
        int line = advance().line(); // return
        AstNode value = null;
        if (startsExpression()) {
            value = parseExpression();
        }
        consumeTerminator();
        return new AstNode.ReturnStmt(value, line);
    }

    private AstNode parseLogStmt() {
        int line = advance().line(); // log
        AstNode value = parseExpression();
        consumeTerminator();
        return new AstNode.LogStmt(value, line);
    }

    private AstNode parseExpressionStatement() {
        int line = current().line();
        AstNode expr = parseExpression();
        consumeTerminator();
        return new AstNode.ExprStmt(expr, line);
    }

    private void consumeTerminator() {
        if (match(Token.TokenType.SEMI)) {
            return;
        }
        if (check(Token.TokenType.RBRACE) || atEnd()) {
            return;
        }
        throw error("expected ';' after statement", current().line());
    }

    private boolean startsExpression() {
        if (check(Token.TokenType.RBRACE) || check(Token.TokenType.SEMI) || atEnd()) {
            return false;
        }
        return true;
    }

    // ---- expressions -------------------------------------------------

    private AstNode parseExpression() {
        AstNode expr = parseOr();
        // Assignment only to a bare variable: keeps target tracking trivial.
        if (match(Token.TokenType.ASSIGN)) {
            int line = previous().line();
            if (!(expr instanceof AstNode.VarRef ref)) {
                throw error("invalid assignment target", line);
            }
            AstNode value = parseExpression();
            return new AstNode.Assign(ref.name(), value, line);
        }
        return expr;
    }

    private AstNode parseOr() {
        AstNode left = parseAnd();
        while (match(Token.TokenType.OR)) {
            int line = previous().line();
            AstNode right = parseAnd();
            left = new AstNode.Logical("||", left, right, line);
        }
        return left;
    }

    private AstNode parseAnd() {
        AstNode left = parseEquality();
        while (match(Token.TokenType.AND)) {
            int line = previous().line();
            AstNode right = parseEquality();
            left = new AstNode.Logical("&&", left, right, line);
        }
        return left;
    }

    private AstNode parseEquality() {
        AstNode left = parseComparison();
        while (check(Token.TokenType.EQ) || check(Token.TokenType.NE)) {
            Token op = advance();
            AstNode right = parseComparison();
            left = new AstNode.Binary(op.text(), left, right, op.line());
        }
        return left;
    }

    private AstNode parseComparison() {
        AstNode left = parseAdditive();
        while (check(Token.TokenType.LT) || check(Token.TokenType.LE)
                || check(Token.TokenType.GT) || check(Token.TokenType.GE)) {
            Token op = advance();
            AstNode right = parseAdditive();
            left = new AstNode.Binary(op.text(), left, right, op.line());
        }
        return left;
    }

    private AstNode parseAdditive() {
        AstNode left = parseMultiplicative();
        while (check(Token.TokenType.PLUS) || check(Token.TokenType.MINUS)) {
            Token op = advance();
            AstNode right = parseMultiplicative();
            left = new AstNode.Binary(op.text(), left, right, op.line());
        }
        return left;
    }

    private AstNode parseMultiplicative() {
        AstNode left = parseUnary();
        while (check(Token.TokenType.STAR) || check(Token.TokenType.SLASH)
                || check(Token.TokenType.PERCENT)) {
            Token op = advance();
            AstNode right = parseUnary();
            left = new AstNode.Binary(op.text(), left, right, op.line());
        }
        return left;
    }

    private AstNode parseUnary() {
        if (check(Token.TokenType.NOT) || check(Token.TokenType.MINUS)) {
            Token op = advance();
            AstNode operand = parseUnary();
            return new AstNode.Unary(op.text(), operand, op.line());
        }
        return parsePostfix();
    }

    private AstNode parsePostfix() {
        AstNode node = parsePrimary();
        while (true) {
            if (match(Token.TokenType.DOT)) {
                int line = previous().line();
                Token field = expect(Token.TokenType.IDENT, "expected field name after '.'");
                if (check(Token.TokenType.LPAREN)) {
                    advance(); // (
                    List<AstNode> args = new ArrayList<>();
                    if (!check(Token.TokenType.RPAREN)) {
                        do {
                            args.add(parseExpression());
                        } while (match(Token.TokenType.COMMA));
                    }
                    expect(Token.TokenType.RPAREN, "expected ')' after arguments");
                    node = new AstNode.MethodCall(node, field.text(), args, line);
                } else {
                    node = new AstNode.Member(node, field.text(), line);
                }
            } else if (match(Token.TokenType.LBRACKET)) {
                int line = previous().line();
                AstNode index = parseExpression();
                expect(Token.TokenType.RBRACKET, "expected ']' after index");
                node = new AstNode.Index(node, index, line);
            } else if (check(Token.TokenType.LPAREN)) {
                if (!(node instanceof AstNode.VarRef ref)) {
                    throw error("only named functions can be called", current().line());
                }
                int line = advance().line(); // (
                List<AstNode> args = new ArrayList<>();
                if (!check(Token.TokenType.RPAREN)) {
                    do {
                        args.add(parseExpression());
                    } while (match(Token.TokenType.COMMA));
                }
                expect(Token.TokenType.RPAREN, "expected ')' after arguments");
                node = new AstNode.Call(ref.name(), args, line);
            } else {
                return node;
            }
        }
    }

    private AstNode parsePrimary() {
        Token t = current();
        switch (t.type()) {
            case NUMBER -> {
                advance();
                double value;
                try {
                    value = Double.parseDouble(t.text());
                } catch (NumberFormatException e) {
                    throw error("invalid number literal '" + t.text() + "'", t.line());
                }
                return new AstNode.NumberLit(value, t.line());
            }
            case STRING -> {
                advance();
                return new AstNode.StringLit(t.text(), t.line());
            }
            case IDENT -> {
                advance();
                return new AstNode.VarRef(t.text(), t.line());
            }
            case LPAREN -> {
                advance();
                AstNode expr = parseExpression();
                expect(Token.TokenType.RPAREN, "expected ')' after expression");
                return expr;
            }
            case LBRACKET -> {
                return parseArrayLiteral();
            }
            case LBRACE -> {
                return parseObjectLiteral();
            }
            case KEYWORD -> {
                advance();
                return switch (t.text()) {
                    case "true" -> new AstNode.BoolLit(true, t.line());
                    case "false" -> new AstNode.BoolLit(false, t.line());
                    case "null" -> new AstNode.NullLit(t.line());
                    default -> throw error("unexpected keyword '" + t.text() + "'", t.line());
                };
            }
            default -> throw error("expected expression but found '" + t.text() + "'", t.line());
        }
    }

    private AstNode parseArrayLiteral() {
        int line = advance().line(); // [
        List<AstNode> elements = new ArrayList<>();
        if (!check(Token.TokenType.RBRACKET)) {
            do {
                elements.add(parseExpression());
            } while (match(Token.TokenType.COMMA));
        }
        expect(Token.TokenType.RBRACKET, "expected ']' after array elements");
        return new AstNode.ArrayLit(elements, line);
    }

    private AstNode parseObjectLiteral() {
        int line = advance().line(); // {
        List<AstNode.Property> properties = new ArrayList<>();
        if (!check(Token.TokenType.RBRACE)) {
            do {
                Token keyToken = current();
                String key;
                if (match(Token.TokenType.IDENT) || match(Token.TokenType.KEYWORD)) {
                    key = previous().text();
                } else if (match(Token.TokenType.STRING)) {
                    key = previous().text();
                } else {
                    throw error("expected property name", keyToken.line());
                }
                expect(Token.TokenType.COLON, "expected ':' after property name");
                AstNode value = parseExpression();
                properties.add(new AstNode.Property(key, value));
            } while (match(Token.TokenType.COMMA));
        }
        expect(Token.TokenType.RBRACE, "expected '}' after object literal");
        return new AstNode.ObjectLit(properties, line);
    }

    // ---- token helpers ----------------------------------------------

    private Token current() {
        return tokens.get(index);
    }

    private Token previous() {
        return tokens.get(index - 1);
    }

    private boolean atEnd() {
        return current().type() == Token.TokenType.EOF;
    }

    private boolean check(Token.TokenType type) {
        return current().type() == type;
    }

    private boolean match(Token.TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private boolean matchKeyword(String keyword) {
        Token t = current();
        if (t.type() == Token.TokenType.KEYWORD && t.text().equals(keyword)) {
            advance();
            return true;
        }
        return false;
    }

    private Token advance() {
        Token t = tokens.get(index);
        if (!atEnd()) {
            index++;
        }
        return t;
    }

    private Token expect(Token.TokenType type, String message) {
        if (check(type)) {
            return advance();
        }
        throw error(message + " but found '" + current().text() + "'", current().line());
    }

    private ScriptCompileException error(String message, int line) {
        return new ScriptCompileException(message, line);
    }
}
