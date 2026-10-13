package com.docextract.validation.expr;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A tiny, safe expression language for business rules over extracted fields, e.g.
 * <pre>
 *   sum(lineItems[].amount) == subtotal
 *   subtotal + tax == total
 *   total &gt;= 0 &amp;&amp; count(lineItems[]) &gt; 0
 *   dueDate &gt;= invoiceDate
 * </pre>
 * Grammar (lowest precedence first):
 * <pre>
 *   expr    := and ('||' and)*
 *   and     := not ('&amp;&amp;' not)*
 *   not     := '!' not | cmp
 *   cmp     := add (('=='|'!='|'&lt;'|'&lt;='|'&gt;'|'&gt;=') add)?
 *   add     := mul (('+'|'-') mul)*
 *   mul     := unary (('*'|'/') unary)*
 *   unary   := '-' unary | primary
 *   primary := number | string | true | false | null | path | func '(' args ')' | '(' expr ')'
 *   path    := ident ('.' ident | '[]' | '[' int ']')*
 * </pre>
 * Functions: sum, count, min, max, avg (over a list), abs, round(x[, places]), exists(path).
 * Numbers are BigDecimal; {@code ==}/{@code !=} on numbers allow a {@value #TOLERANCE_TEXT} tolerance (money rounding).
 * Strings compare with {@code ==}/{@code !=}, and with {@code <}/{@code >} lexicographically, which orders ISO dates.
 * If a referenced value is absent, evaluation throws {@link MissingValueException} and the rule is skipped.
 */
public final class RuleExpression {

    static final String TOLERANCE_TEXT = "0.01";
    static final BigDecimal TOLERANCE = new BigDecimal(TOLERANCE_TEXT);

    private final String source;
    private final Node root;
    private final Set<String> referencedFields;

    private RuleExpression(String source, Node root, Set<String> referencedFields) {
        this.source = source;
        this.root = root;
        this.referencedFields = referencedFields;
    }

    public static RuleExpression parse(String source) {
        Parser p = new Parser(source);
        Node root = p.expr();
        p.expectEnd();
        return new RuleExpression(source.trim(), root, p.fields);
    }

    public String source() {
        return source;
    }

    /** Top-level field names the expression reads, in order of first appearance. */
    public List<String> referencedFields() {
        return List.copyOf(referencedFields);
    }

    /** @return true if the rule holds; throws {@link MissingValueException} when a value it needs is absent. */
    public boolean test(JsonNode fields) {
        Object v = root.eval(fields);
        if (!(v instanceof Boolean b)) {
            throw new RuleSyntaxException("rule does not produce true/false: " + source);
        }
        return b;
    }

    /** Evaluates and returns the raw value (number, string, boolean, list); used for diagnostics. */
    public Object evaluate(JsonNode fields) {
        return root.eval(fields);
    }

    /** "left=140.00, right=150.00" for a top-level comparison, to explain a failure. */
    public String explain(JsonNode fields) {
        if (root instanceof Binary b && b.op.isComparison()) {
            return "left=" + show(safeEval(b.left, fields)) + ", right=" + show(safeEval(b.right, fields));
        }
        return "";
    }

    private static Object safeEval(Node n, JsonNode fields) {
        try {
            return n.eval(fields);
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static String show(Object v) {
        return v instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : String.valueOf(v);
    }

    @Override
    public String toString() {
        return source;
    }

    // ---------------------------------------------------------------- exceptions

    public static class RuleSyntaxException extends RuntimeException {
        public RuleSyntaxException(String message) {
            super(message);
        }
    }

    public static class MissingValueException extends RuntimeException {
        public MissingValueException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- AST

    private interface Node {
        Object eval(JsonNode fields);
    }

    private record Literal(Object value) implements Node {
        public Object eval(JsonNode fields) {
            return value;
        }
    }

    /** A path segment: a property name, {@code []} (every element), or {@code [n]} (one element). */
    private record Segment(String name, boolean all, int index) {
    }

    private record Path(String text, List<Segment> segments) implements Node {
        boolean isList() {
            return segments.stream().anyMatch(Segment::all);
        }

        public Object eval(JsonNode fields) {
            List<JsonNode> current = List.of(fields);
            for (Segment s : segments) {
                List<JsonNode> next = new ArrayList<>();
                for (JsonNode n : current) {
                    if (s.name() != null) {
                        JsonNode child = n.path(s.name());
                        if (!child.isMissingNode() && !child.isNull()) {
                            next.add(child);
                        }
                    } else if (s.all()) {
                        if (n.isArray()) {
                            n.forEach(next::add);
                        }
                    } else if (n.isArray() && s.index() < n.size() && !n.get(s.index()).isNull()) {
                        next.add(n.get(s.index()));
                    }
                }
                current = next;
            }
            if (isList()) {
                // A list path needs its container present; an empty array is a real (empty) list.
                if (current.isEmpty() && !containerPresent(fields)) {
                    throw new MissingValueException(text + " is absent");
                }
                return current.stream().map(Path::toValue).toList();
            }
            if (current.isEmpty()) {
                throw new MissingValueException(text + " is absent");
            }
            return toValue(current.getFirst());
        }

        private boolean containerPresent(JsonNode fields) {
            JsonNode top = fields.path(segments.getFirst().name());
            return top.isArray();
        }

        static Object toValue(JsonNode n) {
            if (n.isNumber()) {
                return n.decimalValue();
            }
            if (n.isBoolean()) {
                return n.booleanValue();
            }
            if (n.isTextual()) {
                return n.asText();
            }
            return n;
        }
    }

    private enum Op {
        OR("||"), AND("&&"), EQ("=="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="),
        ADD("+"), SUB("-"), MUL("*"), DIV("/");

        final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }

        boolean isComparison() {
            return this == EQ || this == NE || this == LT || this == LE || this == GT || this == GE;
        }
    }

    private record Binary(Op op, Node left, Node right) implements Node {
        public Object eval(JsonNode fields) {
            if (op == Op.AND) {
                return bool(left.eval(fields)) && bool(right.eval(fields));
            }
            if (op == Op.OR) {
                return bool(left.eval(fields)) || bool(right.eval(fields));
            }
            Object l = left.eval(fields);
            Object r = right.eval(fields);
            return switch (op) {
                case ADD -> num(l).add(num(r));
                case SUB -> num(l).subtract(num(r));
                case MUL -> num(l).multiply(num(r));
                case DIV -> {
                    if (num(r).signum() == 0) {
                        throw new MissingValueException("division by zero");
                    }
                    yield num(l).divide(num(r), MathContext.DECIMAL64);
                }
                case EQ -> equal(l, r);
                case NE -> !equal(l, r);
                case LT -> compare(l, r) < 0;
                case LE -> compare(l, r) <= 0;
                case GT -> compare(l, r) > 0;
                case GE -> compare(l, r) >= 0;
                default -> throw new IllegalStateException(op.name());
            };
        }

        private static boolean equal(Object l, Object r) {
            if (l instanceof BigDecimal a && r instanceof BigDecimal b) {
                return a.subtract(b).abs().compareTo(TOLERANCE) <= 0;
            }
            if (l instanceof String a && r instanceof String b) {
                return a.trim().equalsIgnoreCase(b.trim());
            }
            return Objects.equals(l, r);
        }

        @SuppressWarnings("unchecked")
        private static int compare(Object l, Object r) {
            if (l instanceof BigDecimal a && r instanceof BigDecimal b) {
                return a.compareTo(b);
            }
            if (l instanceof String a && r instanceof String b) {
                return a.compareTo(b);
            }
            throw new RuleSyntaxException("cannot order " + typeName(l) + " and " + typeName(r));
        }
    }

    private record Not(Node inner) implements Node {
        public Object eval(JsonNode fields) {
            return !bool(inner.eval(fields));
        }
    }

    private record Negate(Node inner) implements Node {
        public Object eval(JsonNode fields) {
            return num(inner.eval(fields)).negate();
        }
    }

    private record Call(String name, List<Node> args) implements Node {
        public Object eval(JsonNode fields) {
            if (name.equals("exists")) {
                try {
                    Object v = args.getFirst().eval(fields);
                    return !(v instanceof List<?> l) || !l.isEmpty();
                } catch (MissingValueException e) {
                    return false;
                }
            }
            List<Object> values = args.stream().map(a -> a.eval(fields)).toList();
            return switch (name) {
                case "sum" -> numbers(values.getFirst()).stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                case "count" -> BigDecimal.valueOf(list(values.getFirst()).size());
                case "min" -> numbers(values.getFirst()).stream().min(BigDecimal::compareTo)
                        .orElseThrow(() -> new MissingValueException("min of empty list"));
                case "max" -> numbers(values.getFirst()).stream().max(BigDecimal::compareTo)
                        .orElseThrow(() -> new MissingValueException("max of empty list"));
                case "avg" -> {
                    List<BigDecimal> ns = numbers(values.getFirst());
                    if (ns.isEmpty()) {
                        throw new MissingValueException("avg of empty list");
                    }
                    yield ns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(ns.size()), MathContext.DECIMAL64);
                }
                case "abs" -> num(values.getFirst()).abs();
                case "round" -> num(values.getFirst()).setScale(
                        values.size() > 1 ? num(values.get(1)).intValueExact() : 0, RoundingMode.HALF_UP);
                default -> throw new IllegalStateException(name);
            };
        }
    }

    static final Set<String> FUNCTIONS = Set.of("sum", "count", "min", "max", "avg", "abs", "round", "exists");

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        throw new RuleSyntaxException("expected true/false but got " + typeName(v));
    }

    private static BigDecimal num(Object v) {
        if (v instanceof BigDecimal d) {
            return d;
        }
        throw new RuleSyntaxException("expected a number but got " + typeName(v));
    }

    private static List<?> list(Object v) {
        if (v instanceof List<?> l) {
            return l;
        }
        throw new RuleSyntaxException("expected a list (use [] in the path, e.g. items[].amount) but got " + typeName(v));
    }

    private static List<BigDecimal> numbers(Object v) {
        return list(v).stream().map(RuleExpression::num).toList();
    }

    private static String typeName(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof BigDecimal) {
            return "number";
        }
        if (v instanceof String) {
            return "string";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        return v instanceof List<?> ? "list" : "object";
    }

    // ---------------------------------------------------------------- parser

    private static final class Parser {
        private final String src;
        private int pos;
        final Set<String> fields = new LinkedHashSet<>();

        Parser(String src) {
            if (src == null || src.isBlank()) {
                throw new RuleSyntaxException("empty rule");
            }
            this.src = src;
        }

        Node expr() {
            Node n = and();
            while (eat("||")) {
                n = new Binary(Op.OR, n, and());
            }
            return n;
        }

        Node and() {
            Node n = not();
            while (eat("&&")) {
                n = new Binary(Op.AND, n, not());
            }
            return n;
        }

        Node not() {
            skipWs();
            if (peek("!") && !peek("!=")) {
                pos++;
                return new Not(not());
            }
            return cmp();
        }

        Node cmp() {
            Node left = add();
            for (Op op : new Op[]{Op.EQ, Op.NE, Op.LE, Op.GE, Op.LT, Op.GT}) {
                if (eat(op.symbol)) {
                    return new Binary(op, left, add());
                }
            }
            return left;
        }

        Node add() {
            Node n = mul();
            while (true) {
                if (eat("+")) {
                    n = new Binary(Op.ADD, n, mul());
                } else if (eat("-")) {
                    n = new Binary(Op.SUB, n, mul());
                } else {
                    return n;
                }
            }
        }

        Node mul() {
            Node n = unary();
            while (true) {
                if (eat("*")) {
                    n = new Binary(Op.MUL, n, unary());
                } else if (eat("/")) {
                    n = new Binary(Op.DIV, n, unary());
                } else {
                    return n;
                }
            }
        }

        Node unary() {
            if (eat("-")) {
                return new Negate(unary());
            }
            return primary();
        }

        Node primary() {
            skipWs();
            if (pos >= src.length()) {
                throw error("unexpected end of rule");
            }
            char c = src.charAt(pos);
            if (eat("(")) {
                Node n = expr();
                expect(")");
                return n;
            }
            if (Character.isDigit(c) || (c == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1)))) {
                int start = pos;
                while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                    pos++;
                }
                try {
                    return new Literal(new BigDecimal(src.substring(start, pos)));
                } catch (NumberFormatException e) {
                    throw error("bad number '" + src.substring(start, pos) + "'");
                }
            }
            if (c == '\'' || c == '"') {
                int end = src.indexOf(c, pos + 1);
                if (end < 0) {
                    throw error("unterminated string");
                }
                String s = src.substring(pos + 1, end);
                pos = end + 1;
                return new Literal(s);
            }
            if (Character.isLetter(c) || c == '_') {
                int start = pos;
                String id = ident();
                int afterIdent = pos;
                switch (id) {
                    case "true":
                        return new Literal(true);
                    case "false":
                        return new Literal(false);
                    case "null":
                        return new Literal(null);
                    default:
                        break;
                }
                skipWs();
                if (peek("(")) {
                    if (!FUNCTIONS.contains(id)) {
                        throw error("unknown function '" + id + "' (known: " + String.join(", ", FUNCTIONS.stream().sorted().toList()) + ")");
                    }
                    pos++;
                    List<Node> args = new ArrayList<>();
                    if (!eat(")")) {
                        do {
                            args.add(expr());
                        } while (eat(","));
                        expect(")");
                    }
                    int expected = id.equals("round") ? -1 : 1;
                    if (expected > 0 && args.size() != expected || id.equals("round") && (args.isEmpty() || args.size() > 2)) {
                        throw error(id + "() takes " + (id.equals("round") ? "1 or 2" : "1") + " argument(s)");
                    }
                    if (id.equals("exists") && !(args.getFirst() instanceof Path)) {
                        throw error("exists() takes a field path");
                    }
                    return new Call(id, args);
                }
                pos = afterIdent;
                return path(id, start);
            }
            throw error("unexpected '" + c + "'");
        }

        Node path(String first, int start) {
            List<Segment> segs = new ArrayList<>();
            segs.add(new Segment(first, false, -1));
            fields.add(first);
            while (pos < src.length()) {
                if (src.charAt(pos) == '.' && pos + 1 < src.length() && (Character.isLetter(src.charAt(pos + 1)) || src.charAt(pos + 1) == '_')) {
                    pos++;
                    segs.add(new Segment(ident(), false, -1));
                } else if (src.startsWith("[]", pos)) {
                    pos += 2;
                    segs.add(new Segment(null, true, -1));
                } else if (src.charAt(pos) == '[') {
                    int close = src.indexOf(']', pos);
                    if (close < 0) {
                        throw error("missing ]");
                    }
                    try {
                        segs.add(new Segment(null, false, Integer.parseInt(src.substring(pos + 1, close).trim())));
                    } catch (NumberFormatException e) {
                        throw error("array index must be a whole number");
                    }
                    pos = close + 1;
                } else {
                    break;
                }
            }
            return new Path(src.substring(start, pos), segs);
        }

        String ident() {
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            return src.substring(start, pos);
        }

        void expectEnd() {
            skipWs();
            if (pos < src.length()) {
                throw error("unexpected '" + src.substring(pos) + "'");
            }
        }

        void expect(String s) {
            if (!eat(s)) {
                throw error("expected '" + s + "'");
            }
        }

        boolean eat(String s) {
            skipWs();
            if (src.startsWith(s, pos)) {
                // Do not split a two-character operator: "<" must not eat the start of "<=".
                if ((s.equals("<") || s.equals(">") || s.equals("!")) && src.startsWith("=", pos + 1)) {
                    return false;
                }
                pos += s.length();
                return true;
            }
            return false;
        }

        boolean peek(String s) {
            skipWs();
            return src.startsWith(s, pos);
        }

        void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        RuleSyntaxException error(String what) {
            return new RuleSyntaxException("rule '" + src.trim() + "': " + what + " at position " + pos);
        }
    }
}
