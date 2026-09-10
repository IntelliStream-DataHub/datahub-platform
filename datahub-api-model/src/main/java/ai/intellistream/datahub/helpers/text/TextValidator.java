// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.text;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

@Slf4j
public class TextValidator {

    private static Pattern patternSnakeUpperCased = Pattern.compile(
            "[A-Z]+(?:_[A-Z]+)*$",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    // snake_case: lowercase-letter/digit runs separated by single underscores. A separator must
    // sit between two runs, so leading/trailing/double separators are rejected (e.g. "_foo",
    // "foo_", "foo__bar").
    private static Pattern patternSnakeLowerCasedDigits = Pattern.compile(
            "[a-z0-9]+(?:_[a-z0-9]+)*$",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    public static Pattern WHITESPACE = Pattern.compile("(?U)\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public static Pattern VALID_NAME = Pattern.compile("[A-Z]+(?:_[A-Z]+)*$", Pattern.UNICODE_CHARACTER_CLASS);

    public static Pattern REPLACE_SPECIAL_C = Pattern.compile("(\\s)|(\\W)+", Pattern.UNICODE_CHARACTER_CLASS);

    public static Pattern REMOVE_FIRST_DIGITS = Pattern.compile("^\\d+", Pattern.UNICODE_CHARACTER_CLASS);

    public static Pattern FOLDER_NAME = Pattern.compile("^[a-zA-Z0-9_.-]+$", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Name reserved for the per-folder upload-staging directory (see {@code FileController}). Uploads
     * stream into a {@code .tmp} subdirectory of the destination folder before an atomic rename into
     * place, so users must not be able to create a folder with this name — otherwise their content
     * could collide with the staging area. {@link #pathName(String)} rejects any segment equal to it.
     */
    public static final String RESERVED_TMP_DIR = ".tmp";

    public static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("([a-z])([A-Z]+)", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * The platform's external-id charset floor: {@code A-Z a-z 0-9 . _ : + = -}.
     *
     * <p>Deliberately URL-safe without percent-encoding, so an external id can sit in a path segment
     * and in an RDF IRI's local part unescaped. Whitespace, {@code /} and control characters are
     * outside it — {@code /} because it would break path segmentation, whitespace and control
     * characters because they are invisible in exactly the places an operator would need to see them.
     */
    private static final Pattern EXTERNAL_ID_CHARSET = Pattern.compile(
            "[A-Za-z0-9._:+=-]+",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    public static boolean validateAZSnakeLowerCasedAndDigits(String text){
        // A dash is treated as a snake_case separator: '-' is converted to '_' before validating,
        // so a kebab-case identifier like "switch-backbone-0-01-arista" is accepted as the
        // equivalent snake_case "switch_backbone_0_01_arista".
        return patternSnakeLowerCasedDigits.matcher(text.replace('-', '_')).matches();
    }

    /**
     * Validate an external id against the platform charset floor, without mutating it.
     *
     * <p>This is the <em>floor</em>, not the convention. It applies to resources, data sets and
     * events alike, and it is all that events are ever subject to: an event external id is the
     * source system's key for the subject the event is about, not a name someone chose, so the
     * platform does not impose a naming convention on it. The convention — snake_case, verbatim
     * industrial tags, or a supplied pattern — is a configurable naming policy layered on top, and
     * governs resources and data sets only.
     *
     * <p>Note what this method does <em>not</em> do: it does not rewrite {@code -} to {@code _}.
     * That rewrite used to run before validation, so a caller mirroring the plant tag
     * {@code COM-99-PT-1034} got {@code com_99_pt_1034} stored, byte-equality joins against the
     * source system silently stopped matching, and the normalisation was lossy enough to collide
     * ({@code P-101} and {@code P.101} both landed on {@code p_101}).
     *
     * @param text the candidate external id
     * @return true when every character is in the charset and it is non-empty
     */
    public static boolean validateExternalIdCharset(String text){
        return text != null && EXTERNAL_ID_CHARSET.matcher(text).matches();
    }

    public static String toSnakeUpperCased(String s){
        // Null/blank passes through unchanged: don't NPE on null, and don't turn whitespace into
        // underscores ("   " -> "___"), which would hide a blank from a downstream @NotBlank check.
        if (s == null || s.isBlank()) {
            return s;
        }
        s = REMOVE_FIRST_DIGITS.matcher(s).replaceFirst("");
        s = REPLACE_SPECIAL_C.matcher(s.toUpperCase()).replaceAll("_");
        return s;
    }

    public static String toSnakeLowerCased(String s){
        s = REMOVE_FIRST_DIGITS.matcher(s).replaceFirst("");

        // Convert camelCase to snake_case by inserting underscores before uppercase letters
        s = CAMEL_CASE_PATTERN.matcher(s).replaceAll("$1_$2");
        s = REPLACE_SPECIAL_C.matcher(s.toLowerCase()).replaceAll("_");

        return trimUnderscores(s);
    }

    /** Strips leading/trailing underscore runs by index; the regex {@code _+$} backtracks quadratically on long runs. */
    public static String trimUnderscores(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '_') start++;
        while (end > start && s.charAt(end - 1) == '_') end--;
        return s.substring(start, end);
    }

    public static String toSnakeLowerCasedAllowStartWithDigits(String s){
        // Leave null/blank unchanged so a downstream @NotBlank check can reject it; normalising
        // whitespace to underscores would otherwise mask a blank value.
        if(s != null && !s.isBlank()){
            s = REPLACE_SPECIAL_C.matcher(s.toLowerCase()).replaceAll("_");
        }
        return s;
    }

    public static String directoryName(String s){
        char[] charArray = s.toCharArray();
        StringBuilder result = new StringBuilder();
        for (char c : charArray) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                    Character.isDigit(c) || c == '-' || c == '.') {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * Normalize and validate a logical path, rejecting anything that could escape its intended
     * root. Accepted input is split on {@code /} (with {@code \} first folded to {@code /}), then
     * each segment is validated against {@code [A-Za-z0-9._-]+} with the additional constraint
     * that a segment cannot be {@code .}, {@code ..}, or the reserved {@link #RESERVED_TMP_DIR}
     * upload-staging name. Duplicate and trailing slashes are collapsed. A leading {@code /} is
     * preserved.
     *
     * <p>The old implementation allowed {@code /} and {@code .} anywhere, which meant inputs like
     * {@code ../etc/passwd} or {@code foo/../../bar} passed through unchanged. File operations
     * downstream would happily follow those traversals.
     *
     * @throws IllegalArgumentException if the path is null, empty after normalization, contains
     *         illegal characters, or contains a {@code .}/{@code ..}/{@link #RESERVED_TMP_DIR} segment.
     */
    public static String pathName(String path) {
        if (path == null) {
            throw new IllegalArgumentException("Path must not be null");
        }
        String unified = path.replace('\\', '/');
        boolean leadingSlash = unified.startsWith("/");
        StringBuilder result = new StringBuilder();
        if (leadingSlash) result.append('/');

        boolean appended = false;
        for (String segment : unified.split("/", -1)) {
            if (segment.isEmpty()) continue; // collapse //, trim leading/trailing /
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Path traversal segment not allowed: " + segment);
            }
            if (segment.equals(RESERVED_TMP_DIR)) {
                throw new IllegalArgumentException("Reserved directory name not allowed: " + segment);
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || Character.isDigit(c) || c == '-' || c == '_' || c == '.';
                if (!ok) {
                    throw new IllegalArgumentException("Invalid character in path name: " + c);
                }
            }
            if (appended) result.append('/');
            result.append(segment);
            appended = true;
        }

        if (!leadingSlash && !appended) {
            throw new IllegalArgumentException("Path must contain at least one valid segment");
        }
        return result.toString();
    }

    public static String getParentPath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }

        // Remove trailing slash if it exists and path is not just "/"
        if (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }

        int lastSlashIndex = path.lastIndexOf("/");

        if (lastSlashIndex == -1 || lastSlashIndex == 0) {
            // If no slash found, or only the root slash
            return "/";
        } else {
            return path.substring(0, lastSlashIndex);
        }
    }

    public static Boolean parseBooleanSafe(String value) {
        try {
            if (value == null) {
                throw new IllegalArgumentException("Input value is null");
            }
            if (value.equalsIgnoreCase("true")) {
                return true;
            } else if (value.equalsIgnoreCase("false")) {
                return false;
            } else {
                throw new IllegalArgumentException("Invalid boolean value: " + value);
            }
        } catch (IllegalArgumentException e) {
            // Handle the exception (e.g., log it) or rethrow
            log.warn ("Error: " + e.getMessage());
            //throw e; // Optional, if you want to propagate the exception
            return null;
        }
    }


}
