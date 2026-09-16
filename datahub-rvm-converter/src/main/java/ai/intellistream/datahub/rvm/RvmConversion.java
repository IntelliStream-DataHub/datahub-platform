// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.rvm;

/**
 * A converted model: the GLB bytes, and the converter's own log, which carries the primitive and
 * triangle counts worth recording alongside the result.
 */
public record RvmConversion(byte[] glb, String log) {
}
