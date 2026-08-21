// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.models.policy;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A resolved naming policy: the preset, what a violation does, and the near-duplicate guard's mode.
 *
 * <p>Built once per resolution and cached, never per item on the write path. The regex for
 * {@link NamingPreset#PATTERN} is compiled here rather than at each evaluation for the same reason.
 *
 * @param policyId         the policy node's id, or null for {@link #shippedDefault()}
 * @param policyExternalId names the rule in errors and findings, so a caller is told which rule
 *                         rejected them rather than only that something did
 * @param preset           the convention
 * @param compiledPattern  the compiled regex when {@code preset == PATTERN}, otherwise null
 * @param mode             what a preset violation does
 * @param nearDuplicateMode what a near-duplicate does
 */
public record NamingPolicy(
        Long policyId,
        String policyExternalId,
        NamingPreset preset,
        Pattern compiledPattern,
        PolicyMode mode,
        PolicyMode nearDuplicateMode) {

    /** Metadata keys a policy node carries its naming configuration under. */
    public static final String KEY_KIND = "kind";
    public static final String KEY_PRESET = "preset";
    public static final String KEY_PATTERN = "pattern";
    public static final String KEY_MODE = "mode";
    public static final String KEY_NEAR_DUPLICATE_MODE = "nearDuplicateMode";

    /** The {@code kind} value marking a policy node as a naming policy. */
    public static final String KIND_NAMING = "naming";

    /** Name used when no policy node is configured and the shipped default applies. */
    public static final String DEFAULT_POLICY_NAME = "naming_default";

    /**
     * A cap on the supplied regex, and the reason there is one.
     *
     * <p>A caller-supplied pattern runs on the write path, so a pathological one is a way to stall
     * every ingest in the tenant. The length cap is the cheap half of the defence; the other half is
     * that matching runs against a bounded-length external id (256 characters), which keeps even a
     * badly-nested pattern's blow-up finite. Anything more (a timeout thread per match) costs more
     * than it saves at this size.
     */
    public static final int MAX_PATTERN_LENGTH = 512;

    /**
     * The number of separator-delimited segments {@link NamingPreset#QUALIFIED_TAG} requires.
     *
     * <p>Three, because that is the smallest count that can carry a qualifier, a type and a
     * sequence — the structure that makes a tag identify one thing on a site with many. Two admits
     * {@code pump-1234}, which names a class of equipment and a number rather than an asset.
     */
    public static final int MIN_QUALIFIED_SEGMENTS = 3;

    /**
     * What applies when no policy node is configured: {@code qualified_tag}, with the
     * near-duplicate guard on, and <strong>both rules warning rather than rejecting</strong>.
     *
     * <p>So the shipped default never refuses a write. Both rules are judgements the platform
     * cannot make on the caller's behalf. {@code P-101} is a legitimate ISA-5.1 loop tag at a site
     * with one pump and an under-specified name at a site with forty. {@code pump-a-01} beside an
     * existing {@code pump_a_01} is usually one asset written two ways, and occasionally two tags a
     * facility genuinely maintains apart. Nothing in a write says which, so both are recorded for a
     * steward and neither costs the caller their data.
     *
     * <p>That makes the findings queue the product of this policy, not an error path. A deployment
     * that wants either rule to block sets its own policy with {@code mode} or
     * {@code nearDuplicateMode} on reject, which is the point of the policy being configurable.
     */
    public static NamingPolicy shippedDefault() {
        return new NamingPolicy(null, DEFAULT_POLICY_NAME, NamingPreset.QUALIFIED_TAG, null,
                PolicyMode.WARN, PolicyMode.WARN);
    }

    /**
     * Read a naming policy out of a policy node's metadata map, falling back to the shipped defaults
     * for anything absent or unparseable.
     *
     * <p>Lenient by design: a policy is edited through a form and stored as loose strings, and the
     * write path is the wrong place to discover that one of them is malformed. A bad regex here
     * degrades to the preset default rather than failing every write in the tenant — and
     * {@link #validatePattern} exists so the mistake is caught at policy-save time, where it can be
     * shown to the person who made it.
     */
    public static NamingPolicy fromMetadata(Long policyId, String policyExternalId, Map<String, String> metadata) {
        Map<String, String> meta = metadata == null ? Map.of() : metadata;

        NamingPreset preset = NamingPreset.parse(meta.get(KEY_PRESET));
        Pattern compiled = null;
        if (preset == NamingPreset.PATTERN) {
            compiled = compileOrNull(meta.get(KEY_PATTERN));
            if (compiled == null) {
                // A pattern preset with no usable pattern constrains nothing; say so by falling back
                // rather than silently accepting everything under a name that implies a rule.
                preset = NamingPreset.VERBATIM_TAG;
            }
        }

        return new NamingPolicy(
                policyId,
                policyExternalId == null ? DEFAULT_POLICY_NAME : policyExternalId,
                preset,
                compiled,
                PolicyMode.parse(meta.get(KEY_MODE), PolicyMode.REJECT),
                PolicyMode.parse(meta.get(KEY_NEAR_DUPLICATE_MODE), PolicyMode.REJECT));
    }

    /**
     * Validate a supplied regex at policy-save time.
     *
     * @return null when acceptable, otherwise the reason it is not
     */
    public static String validatePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return "A pattern is required when the preset is 'pattern'.";
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            return "Pattern must be at most " + MAX_PATTERN_LENGTH + " characters.";
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "Pattern is not a valid regular expression: " + e.getDescription();
        }
        return null;
    }

    private static Pattern compileOrNull(String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.length() > MAX_PATTERN_LENGTH) {
            return null;
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** Whether {@code externalId} satisfies this policy's preset. Says nothing about near-duplicates. */
    public boolean matchesPreset(String externalId) {
        return switch (preset) {
            // The charset floor has already been enforced by validation; verbatim adds nothing.
            case VERBATIM_TAG -> true;
            case QUALIFIED_TAG -> segmentCount(externalId) >= MIN_QUALIFIED_SEGMENTS;
            case SNAKE_CASE -> SNAKE_CASE_PATTERN.matcher(externalId).matches();
            case PATTERN -> compiledPattern == null || compiledPattern.matcher(externalId).matches();
        };
    }

    /** Today's rule: lowercase alphanumeric runs joined by single underscores. */
    private static final Pattern SNAKE_CASE_PATTERN = Pattern.compile("[a-z0-9]+(?:_[a-z0-9]+)*");

    /**
     * How many separator-delimited alphanumeric runs an external id has.
     *
     * <p>A segment is a maximal run of letters and digits; everything else in the charset
     * ({@code . _ : + = -}) separates. Counting runs rather than splitting on a separator set means
     * leading, trailing and repeated separators cost nothing: {@code =K1-M3+B02} is three segments,
     * not four with an empty one, so an IEC 81346 designation is not penalised for its leading
     * {@code =}.
     *
     * <p>Written as a scan rather than a regex or {@code split} because it runs on the write path
     * for every item in every batch, and it allocates nothing.
     */
    private static int segmentCount(String externalId) {
        int segments = 0;
        boolean inSegment = false;
        for (int i = 0; i < externalId.length(); i++) {
            char c = externalId.charAt(i);
            boolean alphanumeric = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!alphanumeric) {
                inSegment = false;
            } else if (!inSegment) {
                segments++;
                inSegment = true;
            }
        }
        return segments;
    }

    public String describePreset() {
        return switch (preset) {
            case VERBATIM_TAG -> "verbatim_tag";
            case QUALIFIED_TAG -> "qualified_tag (at least " + MIN_QUALIFIED_SEGMENTS
                    + " separator-delimited segments)";
            case SNAKE_CASE -> "snake_case";
            case PATTERN -> "pattern " + (compiledPattern == null ? "" : "'" + compiledPattern.pattern() + "'");
        };
    }
}
