// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one grammar every organization-group grant follows, bound to a subject and the
 * {@link GrantVerb}s that apply to it:
 *
 * <pre>
 *   /&lt;subject&gt;/&lt;object&gt;/&lt;verb&gt;      e.g.  /datasets/data_set_sap/read
 *   /&lt;subject&gt;/*&#47;&lt;verb&gt;                  /settings/*&#47;write   (every object, present and future)
 * </pre>
 *
 * <p>{@link DatasetGrants} and {@link SettingsGrants} are facades over this parser; they add the
 * domain meaning (what an object is, what a grant lets the caller do) while the mechanics live
 * here once. Paths are relative to the organization — that is what Keycloak emits in the
 * {@code organization.<alias>.groups} claim — so the tenant is implicit, including for the
 * wildcard: "every object" always means every object <em>of this organization</em>.
 *
 * <h2>Extending</h2>
 * <ul>
 *   <li><b>A new verb</b>: a {@link GrantVerb} constant, declared in the facade's
 *       {@link #of(String, GrantVerb...)} call and surfaced there. Verbs never imply one another;
 *       each is granted by its own group.</li>
 *   <li><b>A new subject</b> (a third thing grants attach to): a new facade holding its own
 *       {@code GrantGrammar.of("<subject>", ...)}. Do not widen an existing facade.</li>
 *   <li><b>Objects</b> are opaque: any string the subject's domain gives meaning to. The only
 *       reserved spelling is {@code *}, so a new subject's object ids must not admit it.</li>
 * </ul>
 *
 * <h2>Parsing rules</h2>
 * <ul>
 *   <li>The object segment is taken <b>verbatim</b> — never normalised or rewritten. Matching an
 *       object case-insensitively (or not) is the facade's domain decision; this parser only
 *       collapses duplicates that differ in case, since both spellings name the same grant.</li>
 *   <li>Verbs resolve through {@link GrantVerb#fromSpelling}, case-insensitively; a verb the
 *       grammar does not declare grants nothing.</li>
 *   <li>{@code *} as the object sets the every-object flag for that verb; it cannot collide with
 *       a real object in either current subject (dataset external ids and settings scopes both
 *       exclude {@code *} from their charsets).</li>
 *   <li>Paths nested deeper than the grammar ({@code /datasets/a/b/read}) are refused rather than
 *       guessed at, so an unintended subgroup cannot silently grant something.</li>
 *   <li>Everything else is silently ignored: an organization's group tree is theirs and may hold
 *       groups that have nothing to do with DataHub. A path that looks <em>meant</em> as a grant
 *       but is malformed is logged at debug.</li>
 * </ul>
 *
 * <p>Object sets come out sorted and case-insensitive: repeated grants collapse to one, and the
 * sorted order gives anything downstream that fingerprints a grant set (the dataset closure cache)
 * a stable key regardless of the order the identity provider returns groups in.
 */
@Slf4j
final class GrantGrammar {

    /** The every-object grant. The one reserved object spelling. */
    static final String ALL_OBJECTS = "*";

    private static final Grants NONE = new Grants(Set.of(), Map.of());

    private final String prefix;
    private final Set<GrantVerb> verbs;

    private GrantGrammar(String subject, GrantVerb... verbs) {
        this.prefix = "/" + subject + "/";
        this.verbs = EnumSet.noneOf(GrantVerb.class);
        Collections.addAll(this.verbs, verbs);
    }

    static GrantGrammar of(String subject, GrantVerb... verbs) {
        return new GrantGrammar(subject, verbs);
    }

    /** Parse organization group paths into whatever this grammar's subject they grant. */
    Grants parse(Collection<String> groupPaths) {
        if (groupPaths == null || groupPaths.isEmpty()) {
            return NONE;
        }
        Set<GrantVerb> everyObject = EnumSet.noneOf(GrantVerb.class);
        Map<GrantVerb, Set<String>> objectsByVerb = new EnumMap<>(GrantVerb.class);

        for (String path : groupPaths) {
            if (path == null || !path.startsWith(prefix)) {
                continue;
            }
            String remainder = path.substring(prefix.length());
            int split = remainder.lastIndexOf('/');
            if (split <= 0 || split == remainder.length() - 1) {
                // The container group itself ("/datasets/foo", no verb) or a trailing slash.
                log.debug("Ignoring organization group with no permission segment: {}", path);
                continue;
            }
            String object = remainder.substring(0, split);
            String verbSpelling = remainder.substring(split + 1);

            if (object.indexOf('/') >= 0) {
                log.debug("Ignoring organization group nested deeper than the grant grammar: {}", path);
                continue;
            }
            if (object.isBlank()) {
                continue;
            }
            GrantVerb verb = GrantVerb.fromSpelling(verbSpelling);
            if (verb == null || !verbs.contains(verb)) {
                log.debug("Ignoring organization group with unknown permission '{}': {}",
                        verbSpelling, path);
                continue;
            }
            if (ALL_OBJECTS.equals(object)) {
                everyObject.add(verb);
            } else {
                objectsByVerb.computeIfAbsent(verb, v -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                        .add(object);
            }
        }

        if (everyObject.isEmpty() && objectsByVerb.isEmpty()) {
            return NONE;
        }
        objectsByVerb.replaceAll((verb, objects) -> Collections.unmodifiableSet(objects));
        return new Grants(Collections.unmodifiableSet(everyObject),
                Collections.unmodifiableMap(objectsByVerb));
    }

    /** What one caller holds under one subject's grammar. */
    static final class Grants {

        private final Set<GrantVerb> everyObjectVerbs;
        private final Map<GrantVerb, Set<String>> objectsByVerb;

        private Grants(Set<GrantVerb> everyObjectVerbs, Map<GrantVerb, Set<String>> objectsByVerb) {
            this.everyObjectVerbs = everyObjectVerbs;
            this.objectsByVerb = objectsByVerb;
        }

        boolean isEmpty() {
            return everyObjectVerbs.isEmpty() && objectsByVerb.isEmpty();
        }

        /** Whether the verb is granted over every object, the {@code *} grant. */
        boolean allowsAll(GrantVerb verb) {
            return everyObjectVerbs.contains(verb);
        }

        /** The objects the verb is granted on by name. Empty — not everything — under {@code *}. */
        Set<String> objects(GrantVerb verb) {
            return objectsByVerb.getOrDefault(verb, Set.of());
        }
    }
}
