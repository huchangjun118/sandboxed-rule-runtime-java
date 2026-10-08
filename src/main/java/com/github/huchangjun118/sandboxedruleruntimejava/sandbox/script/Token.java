package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script;

/**
 * A single lexical token.
 */
public record Token(TokenType type, String text, int line) {

    public enum TokenType {
        NUMBER, STRING, IDENT, KEYWORD,
        LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
        COMMA, SEMI, COLON, DOT,
        ASSIGN, PLUS, MINUS, STAR, SLASH, PERCENT,
        EQ, NE, LT, LE, GT, GE, AND, OR, NOT,
        EOF
    }
}
