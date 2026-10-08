package com.github.huchangjun118.sandboxedruleruntimejava.sandbox.script;

import com.github.huchangjun118.sandboxedruleruntimejava.sandbox.ScriptCompileException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits rule source text into {@link Token}s.
 *
 * <p>Numbers are integer or decimal literals; strings use double quotes with
 * a small escape set; identifiers are {@code [A-Za-z_][A-Za-z0-9_]*}. The
 * lexer deliberately recognizes nothing that could address host capabilities
 * - there is no import/class/eval syntax in this language.</p>
 */
public final class Lexer {

    private static final Set<String> KEYWORDS = Set.of(
            "true", "false", "null",
            "var", "if", "else", "while", "for", "in",
            "function", "return", "log");

    private final String src;
    private final List<Token> tokens = new ArrayList<>();
    private int pos;
    private int line = 1;

    private Lexer(String src) {
        this.src = src;
    }

    public static List<Token> tokenize(String source) {
        if (source == null) {
            throw new ScriptCompileException("source is null", 1);
        }
        return new Lexer(source).scan();
    }

    private List<Token> scan() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n') {
                line++;
                pos++;
            } else if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '/' && peek(1) == '/') {
                skipLineComment();
            } else if (c == '/' && peek(1) == '*') {
                skipBlockComment();
            } else if (c == '"') {
                readString();
            } else if (Character.isDigit(c)) {
                readNumber();
            } else if (isIdentStart(c)) {
                readIdentifier();
            } else {
                readSymbol();
            }
        }
        tokens.add(new Token(Token.TokenType.EOF, "", line));
        return tokens;
    }

    private char peek(int ahead) {
        int idx = pos + ahead;
        return idx < src.length() ? src.charAt(idx) : '\0';
    }

    private void skipLineComment() {
        pos += 2;
        while (pos < src.length() && src.charAt(pos) != '\n') {
            pos++;
        }
    }

    private void skipBlockComment() {
        int startLine = line;
        pos += 2;
        while (pos < src.length()) {
            if (src.charAt(pos) == '*' && peek(1) == '/') {
                pos += 2;
                return;
            }
            if (src.charAt(pos) == '\n') {
                line++;
            }
            pos++;
        }
        throw new ScriptCompileException("unterminated block comment", startLine);
    }

    private void readString() {
        int startLine = line;
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '"') {
                pos++;
                tokens.add(new Token(Token.TokenType.STRING, sb.toString(), startLine));
                return;
            }
            if (c == '\n') {
                throw new ScriptCompileException("unterminated string literal", startLine);
            }
            if (c == '\\') {
                pos++;
                if (pos >= src.length()) {
                    break;
                }
                char e = src.charAt(pos);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> throw new ScriptCompileException(
                            "invalid string escape \\" + e, startLine);
                }
            } else {
                sb.append(c);
            }
            pos++;
        }
        throw new ScriptCompileException("unterminated string literal", startLine);
    }

    private void readNumber() {
        int start = pos;
        int startLine = line;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
            pos++;
        }
        if (pos < src.length() && src.charAt(pos) == '.'
                && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1))) {
            pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
        }
        String text = src.substring(start, pos);
        tokens.add(new Token(Token.TokenType.NUMBER, text, startLine));
    }

    private void readIdentifier() {
        int start = pos;
        int startLine = line;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        String text = src.substring(start, pos);
        Token.TokenType type = KEYWORDS.contains(text)
                ? Token.TokenType.KEYWORD : Token.TokenType.IDENT;
        tokens.add(new Token(type, text, startLine));
    }

    private void readSymbol() {
        int startLine = line;
        char c = src.charAt(pos);
        switch (c) {
            case '(' -> add(Token.TokenType.LPAREN, "(");
            case ')' -> add(Token.TokenType.RPAREN, ")");
            case '{' -> add(Token.TokenType.LBRACE, "{");
            case '}' -> add(Token.TokenType.RBRACE, "}");
            case '[' -> add(Token.TokenType.LBRACKET, "[");
            case ']' -> add(Token.TokenType.RBRACKET, "]");
            case ',' -> add(Token.TokenType.COMMA, ",");
            case ';' -> add(Token.TokenType.SEMI, ";");
            case ':' -> add(Token.TokenType.COLON, ":");
            case '.' -> add(Token.TokenType.DOT, ".");
            case '+' -> add(Token.TokenType.PLUS, "+");
            case '-' -> add(Token.TokenType.MINUS, "-");
            case '*' -> add(Token.TokenType.STAR, "*");
            case '/' -> add(Token.TokenType.SLASH, "/");
            case '%' -> add(Token.TokenType.PERCENT, "%");
            case '=' -> {
                if (peek(1) == '=') {
                    add2(Token.TokenType.EQ, "==");
                } else {
                    add(Token.TokenType.ASSIGN, "=");
                }
            }
            case '!' -> {
                if (peek(1) == '=') {
                    add2(Token.TokenType.NE, "!=");
                } else {
                    add(Token.TokenType.NOT, "!");
                }
            }
            case '<' -> {
                if (peek(1) == '=') {
                    add2(Token.TokenType.LE, "<=");
                } else {
                    add(Token.TokenType.LT, "<");
                }
            }
            case '>' -> {
                if (peek(1) == '=') {
                    add2(Token.TokenType.GE, ">=");
                } else {
                    add(Token.TokenType.GT, ">");
                }
            }
            case '&' -> {
                if (peek(1) == '&') {
                    add2(Token.TokenType.AND, "&&");
                } else {
                    throw new ScriptCompileException("unexpected character '&'", startLine);
                }
            }
            case '|' -> {
                if (peek(1) == '|') {
                    add2(Token.TokenType.OR, "||");
                } else {
                    throw new ScriptCompileException("unexpected character '|'", startLine);
                }
            }
            default -> throw new ScriptCompileException(
                    "unexpected character '" + c + "'", startLine);
        }
    }

    private void add(Token.TokenType type, String text) {
        tokens.add(new Token(type, text, line));
        pos++;
    }

    private void add2(Token.TokenType type, String text) {
        tokens.add(new Token(type, text, line));
        pos += 2;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
