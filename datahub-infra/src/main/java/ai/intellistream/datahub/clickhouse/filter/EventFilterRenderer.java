// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.clickhouse.filter;

import ai.intellistream.datahub.filter.CompareOp;
import ai.intellistream.datahub.filter.Expr;
import ai.intellistream.datahub.filter.FilterParseException;
import ai.intellistream.datahub.filter.Predicate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a parsed filter into ClickHouse SQL and its bound parameters.
 *
 * <p>Every character of the output comes from one of four places: a fixed keyword written in this
 * file, a physical column name looked up in {@link EventColumns}, an emitted function name from
 * {@link FunctionRegistry}, or a generated placeholder {@code {p0:Type}}. <b>Nothing the caller
 * typed is ever concatenated into the SQL.</b> Values — including metadata keys and LIKE patterns
 * — go into {@code params}, which the ClickHouse client binds.
 *
 * <p>That is the whole security argument, and it is why the parser's output type is an AST rather
 * than a string: there is no path from caller text to query text for a mistake to travel down.
 */
public final class EventFilterRenderer {

    private static final DateTimeFormatter CH_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Map<String, Object> params;
    private final String source;
    private int sequence;

    /**
     * @param params the query's parameter map, appended to as values are bound
     * @param source the caller's original expression, used only to place error offsets
     */
    public EventFilterRenderer(Map<String, Object> params, String source) {
        this.params = params;
        this.source = source;
    }

    public String render(Predicate node) {
        return switch (node) {
            case Predicate.And and -> join(and.nodes(), " AND ");
            case Predicate.Or or -> join(or.nodes(), " OR ");
            case Predicate.Not not -> "NOT (" + render(not.node()) + ")";
            case Predicate.Comparison c -> comparison(c);
            case Predicate.Like like -> like(like);
            case Predicate.In in -> in(in);
            case Predicate.Between between -> between(between);
            case Predicate.IsNull isNull -> isNull(isNull);
            case Predicate.BooleanValue value -> booleanValue(value);
        };
    }

    // Every composite node parenthesises itself, so precedence is explicit in the output rather
    // than inherited from SQL's defaults. The same discipline is what keeps the dataset ACL from
    // ending up at the mercy of a caller's OR.
    private String join(List<Predicate> nodes, String operator) {
        List<String> parts = new ArrayList<>(nodes.size());
        for (Predicate node : nodes) {
            parts.add(render(node));
        }
        return "(" + String.join(operator, parts) + ")";
    }

    private String comparison(Predicate.Comparison c) {
        ValueType leftType = typeOf(c.left());
        ValueType rightType = typeOf(c.right());
        requireComparable(c.left(), leftType, c.right(), rightType);

        ValueType binding = bindingType(c.left(), leftType, c.right(), rightType);
        return sql(c.left(), binding) + " " + c.op().sql() + " " + sql(c.right(), binding);
    }

    /**
     * Which type a bare literal in this comparison should bind as.
     *
     * <p>A literal takes the type of whatever it is compared with, so {@code eventTime >
     * '2026-01-01'} binds a DateTime64 and {@code id = '0190...'} binds a UUID. That is ordinary
     * SQL literal typing and not the implicit coercion this language refuses: the rule declined
     * above is about a metadata *value*, whose stored type really is text however it is being
     * used.
     */
    private static ValueType bindingType(Expr left, ValueType leftType, Expr right, ValueType rightType) {
        if (isLiteral(left) && !isLiteral(right)) {
            return rightType;
        }
        return leftType;
    }

    private static boolean isLiteral(Expr expr) {
        return expr instanceof Expr.StringLiteral || expr instanceof Expr.NumberLiteral;
    }

    private String like(Predicate.Like like) {
        // LIKE is a string operation on both sides; a converter on either would be meaningless.
        String operator = like.caseInsensitive() ? " ILIKE " : " LIKE ";
        return "(" + sql(like.left(), ValueType.STRING) + operator
                + sql(like.pattern(), ValueType.STRING) + ")";
    }

    private String in(Predicate.In in) {
        ValueType leftType = typeOf(in.left());
        List<String> values = new ArrayList<>(in.values().size());
        for (Expr value : in.values()) {
            requireComparable(in.left(), leftType, value, typeOf(value));
            values.add(sql(value, leftType));
        }
        return "(" + sql(in.left(), leftType) + " IN (" + String.join(", ", values) + "))";
    }

    private String between(Predicate.Between between) {
        ValueType leftType = typeOf(between.left());
        requireComparable(between.left(), leftType, between.low(), typeOf(between.low()));
        requireComparable(between.left(), leftType, between.high(), typeOf(between.high()));
        return "(" + sql(between.left(), leftType) + " BETWEEN " + sql(between.low(), leftType)
                + " AND " + sql(between.high(), leftType) + ")";
    }

    /**
     * {@code IS NULL} on a metadata value asks a question the storage cannot answer literally: a
     * ClickHouse Map returns the value type's default for a missing key, so
     * {@code metadata['nope']} is {@code ''} and never NULL. Read literally the expression is
     * constant-false, which is a silently wrong answer rather than a useful one — so it is
     * rendered as the question the caller meant, "is this key absent".
     */
    private String isNull(Predicate.IsNull isNull) {
        if (isNull.operand() instanceof Expr.MetadataRef metadata) {
            return "(NOT mapContains(metadata, " + bind(metadata.key(), "String") + "))";
        }
        return "(" + sql(isNull.operand(), typeOf(isNull.operand())) + " IS NULL)";
    }

    /**
     * A boolean-valued operand used as a condition on its own.
     *
     * <p>Guarded by type rather than accepted as truthiness: {@code type} on its own is a column,
     * not a filter, and reading a non-empty string as "true" would silently turn a mistake into a
     * query that matches everything.
     */
    private String booleanValue(Predicate.BooleanValue value) {
        ValueType type = typeOf(value.operand());
        if (type != ValueType.BOOLEAN) {
            throw new FilterParseException(
                    "This is " + describe(type) + ", not a condition. Compare it with something, "
                            + "or use a function that answers yes or no such as has_key(...).",
                    0, 0);
        }
        return "(" + sql(value.operand(), ValueType.BOOLEAN) + ")";
    }

    // ---------------------------------------------------------------- type checking

    /**
     * A bare metadata value is a String, and this language will not quietly pretend otherwise.
     *
     * <p>Inferring the conversion from the other operand would mean one side of a comparison
     * silently changing how the other is read. Requiring the converter costs the caller six
     * characters and makes the expression say what it does — and the rejection names the converter
     * to use, so the error teaches the language rather than just refusing it.
     */
    private void requireComparable(Expr left, ValueType leftType, Expr right, ValueType rightType) {
        if (leftType == rightType) {
            return;
        }
        Expr rawMetadata = left instanceof Expr.MetadataRef ? left
                : right instanceof Expr.MetadataRef ? right : null;
        if (rawMetadata == null && (isLiteral(left) || isLiteral(right))) {
            // A literal adapts to the column or function beside it; see bindingType.
            return;
        }
        if (rawMetadata != null) {
            ValueType other = rawMetadata == left ? rightType : leftType;
            String converter = converterFor(other);
            String key = ((Expr.MetadataRef) rawMetadata).key();
            String wrong = "metadata['" + key + "']";
            String right2 = converter + "(metadata['" + key + "'])";
            int offset = Suggestions.locate(source, wrong);
            throw new FilterParseException(
                    "Metadata values are text, so metadata['" + key + "'] cannot be compared with "
                            + describe(other) + ". Wrap it: " + right2 + ".",
                    Math.max(offset, 0), wrong.length(), converter,
                    offset < 0 ? null : Suggestions.splice(source, offset, wrong.length(), right2),
                    "Every metadata value is stored as text; converters are to_int, to_number, "
                            + "to_bool, to_date and to_timestamp (or the ::type shorthand).");
        }
        // A UUID column accepts a string literal: it binds as UUID rather than as text.
        if (leftType == ValueType.UUID && right instanceof Expr.StringLiteral) {
            return;
        }
        if (rightType == ValueType.UUID && left instanceof Expr.StringLiteral) {
            return;
        }
        throw new FilterParseException(
                describe(leftType) + " cannot be compared with " + describe(rightType) + ".",
                0, 0, null, null,
                "Compare like with like, or convert one side with to_int, to_number, to_bool, "
                        + "to_date or to_timestamp.");
    }

    private static String converterFor(ValueType type) {
        return switch (type) {
            case NUMBER -> "to_number";
            case DATETIME -> "to_timestamp";
            case BOOLEAN -> "to_bool";
            case STRING, UUID -> "to_int";
        };
    }

    private static String describe(ValueType type) {
        return switch (type) {
            case STRING -> "text";
            case NUMBER -> "a number";
            case DATETIME -> "a date or timestamp";
            case BOOLEAN -> "a boolean";
            case UUID -> "an id";
        };
    }

    private ValueType typeOf(Expr expr) {
        return switch (expr) {
            case Expr.ColumnRef column -> EventColumns.find(column.name())
                    .orElseThrow(() -> Suggestions.unknownColumn(source, column.name())).type();
            case Expr.MetadataRef ignored -> ValueType.STRING;
            case Expr.StringLiteral ignored -> ValueType.STRING;
            case Expr.NumberLiteral ignored -> ValueType.NUMBER;
            case Expr.BooleanLiteral ignored -> ValueType.BOOLEAN;
            case Expr.FunctionCall call -> FunctionRegistry.find(call.name())
                    .orElseThrow(() -> Suggestions.unknownFunction(source, call.name())).returns();
            case Expr.Cast cast -> FunctionRegistry
                    .converterForCast(cast.typeName())
                    .flatMap(FunctionRegistry::find)
                    .orElseThrow(() -> unknownCastTarget(cast.typeName()))
                    .returns();
        };
    }

    private FilterParseException unknownCastTarget(String typeName) {
        int offset = Suggestions.locate(source, typeName);
        return new FilterParseException("Unknown type '" + typeName + "' in a :: cast.",
                Math.max(offset, 0), typeName.length(), null, null,
                "Cast targets are " + String.join(", ", FunctionRegistry.castTargets()) + ".");
    }

    // ---------------------------------------------------------------- SQL emission

    /**
     * @param binding the type a bare literal here should bind as, taken from the operand it is
     *                being compared with — which is how {@code eventTime > '2026-01-01'} binds a
     *                DateTime64 rather than a string
     */
    private String sql(Expr expr, ValueType binding) {
        return switch (expr) {
            case Expr.ColumnRef column -> EventColumns.find(column.name())
                    .orElseThrow(() -> Suggestions.unknownColumn(source, column.name()))
                    .physicalName();
            case Expr.MetadataRef metadata -> "metadata[" + bind(metadata.key(), "String") + "]";
            case Expr.StringLiteral literal -> bindLiteral(literal.value(), binding);
            case Expr.NumberLiteral literal -> bindNumber(literal.value(), binding);
            case Expr.BooleanLiteral literal -> bind(literal.value() ? 1 : 0, "UInt8");
            case Expr.FunctionCall call -> function(call.name(), call.args());
            case Expr.Cast cast -> function(
                    FunctionRegistry.converterForCast(cast.typeName())
                            .orElseThrow(() -> unknownCastTarget(cast.typeName())),
                    List.of(cast.operand()));
        };
    }

    private String bindLiteral(String value, ValueType binding) {
        return switch (binding) {
            case DATETIME -> bind(toClickHouseDateTime(value), "DateTime64(3)");
            case UUID -> bind(value, "UUID");
            case NUMBER -> {
                try {
                    yield bind(new BigDecimal(value), "Float64");
                } catch (NumberFormatException e) {
                    int offset = Suggestions.locate(source, value);
                    throw new FilterParseException("'" + value + "' is not a number.",
                            Math.max(offset, 0), value.length(), null, null,
                            "Compare against a number without quotes, or against text with quotes.");
                }
            }
            case STRING, BOOLEAN -> bind(value, "String");
        };
    }

    private String bindNumber(BigDecimal value, ValueType binding) {
        if (binding == ValueType.NUMBER && value.scale() <= 0) {
            return bind(value.longValueExact(), "Int64");
        }
        return bind(value, "Float64");
    }

    private String function(String name, List<Expr> args) {
        FunctionRegistry.Fn fn = FunctionRegistry.find(name)
                .orElseThrow(() -> Suggestions.unknownFunction(source, name));
        if (args.size() < fn.minArgs() || args.size() > fn.maxArgs()) {
            throw new FilterParseException(fn.name() + " takes "
                    + (fn.minArgs() == fn.maxArgs() ? String.valueOf(fn.minArgs())
                    : fn.minArgs() + " to " + fn.maxArgs())
                    + " argument(s), but got " + args.size() + ".",
                    Math.max(Suggestions.locate(source, name), 0), name.length());
        }
        return switch (fn.name()) {
            case "to_timestamp" -> convert(args.getFirst(), "parseDateTimeBestEffortOrNull",
                    ValueType.DATETIME);
            case "to_date" -> "toDate(" + convert(args.getFirst(),
                    "parseDateTimeBestEffortOrNull", ValueType.DATETIME) + ")";
            case "to_number" -> convert(args.getFirst(), "toFloat64OrNull", ValueType.NUMBER);
            case "to_int" -> convert(args.getFirst(), "toInt64OrNull", ValueType.NUMBER);
            case "to_bool" -> toBool(args.getFirst());
            case "date_part" -> datePart(args);
            case "has_key" -> "mapContains(metadata, " + sql(args.getFirst(), ValueType.STRING) + ")";
            case "lower" -> "lower(" + sql(args.getFirst(), ValueType.STRING) + ")";
            case "upper" -> "upper(" + sql(args.getFirst(), ValueType.STRING) + ")";
            case "length" -> "length(" + sql(args.getFirst(), ValueType.STRING) + ")";
            case "now" -> "now64(3)";
            default -> throw new IllegalStateException("registry entry with no emitter: " + fn.name());
        };
    }

    /**
     * Applies a converter, folding it away when its argument is a literal.
     *
     * <p>{@code to_timestamp('2026-01-01 12:30')} has a constant answer, so it is computed here
     * and bound as a parameter: no function call reaches ClickHouse, and a date that will not
     * parse is a 400 pointing at the offending text instead of a ClickHouse exception arriving as
     * a 500. Over a column the call has to be emitted, and always in its {@code …OrNull} form.
     */
    private String convert(Expr argument, String clickHouseFunction, ValueType produces) {
        if (argument instanceof Expr.StringLiteral literal) {
            return bindLiteral(literal.value(), produces);
        }
        if (argument instanceof Expr.NumberLiteral literal) {
            return bindNumber(literal.value(), produces);
        }
        return clickHouseFunction + "(" + sql(argument, ValueType.STRING) + ")";
    }

    /**
     * Spelled out rather than delegated to a ClickHouse cast, so the accepted spellings are ours:
     * documented, testable, and the same on every server version.
     */
    private String toBool(Expr argument) {
        if (argument instanceof Expr.BooleanLiteral literal) {
            return bind(literal.value() ? 1 : 0, "UInt8");
        }
        if (argument instanceof Expr.StringLiteral literal) {
            Boolean parsed = parseBool(literal.value());
            if (parsed == null) {
                throw new FilterParseException("'" + literal.value() + "' is not a boolean.",
                        Math.max(Suggestions.locate(source, literal.value()), 0),
                        literal.value().length(), null, null,
                        "Accepted: true/false, t/f, yes/no, y/n, on/off, 1/0.");
            }
            return bind(parsed ? 1 : 0, "UInt8");
        }
        String value = sql(argument, ValueType.STRING);
        return "multiIf(lower(" + value + ") IN ('true','t','yes','y','1','on'), 1, "
                + "lower(" + value + ") IN ('false','f','no','n','0','off'), 0, NULL)";
    }

    private static Boolean parseBool(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (List.of("true", "t", "yes", "y", "1", "on").contains(lower)) {
            return Boolean.TRUE;
        }
        if (List.of("false", "f", "no", "n", "0", "off").contains(lower)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** The part name is validated against a closed set, never passed through as text. */
    private String datePart(List<Expr> args) {
        if (!(args.getFirst() instanceof Expr.StringLiteral part)) {
            throw new FilterParseException(
                    "date_part's first argument must be one of "
                            + String.join(", ", FunctionRegistry.DATE_PARTS) + ", in quotes.", 0, 0);
        }
        String name = part.value().toLowerCase(Locale.ROOT);
        if (!FunctionRegistry.DATE_PARTS.contains(name)) {
            int offset = Suggestions.locate(source, part.value());
            throw new FilterParseException("date_part cannot extract '" + part.value() + "'.",
                    Math.max(offset, 0), part.value().length(), null, null,
                    "Available parts: " + String.join(", ", FunctionRegistry.DATE_PARTS) + ".");
        }
        String inner = sql(args.get(1), ValueType.DATETIME);
        return switch (name) {
            case "year" -> "toYear(" + inner + ")";
            case "month" -> "toMonth(" + inner + ")";
            default -> "toDayOfMonth(" + inner + ")";
        };
    }

    // ---------------------------------------------------------------- parameters

    /** Placeholder names are generated, never derived from anything the caller wrote. */
    private String bind(Object value, String clickHouseType) {
        String name = "fp" + sequence++;
        params.put(name, value);
        return "{" + name + ":" + clickHouseType + "}";
    }

    /**
     * Parses a date literal here rather than letting ClickHouse do it, so the failure is a 400
     * naming the text instead of a query that dies at execution.
     */
    private String toClickHouseDateTime(String value) {
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZoneOffset.UTC).format(CH_DT);
        } catch (DateTimeParseException ignored) {
            // not an offset form; try the rest
        }
        try {
            return ZonedDateTime.parse(text).withZoneSameInstant(ZoneOffset.UTC).format(CH_DT);
        } catch (DateTimeParseException ignored) {
            // keep trying
        }
        for (DateTimeFormatter format : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))) {
            try {
                return LocalDateTime.parse(text, format).atZone(ZoneOffset.UTC).format(CH_DT);
            } catch (DateTimeParseException ignored) {
                // next
            }
        }
        try {
            return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).format(CH_DT);
        } catch (DateTimeParseException ignored) {
            // out of options
        }
        int offset = Suggestions.locate(source, text);
        throw new FilterParseException("'" + value + "' is not a date or timestamp.",
                Math.max(offset, 0), text.length(), null, null,
                "Accepted: 2026-01-01, 2026-01-01 12:30, 2026-01-01T12:30:00Z.");
    }
}
