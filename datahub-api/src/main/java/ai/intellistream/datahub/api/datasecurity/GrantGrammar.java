// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The one grammar every organization-group grant follows, bound to a subject and its verbs:
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
 *   <li><b>A new verb</b> (say {@code manage}): add it to the facade's
 *       {@link #of(String, String...)} call and surface it there. Verbs never imply one another;
 *       each is granted by its own group.</li>
 *   <li><b>A new subject</b> (a third thing grants attach to): a new facade holding its own
 *       {@code GrantGrammar.of("<subject>", ...)}. Do not widen an existing facade.</li>
 *   <li><b>Objects</b> are opaque: any string the subject's domain gives meaning to. The only
 *       reserved spelling is {@code *}.</li>
 * </ul>
 *
 * <h2>Parsing rules</h2>
 * <ul>
 *   <li>The object segment is taken <b>verbatim</b> — never normalised or rewritten. Matching an
 *       object case-insensitively (or not) is the facade's domain decision; this parser only
 *       collapses duplicates that differ in case, since both spellings name the same grant.</li>
 *   <li>Verbs match case-insensitively (locale-independent), so {@code /datasets/x/READ} grants
 *       what {@code /datasets/x/read} does rather than silently nothing.</li>
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

    private final String prefix;
    /** Any-case spelling of a verb → the canonical spelling the grammar declares. */
    private final Map<String, String> verbs;
    private final Grants none;

    private GrantGrammar(String subject, String... verbs) {
        this.prefix = "/" + subject + "/";
        Map<String, String> canonical = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String verb : verbs) {
            canonical.put(verb, verb);
        }
        this.verbs = canonical;
        this.none = new Grants(Set.of(), Map.of());
    }

    static GrantGrammar of(String subject, String... verbs) {
        return new GrantGrammar(subject, verbs);
    }

    /** Parse organization group paths into whatever this grammar's subject they grant. */
    Grants parse(Collection<String> groupPaths) {
        if (groupPaths == null || groupPaths.isEmpty()) {
            return none;
        }
        Set<String> everyObject = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Set<String>> objectsByVerb = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

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
            String verb = verbs.get(verbSpelling);
            if (verb == null) {
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
            return none;
        }
        objectsByVerb.replaceAll((verb, objects) -> Collections.unmodifiableSet(objects));
        return new Grants(Collections.unmodifiableSet(everyObject),
                Collections.unmodifiableMap(objectsByVerb));
    }

    /** What one caller holds under one subject's grammar. Verb lookups are case-insensitive. */
    static final class Grants {

        private final Set<String> everyObjectVerbs;
        private final Map<String, Set<String>> objectsByVerb;

        private Grants(Set<String> everyObjectVerbs, Map<String, Set<String>> objectsByVerb) {
            this.everyObjectVerbs = everyObjectVerbs;
            this.objectsByVerb = objectsByVerb;
        }

        boolean isEmpty() {
            return everyObjectVerbs.isEmpty() && objectsByVerb.isEmpty();
        }

        /** Whether the verb is granted over every object, the {@code *} grant. */
        boolean allowsAll(String verb) {
            return everyObjectVerbs.contains(verb);
        }

        /** The objects the verb is granted on by name. Empty — not everything — under {@code *}. */
        Set<String> objects(String verb) {
            return objectsByVerb.getOrDefault(verb, Set.of());
        }
    }
}
