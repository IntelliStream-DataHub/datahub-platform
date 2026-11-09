// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.definition;

import ai.intellistream.datahub.flow.api.Item;
import ai.intellistream.datahub.flow.api.ProcessException;
import ai.intellistream.datahub.flow.api.PropertyValue;
import ai.intellistream.datahub.flow.definition.Template.Reference;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A property's value as configured, at whatever stage of resolution it has reached: as written
 * (when a flow is saved), with its run-level references resolved (for the run), and with its item
 * references resolved too (per item).
 *
 * <p>The value is one of: null (not set), {@link String}, {@link BigDecimal}, {@link Boolean},
 * {@link Duration}, {@link Instant}, a {@link List} of strings, or a {@link Template} with
 * references still to resolve — on its own or as a list element.
 */
public final class ConfiguredValue implements PropertyValue {

    private final String property;
    private final Object value;
    private final Function<Item, Function<Reference, Object>> forItem;

    private ConfiguredValue(String property, Object value,
                            Function<Item, Function<Reference, Object>> forItem) {
        this.property = property;
        this.value = value;
        this.forItem = forItem;
    }

    /**
     * A value as written. Strings with references become templates, in lists too.
     *
     * @param raw null, or a String, BigDecimal, Boolean or List of those as the definition wrote it
     * @throws IllegalArgumentException for a malformed reference
     */
    public static ConfiguredValue of(String property, Object raw) {
        return new ConfiguredValue(property, parse(raw), null);
    }

    /**
     * A JSON value as the Java value {@link #of} takes.
     *
     * @throws IllegalArgumentException for null, an object, or an array holding anything but
     *                                  strings, numbers and booleans
     */
    public static Object fromJson(JsonNode node) {
        if (node.isString()) {
            return node.stringValue();
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                if (element.isArray() || element.isObject() || element.isNull()) {
                    throw new IllegalArgumentException("A list holds strings, numbers or booleans");
                }
                list.add(fromJson(element));
            }
            return List.copyOf(list);
        }
        throw new IllegalArgumentException(node.isNull()
                ? "null is not a value: leave the property out instead"
                : "An object is not a property value");
    }

    private static Object parse(Object raw) {
        if (raw instanceof String s && Template.mayReference(s)) {
            return Template.parse(s);
        }
        if (raw instanceof List<?> list) {
            List<Object> parsed = new ArrayList<>(list.size());
            for (Object element : list) {
                parsed.add(parse(element));
            }
            return List.copyOf(parsed);
        }
        return raw;
    }

    /**
     * This value with every reference {@code resolve} answers for replaced, ready to be evaluated
     * against items.
     *
     * @param resolve returns a reference's value, or null to leave it
     * @param forItem given an item, returns what its references resolve to
     */
    public ConfiguredValue resolve(Function<Reference, Object> resolve,
                                   Function<Item, Function<Reference, Object>> forItem) {
        return new ConfiguredValue(property, substitute(value, resolve), forItem);
    }

    private Object substitute(Object v, Function<Reference, Object> resolve) {
        try {
            if (v instanceof Template t) {
                return t.substitute(resolve);
            }
            if (v instanceof List<?> list) {
                List<Object> out = new ArrayList<>(list.size());
                for (Object element : list) {
                    out.add(substitute(element, resolve));
                }
                return List.copyOf(out);
            }
            return v;
        } catch (IllegalArgumentException e) {
            throw new ProcessException("Property \"" + property + "\": " + e.getMessage(), e);
        }
    }

    public Object value() {
        return value;
    }

    @Override
    public boolean isSet() {
        return value != null;
    }

    @Override
    public boolean refersToItem() {
        return refersToItem(value);
    }

    private static boolean refersToItem(Object v) {
        if (v instanceof Template t) {
            return t.refersToItem();
        }
        if (v instanceof List<?> list) {
            for (Object element : list) {
                if (refersToItem(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public PropertyValue evaluate(Item item) {
        if (forItem == null || !refersToItem()) {
            return this;
        }
        Function<Reference, Object> resolver = forItem.apply(item);
        return new ConfiguredValue(property, substitute(value,
                r -> r.scope().perItem() ? resolver.apply(r) : null), forItem);
    }

    @Override
    public String asString() {
        if (value == null) {
            return null;
        }
        Object v = resolved();
        if (v instanceof List<?>) {
            throw mismatch("text", v);
        }
        return Template.text(v);
    }

    @Override
    public Double asDouble() {
        Object v = resolvedOrNull();
        return switch (v) {
            case null -> null;
            case Number n -> n.doubleValue();
            case String s -> {
                try {
                    yield Double.valueOf(s.trim());
                } catch (NumberFormatException e) {
                    throw mismatch("a number", v);
                }
            }
            default -> throw mismatch("a number", v);
        };
    }

    @Override
    public Boolean asBoolean() {
        Object v = resolvedOrNull();
        return switch (v) {
            case null -> null;
            case Boolean b -> b;
            case String s when s.equals("true") || s.equals("false") -> Boolean.valueOf(s);
            default -> throw mismatch("true or false", v);
        };
    }

    @Override
    public Duration asDuration() {
        Object v = resolvedOrNull();
        return switch (v) {
            case null -> null;
            case Duration d -> d;
            case String s -> {
                try {
                    yield Duration.parse(s.trim());
                } catch (DateTimeParseException e) {
                    throw mismatch("an ISO-8601 duration such as PT5M", v);
                }
            }
            default -> throw mismatch("an ISO-8601 duration such as PT5M", v);
        };
    }

    @Override
    public Instant asInstant() {
        Object v = resolvedOrNull();
        return switch (v) {
            case null -> null;
            case Instant i -> i;
            case String s -> {
                try {
                    yield Instant.parse(s.trim());
                } catch (DateTimeParseException e) {
                    throw mismatch("an ISO-8601 instant such as 2026-10-01T00:00:00Z", v);
                }
            }
            default -> throw mismatch("an ISO-8601 instant such as 2026-10-01T00:00:00Z", v);
        };
    }

    @Override
    public List<String> asList() {
        Object v = resolvedOrNull();
        if (v == null) {
            return null;
        }
        if (!(v instanceof List<?> list)) {
            throw mismatch("a list", v);
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element instanceof Template) {
                throw unresolved();
            }
            out.add(Template.text(element));
        }
        return List.copyOf(out);
    }

    private Object resolvedOrNull() {
        return value == null ? null : resolved();
    }

    private Object resolved() {
        if (value instanceof Template) {
            throw unresolved();
        }
        return value;
    }

    private ProcessException unresolved() {
        return new ProcessException("Property \"" + property + "\" still has references to resolve"
                + (refersToItem() ? ": it refers to the item, so evaluate it against one" : ""));
    }

    private ProcessException mismatch(String expected, Object v) {
        String shown = v instanceof String s ? "\"" + s + "\"" : String.valueOf(v);
        return new ProcessException("Property \"" + property + "\" must be " + expected + ", not "
                + shown);
    }

    @Override
    public String toString() {
        return value instanceof Template t ? t.toString() : String.valueOf(value);
    }
}
