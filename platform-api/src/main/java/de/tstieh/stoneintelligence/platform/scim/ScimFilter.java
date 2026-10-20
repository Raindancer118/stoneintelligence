package de.tstieh.stoneintelligence.platform.scim;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * SCIM-Filter nach RFC 7644 Abschnitt 3.4.2.2: {@code and}/{@code or}/{@code not}, Klammern, alle
 * Vergleichsoperatoren und Wertpfade wie {@code emails[type eq "work"]}. Attributnamen sind
 * case-insensitiv; Zeichenketten auch, ausser bei {@link #CASE_EXACT} (RFC 7643: id, externalId).
 */
public record ScimFilter(Expression expression) {

    static final Set<String> CASE_EXACT = Set.of("id", "externalid", "value", "$ref");
    private static final String SCHEMA_PREFIX = "urn:ietf:params:scim:schemas:core:2.0:";

    public enum Op {
        EQ, NE, CO, SW, EW, GT, GE, LT, LE
    }

    public sealed interface Expression permits And, Or, Not, Present, Compare, ValuePath {
    }

    public record And(Expression left, Expression right) implements Expression {
    }

    public record Or(Expression left, Expression right) implements Expression {
    }

    public record Not(Expression inner) implements Expression {
    }

    public record Present(String attribute) implements Expression {
    }

    /** {@code value}: String, Boolean, Number oder {@code null}. */
    public record Compare(String attribute, Op op, Object value) implements Expression {
    }

    public record ValuePath(String attribute, Expression filter) implements Expression {
    }

    public static ScimFilter parse(String filter) {
        if (filter == null || filter.isBlank()) {
            throw ScimException.invalidFilter("empty filter");
        }
        var parser = new Parser(filter);
        var expression = parser.or();
        parser.skipSpace();
        if (!parser.atEnd()) {
            throw ScimException.invalidFilter("unexpected input at " + parser.position + ": " + filter);
        }
        return new ScimFilter(expression);
    }

    /** {@code attributes}: Attributname wie in der Ressource (z. B. "userName") -> Wert, Liste oder verschachtelte Map. */
    public boolean matches(Function<String, Object> attributes) {
        return evaluate(expression, attributes);
    }

    /** Ohne URN-Praefix, mit {@code .}-Pfad wie geschrieben ("name.givenName"). */
    static String normalize(String attribute) {
        var lower = attribute.toLowerCase(Locale.ROOT);
        if (lower.startsWith(SCHEMA_PREFIX)) {
            var rest = attribute.substring(SCHEMA_PREFIX.length());
            var colon = rest.indexOf(':');
            return colon < 0 ? rest : rest.substring(colon + 1);
        }
        return attribute;
    }

    private static boolean evaluate(Expression expression, Function<String, Object> attributes) {
        return switch (expression) {
            case And and -> evaluate(and.left(), attributes) && evaluate(and.right(), attributes);
            case Or or -> evaluate(or.left(), attributes) || evaluate(or.right(), attributes);
            case Not not -> !evaluate(not.inner(), attributes);
            case Present present -> isPresent(lookup(attributes, present.attribute()));
            case Compare compare -> values(lookup(attributes, compare.attribute())).stream()
                .anyMatch(value -> compare(value, compare.op(), compare.value(), compare.attribute()))
                || compare.value() == null && compare.op() == Op.EQ && !isPresent(lookup(attributes, compare.attribute()));
            case ValuePath path -> values(lookup(attributes, path.attribute())).stream()
                .filter(Map.class::isInstance)
                .anyMatch(element -> evaluate(path.filter(), name -> lookupIgnoreCase((Map<?, ?>) element, name)));
        };
    }

    private static Object lookup(Function<String, Object> attributes, String path) {
        var parts = path.split("\\.");
        var value = attributes.apply(parts[0]);
        if (value == null) {
            value = caseInsensitive(attributes, parts[0]);
        }
        for (int i = 1; i < parts.length && value != null; i++) {
            value = value instanceof Map<?, ?> map ? lookupIgnoreCase(map, parts[i]) : null;
        }
        return value;
    }

    private static Object caseInsensitive(Function<String, Object> attributes, String name) {
        // Ressourcen werden in Camel-Case geliefert; Filter duerfen jede Schreibweise nutzen.
        for (var known : List.of("id", "externalId", "userName", "displayName", "active", "emails", "members", "name", "title")) {
            if (known.equalsIgnoreCase(name)) {
                return attributes.apply(known);
            }
        }
        return null;
    }

    private static Object lookupIgnoreCase(Map<?, ?> map, String name) {
        for (var entry : map.entrySet()) {
            if (String.valueOf(entry.getKey()).equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isPresent(Object value) {
        return value != null && !(value instanceof String s && s.isEmpty())
            && !(value instanceof Collection<?> c && c.isEmpty());
    }

    private static Collection<?> values(Object value) {
        if (value == null) {
            return List.of();
        }
        return value instanceof Collection<?> collection ? collection : List.of(value);
    }

    private static boolean compare(Object actual, Op op, Object expected, String attribute) {
        if (expected == null) {
            return switch (op) {
                case EQ -> actual == null;
                case NE -> actual != null;
                default -> throw ScimException.invalidFilter(op + " needs a value");
            };
        }
        if (actual instanceof Boolean || expected instanceof Boolean) {
            var equal = Objects.equals(String.valueOf(actual).toLowerCase(Locale.ROOT), String.valueOf(expected).toLowerCase(Locale.ROOT));
            return switch (op) {
                case EQ -> equal;
                case NE -> !equal;
                default -> throw ScimException.invalidFilter(op + " is not defined for booleans");
            };
        }
        var leaf = attribute.substring(attribute.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        var exact = CASE_EXACT.contains(leaf);
        var a = exact ? String.valueOf(actual) : String.valueOf(actual).toLowerCase(Locale.ROOT);
        var b = exact ? String.valueOf(expected) : String.valueOf(expected).toLowerCase(Locale.ROOT);
        return switch (op) {
            case EQ -> a.equals(b);
            case NE -> !a.equals(b);
            case CO -> a.contains(b);
            case SW -> a.startsWith(b);
            case EW -> a.endsWith(b);
            case GT -> a.compareTo(b) > 0;
            case GE -> a.compareTo(b) >= 0;
            case LT -> a.compareTo(b) < 0;
            case LE -> a.compareTo(b) <= 0;
        };
    }

    /** Rekursiver Abstieg; Vorrang: not/Klammern vor and vor or. */
    private static final class Parser {

        private final String input;
        private int position;

        Parser(String input) {
            this.input = input;
        }

        Expression or() {
            var left = and();
            while (keyword("or")) {
                left = new Or(left, and());
            }
            return left;
        }

        Expression and() {
            var left = unary();
            while (keyword("and")) {
                left = new And(left, unary());
            }
            return left;
        }

        Expression unary() {
            skipSpace();
            if (keyword("not")) {
                skipSpace();
                expect('(');
                var inner = or();
                expect(')');
                return new Not(inner);
            }
            if (peek() == '(') {
                position++;
                var inner = or();
                expect(')');
                return inner;
            }
            var attribute = attributePath();
            if (peek() == '[') {
                position++;
                var inner = or();
                expect(']');
                return new ValuePath(attribute, inner);
            }
            var operator = word();
            if (operator.equalsIgnoreCase("pr")) {
                return new Present(attribute);
            }
            Op op;
            try {
                op = Op.valueOf(operator.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw ScimException.invalidFilter("unknown operator '" + operator + "'");
            }
            return new Compare(attribute, op, value());
        }

        private String attributePath() {
            skipSpace();
            var start = position;
            while (!atEnd() && (Character.isLetterOrDigit(peek()) || ":._$-".indexOf(peek()) >= 0)) {
                position++;
            }
            if (start == position) {
                throw ScimException.invalidFilter("attribute expected at " + start);
            }
            return normalize(input.substring(start, position));
        }

        private Object value() {
            skipSpace();
            if (atEnd()) {
                throw ScimException.invalidFilter("value expected");
            }
            if (peek() == '"') {
                return string();
            }
            var token = word();
            return switch (token.toLowerCase(Locale.ROOT)) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                default -> {
                    try {
                        yield new java.math.BigDecimal(token);
                    } catch (NumberFormatException notANumber) {
                        throw ScimException.invalidFilter("invalid value '" + token + "'");
                    }
                }
            };
        }

        private String string() {
            position++;
            var result = new StringBuilder();
            while (!atEnd()) {
                var c = input.charAt(position++);
                if (c == '"') {
                    return result.toString();
                }
                if (c == '\\' && !atEnd()) {
                    var escaped = input.charAt(position++);
                    result.append(switch (escaped) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> escaped;
                    });
                } else {
                    result.append(c);
                }
            }
            throw ScimException.invalidFilter("unterminated string");
        }

        private String word() {
            skipSpace();
            var start = position;
            while (!atEnd() && !Character.isWhitespace(peek()) && "()[]".indexOf(peek()) < 0) {
                position++;
            }
            if (start == position) {
                throw ScimException.invalidFilter("operator expected at " + start);
            }
            return input.substring(start, position);
        }

        private boolean keyword(String keyword) {
            skipSpace();
            var end = position + keyword.length();
            if (end <= input.length() && input.regionMatches(true, position, keyword, 0, keyword.length())
                    && (end == input.length() || Character.isWhitespace(input.charAt(end)) || input.charAt(end) == '(')) {
                position = end;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            skipSpace();
            if (peek() != c) {
                throw ScimException.invalidFilter("'" + c + "' expected at " + position);
            }
            position++;
        }

        void skipSpace() {
            while (!atEnd() && Character.isWhitespace(peek())) {
                position++;
            }
        }

        char peek() {
            return atEnd() ? '\0' : input.charAt(position);
        }

        boolean atEnd() {
            return position >= input.length();
        }
    }
}
