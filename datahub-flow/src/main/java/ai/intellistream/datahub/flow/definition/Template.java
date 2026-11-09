// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.definition;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A property value with {@code ${…}} references in it. Substitution only: a reference names one
 * value, and there are no operators or functions.
 *
 * <p>What a name refers to is decided by its first segment:
 * <ul>
 *   <li>{@code parameters.<name>} — a flow parameter, as resolved for the run;</li>
 *   <li>{@code run.window.start}, {@code run.window.end} — the run's window;</li>
 *   <li>{@code secrets.<name>} — a secret from the tenant's flow settings, only in a sensitive
 *       property;</li>
 *   <li>{@code item.last}, {@code item.lastTime}, {@code item.min}, {@code item.max},
 *       {@code item.mean}, {@code item.count} — a summary of the timeseries item being handled;</li>
 *   <li>anything else — an attribute of the item being handled, e.g.
 *       {@code ${timeseries.externalId}}.</li>
 * </ul>
 * The first three are resolved once per run, the last two once per item.
 *
 * <p>There is no escape for a literal {@code ${}: a value cannot contain one.
 */
public final class Template {

    /** A name: letters, digits and {@code _ . : -}, which covers every attribute name in use. */
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z0-9_.:-]+)}");

    public static final Set<String> RUN_KEYS = Set.of("window.start", "window.end");
    public static final Set<String> ITEM_KEYS = Set.of("last", "lastTime", "min", "max", "mean", "count");

    private final List<Part> parts;

    private Template(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    /** @throws IllegalArgumentException for a {@code ${} that does not start a valid reference */
    public static Template parse(String text) {
        List<Part> parts = new ArrayList<>();
        Matcher m = REFERENCE.matcher(text);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) {
                parts.add(literal(text.substring(at, m.start())));
            }
            parts.add(reference(m.group(1)));
            at = m.end();
        }
        if (at < text.length()) {
            parts.add(literal(text.substring(at)));
        }
        return new Template(parts);
    }

    /** Whether {@code text} contains anything that looks like a reference. */
    public static boolean mayReference(String text) {
        return text.contains("${");
    }

    private static Literal literal(String text) {
        int stray = text.indexOf("${");
        if (stray >= 0) {
            throw new IllegalArgumentException("\"" + text.substring(stray) + "\" is not a valid "
                    + "reference: write ${name}, where the name is letters, digits and _ . : -");
        }
        return new Literal(text);
    }

    private static Reference reference(String name) {
        int dot = name.indexOf('.');
        String head = dot < 0 ? name : name.substring(0, dot);
        String key = dot < 0 ? "" : name.substring(dot + 1);
        Scope scope = switch (head) {
            case "parameters" -> Scope.PARAMETERS;
            case "run" -> Scope.RUN;
            case "secrets" -> Scope.SECRETS;
            case "item" -> Scope.ITEM;
            default -> Scope.ATTRIBUTE;
        };
        if (scope != Scope.ATTRIBUTE && key.isEmpty()) {
            throw new IllegalArgumentException("${" + name + "} names no value: write ${" + head
                    + ".<name>}");
        }
        return new Reference(name, scope, scope == Scope.ATTRIBUTE ? name : key);
    }

    public List<Part> parts() {
        return parts;
    }

    public List<Reference> references() {
        List<Reference> refs = new ArrayList<>();
        for (Part p : parts) {
            if (p instanceof Reference r) {
                refs.add(r);
            }
        }
        return refs;
    }

    /** Whether the whole value is one reference, so it may stand for a value of any type. */
    public boolean isSingleReference() {
        return parts.size() == 1 && parts.getFirst() instanceof Reference;
    }

    public boolean refersToItem() {
        for (Reference r : references()) {
            if (r.scope().perItem()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the references {@code resolve} answers for (it returns null for those it leaves
     * alone) and returns what the value has become: the referenced value itself,
     * typed, when the whole value was one reference; text when everything is resolved; or a template
     * of what is left.
     *
     * @throws IllegalArgumentException when a value cannot be put into text, such as a list
     */
    public Object substitute(Function<Reference, Object> resolve) {
        List<Part> left = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        boolean unresolved = false;
        for (Part p : parts) {
            if (p instanceof Literal l) {
                text.append(l.text());
                continue;
            }
            Reference r = (Reference) p;
            Object value = resolve.apply(r);
            if (value == null) {
                if (!text.isEmpty()) {
                    left.add(new Literal(text.toString()));
                    text.setLength(0);
                }
                left.add(r);
                unresolved = true;
            } else if (isSingleReference()) {
                return value;
            } else {
                text.append(text(value));
            }
        }
        if (!unresolved) {
            return text.toString();
        }
        if (!text.isEmpty()) {
            left.add(new Literal(text.toString()));
        }
        return new Template(left);
    }

    /** {@code segment} escaped for use in a JSON Pointer (RFC 6901). */
    static String escapePointer(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    /** A value as it reads when substituted into text. */
    public static String text(Object value) {
        return switch (value) {
            case String s -> s;
            case BigDecimal d -> d.stripTrailingZeros().toPlainString();
            case Double d when d.isNaN() || d.isInfinite() -> d.toString();
            case Number n -> new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
            case Boolean b -> b.toString();
            case Instant i -> i.toString();
            case Duration d -> d.toString();
            default -> throw new IllegalArgumentException("A " + value.getClass().getSimpleName()
                    + " cannot be put into text");
        };
    }

    /** The value as written. */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (Part p : parts) {
            text.append(p instanceof Literal l ? l.text() : "${" + ((Reference) p).name() + "}");
        }
        return text.toString();
    }

    public sealed interface Part permits Literal, Reference {
    }

    public record Literal(String text) implements Part {
    }

    /**
     * @param name  as written, e.g. {@code parameters.limit}
     * @param key   within the scope, e.g. {@code limit}; for an attribute, the whole name
     */
    public record Reference(String name, Scope scope, String key) implements Part {
    }

    public enum Scope {
        PARAMETERS(false), RUN(false), SECRETS(false), ITEM(true), ATTRIBUTE(true);

        private final boolean perItem;

        Scope(boolean perItem) {
            this.perItem = perItem;
        }

        public boolean perItem() {
            return perItem;
        }
    }
}
