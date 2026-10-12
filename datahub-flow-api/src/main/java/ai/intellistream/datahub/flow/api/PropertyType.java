// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.flow.api;

/**
 * What kind of value a property holds, and so how a definition writes it.
 *
 * <ul>
 *   <li>{@link #STRING} — a JSON string.</li>
 *   <li>{@link #NUMBER} — a JSON number.</li>
 *   <li>{@link #BOOLEAN} — a JSON boolean.</li>
 *   <li>{@link #DURATION} — an ISO-8601 duration string, e.g. {@code PT5M}.</li>
 *   <li>{@link #INSTANT} — an ISO-8601 instant string, e.g. {@code 2026-10-01T00:00:00Z}.</li>
 *   <li>{@link #LIST} — a JSON array of strings.</li>
 *   <li>{@link #ENUM} — a JSON string, one of the property's allowable values.</li>
 *   <li>{@link #SECRET} — a {@code ${secrets.<name>}} reference to a secret in the tenant's flow
 *       settings; the value never appears in a definition.</li>
 * </ul>
 *
 * <p>Wherever a property allows references, a value that is exactly one {@code ${…}} reference is
 * accepted for any type and checked when it is resolved.
 */
public enum PropertyType {
    STRING, NUMBER, BOOLEAN, DURATION, INSTANT, LIST, ENUM, SECRET
}
