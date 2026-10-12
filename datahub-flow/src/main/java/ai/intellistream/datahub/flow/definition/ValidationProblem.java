// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.definition;

/**
 * One thing wrong with a flow definition.
 *
 * @param pointer where, as an RFC 6901 JSON Pointer into the definition prefixed with {@code #},
 *                e.g. {@code #/processors/2/properties/target}, the form the api's problem
 *                responses use
 * @param message what is wrong, in a sentence
 */
public record ValidationProblem(String pointer, String message) {
}
