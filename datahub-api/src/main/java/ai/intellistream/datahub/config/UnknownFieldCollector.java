// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import tools.jackson.core.JsonParser;
import tools.jackson.core.TokenStreamContext;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.DeserializationProblemHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Gathers every unknown field in one request body instead of failing on the first.
 *
 * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} throws the moment Jackson meets a field the target type
 * does not declare, so a body with three stale fields costs three round trips to discover — and
 * which one you hear about depends on the order they happen to appear in the JSON. Reporting the
 * whole set costs one parse and saves the caller the rest.
 *
 * <p>The handler claims each unknown property so parsing continues, recording where it sat and what
 * that position accepts. {@code StrictJacksonJsonHttpMessageConverter} drains the result once the
 * body is read and fails then, with everything found.
 *
 * <h2>Thread-local, and cleared per read</h2>
 * There is no per-parse object to hang this on that the converter can also reach, so it rides a
 * {@link ThreadLocal}. Tomcat reuses worker threads, so it is cleared around every read — the same
 * hazard, and the same discipline, as {@code TenantContext}.
 */
public final class UnknownFieldCollector extends DeserializationProblemHandler {

    /** JSON Pointer → the field names its position does accept. Insertion-ordered. */
    private static final ThreadLocal<Map<String, Set<String>>> FOUND = new ThreadLocal<>();

    /**
     * One unknown field: where it sat in the body as an RFC 6901 JSON Pointer, and the names that
     * position accepts. The pointer is what RFC 9457's {@code errors} extension expects, and it also
     * tells a nested field apart from a top-level one of the same name.
     */
    public record UnknownField(String pointer, Set<String> allowed) {}

    static void begin() {
        FOUND.set(new LinkedHashMap<>());
    }

    /** Everything collected since {@link #begin()}, in the order the fields appeared in the body. */
    static List<UnknownField> drain() {
        Map<String, Set<String>> found = FOUND.get();
        FOUND.remove();
        if (found == null || found.isEmpty()) {
            return List.of();
        }
        List<UnknownField> result = new ArrayList<>(found.size());
        found.forEach((pointer, allowed) -> result.add(new UnknownField(pointer, allowed)));
        return result;
    }

    @Override
    public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
                                         ValueDeserializer<?> deserializer,
                                         Object beanOrClass, String propertyName) {
        Map<String, Set<String>> found = FOUND.get();
        if (found == null) {
            // Not inside a request-body read — a mapper elsewhere shares this handler by accident.
            // Returning false restores the default behaviour rather than silently swallowing it.
            return false;
        }
        found.computeIfAbsent(pointerFor(p, propertyName), where -> knownPropertiesOf(deserializer));
        p.skipChildren();   // consume the value so the parse can continue past it
        return true;
    }

    /**
     * The RFC 6901 pointer for the property being rejected, walked out of the parser's context
     * chain: the innermost context is the object holding the unknown property, so its ancestors
     * supply the path and the property name supplies the last segment.
     */
    private static String pointerFor(JsonParser p, String propertyName) {
        Deque<String> segments = new ArrayDeque<>();
        TokenStreamContext context = p.streamReadContext();
        for (TokenStreamContext parent = context == null ? null : context.getParent();
             parent != null && !parent.inRoot();
             parent = parent.getParent()) {
            if (parent.inArray()) {
                segments.addFirst(String.valueOf(parent.getCurrentIndex()));
            } else if (parent.currentName() != null) {
                segments.addFirst(escape(parent.currentName()));
            }
        }
        segments.addLast(escape(propertyName));
        return "#/" + String.join("/", segments);
    }

    /** RFC 6901 requires {@code ~} and {@code /} to be escaped inside a pointer segment. */
    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    /** Sorted for a stable response; empty when the deserializer cannot enumerate its properties. */
    private static Set<String> knownPropertiesOf(ValueDeserializer<?> deserializer) {
        Collection<Object> known = deserializer == null ? null : deserializer.getKnownPropertyNames();
        if (known == null) {
            return Set.of();
        }
        Set<String> names = new TreeSet<>();
        known.forEach(name -> names.add(String.valueOf(name)));
        return names;
    }
}
