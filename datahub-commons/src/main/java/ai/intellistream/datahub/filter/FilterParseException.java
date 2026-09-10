// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.filter;

/**
 * A filter expression the parser will not accept, with everything a caller needs to fix it.
 *
 * <p>Carrying an {@link #offset} and a {@link #suggestedQuery} rather than only a message is the
 * point: the console underlines the span and offers the repair as one click, and an SDK caller
 * gets an expression they can paste. A message alone makes the caller do the edit by hand from a
 * description of it.
 *
 * <p>{@link #suggestedQuery} is null whenever the fix is ambiguous. Applying the wrong correction
 * automatically is worse than offering none, so several near-misses list candidates in the message
 * and leave the repair to the caller.
 *
 * <p>{@link #getCode()} and {@link #getArgs()} carry the same message as a stable key plus its
 * values, so a UI can render it in the reader's own language. The English {@link #getMessage()}
 * stays the fallback rather than being replaced: an SDK caller, a curl session and a log line all
 * want a sentence, and a client that does not translate should not be handed a bare key.
 */
public class FilterParseException extends RuntimeException {

    private final int offset;
    private final int length;
    private final String suggestion;
    private final String suggestedQuery;
    private final String help;
    private String code;
    private String[] args = new String[0];
    private String helpCode;
    private String[] helpArgs = new String[0];

    public FilterParseException(String message, int offset, int length,
                                String suggestion, String suggestedQuery, String help) {
        super(message);
        this.offset = offset;
        this.length = length;
        this.suggestion = suggestion;
        this.suggestedQuery = suggestedQuery;
        this.help = help;
    }

    public FilterParseException(String message, int offset, int length) {
        this(message, offset, length, null, null, null);
    }

    /**
     * Attach the translation key for this message, with the values it interpolates.
     *
     * <p>Fluent rather than a constructor argument because every throw site already passes four
     * or six things; another positional pair would make them unreadable.
     */
    public FilterParseException withCode(String code, String... args) {
        this.code = code;
        this.args = args == null ? new String[0] : args;
        return this;
    }

    /** The same for {@link #getHelp()}, which a UI shows beneath the message. */
    public FilterParseException withHelpCode(String helpCode, String... helpArgs) {
        this.helpCode = helpCode;
        this.helpArgs = helpArgs == null ? new String[0] : helpArgs;
        return this;
    }

    /** Translation key for {@link #getMessage()}, or null when the message is not keyed. */
    public String getCode() {
        return code;
    }

    /** Values for the {@code {0}}, {@code {1}} … placeholders of {@link #getCode()}. */
    public String[] getArgs() {
        return args.clone();
    }

    public String getHelpCode() {
        return helpCode;
    }

    public String[] getHelpArgs() {
        return helpArgs.clone();
    }

    /** Character offset of the offending token in the expression, 0-based. */
    public int getOffset() {
        return offset;
    }

    /** Length of the offending token, so a caller can underline exactly it. */
    public int getLength() {
        return length;
    }

    /** The single replacement token, when there is an unambiguous one; otherwise null. */
    public String getSuggestion() {
        return suggestion;
    }

    /** The caller's expression with {@link #getSuggestion()} spliced in; null when ambiguous. */
    public String getSuggestedQuery() {
        return suggestedQuery;
    }

    /** One line of orientation, e.g. that this dialect uses PostgreSQL function names. */
    public String getHelp() {
        return help;
    }
}
