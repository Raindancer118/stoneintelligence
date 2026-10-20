package de.tstieh.stoneintelligence.platform.scim;

import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import de.tstieh.stoneintelligence.platform.scim.ScimFilter.Compare;
import de.tstieh.stoneintelligence.platform.scim.ScimFilter.Expression;
import de.tstieh.stoneintelligence.platform.scim.ScimFilter.ValuePath;

/**
 * Uebersetzt einen {@link ScimFilter} in eine SQL-Bedingung mit benannten Parametern, damit auch
 * {@code userName eq "x"} in einem Verzeichnis mit 100.000 Konten ein Indexzugriff bleibt.
 */
final class ScimSql {

    /** {@code caseExact == false}: verglichen wird {@code lower(sql)}, passend zu den Indizes. */
    record Column(String sql, boolean caseExact, boolean bool) {

        static Column text(String sql, boolean caseExact) {
            return new Column(sql, caseExact, false);
        }

        static Column bool(String sql) {
            return new Column(sql, true, true);
        }
    }

    private final Map<String, Column> columns;
    private final BiFunction<ValuePath, ScimSql, String> valuePaths;
    private final Map<String, Object> params;

    ScimSql(Map<String, Column> columns, BiFunction<ValuePath, ScimSql, String> valuePaths, Map<String, Object> params) {
        this.columns = columns;
        this.valuePaths = valuePaths;
        this.params = params;
    }

    String where(Expression expression) {
        return switch (expression) {
            case ScimFilter.And and -> "(" + where(and.left()) + " AND " + where(and.right()) + ")";
            case ScimFilter.Or or -> "(" + where(or.left()) + " OR " + where(or.right()) + ")";
            case ScimFilter.Not not -> "NOT (" + where(not.inner()) + ")";
            case ScimFilter.Present present -> {
                var column = column(present.attribute());
                yield column.bool() ? column.sql() + " IS NOT NULL" : "(" + column.sql() + " IS NOT NULL AND " + column.sql() + " <> '')";
            }
            case Compare compare -> compare(compare);
            case ValuePath path -> {
                var sql = valuePaths.apply(path, this);
                if (sql == null) {
                    throw ScimException.invalidFilter("filtering on " + path.attribute() + "[...] is not supported");
                }
                yield sql;
            }
        };
    }

    /** Fuer Wertpfade: ein weiterer Parameter mit eindeutigem Namen. */
    String bind(Object value) {
        var name = "p" + params.size();
        params.put(name, value);
        return ":" + name;
    }

    private Column column(String attribute) {
        var column = columns.get(attribute.toLowerCase(Locale.ROOT));
        if (column == null) {
            throw ScimException.invalidFilter("filtering on '" + attribute + "' is not supported");
        }
        return column;
    }

    private String compare(Compare compare) {
        var column = column(compare.attribute());
        var value = compare.value();
        if (value == null) {
            return switch (compare.op()) {
                case EQ -> column.sql() + " IS NULL";
                case NE -> column.sql() + " IS NOT NULL";
                default -> throw ScimException.invalidFilter(compare.op() + " needs a value");
            };
        }
        if (column.bool()) {
            if (!(value instanceof Boolean)) {
                throw ScimException.invalidFilter(compare.attribute() + " is a boolean");
            }
            return switch (compare.op()) {
                case EQ -> column.sql() + " = " + bind(value);
                case NE -> column.sql() + " <> " + bind(value);
                default -> throw ScimException.invalidFilter(compare.op() + " is not defined for booleans");
            };
        }
        var text = String.valueOf(value);
        var left = column.caseExact() ? column.sql() : "lower(" + column.sql() + ")";
        var right = column.caseExact() ? text : text.toLowerCase(Locale.ROOT);
        return switch (compare.op()) {
            case EQ -> left + " = " + bind(right);
            case NE -> "(" + left + " IS NULL OR " + left + " <> " + bind(right) + ")";
            case CO -> left + " LIKE " + bind("%" + escapeLike(right) + "%") + " ESCAPE '\\'";
            case SW -> left + " LIKE " + bind(escapeLike(right) + "%") + " ESCAPE '\\'";
            case EW -> left + " LIKE " + bind("%" + escapeLike(right)) + " ESCAPE '\\'";
            case GT -> left + " > " + bind(right);
            case GE -> left + " >= " + bind(right);
            case LT -> left + " < " + bind(right);
            case LE -> left + " <= " + bind(right);
        };
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
