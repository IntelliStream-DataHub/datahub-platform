// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ByteUnitUtil {

    private static final Set<Character> sizeUnit = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList('k', 'K', 'm', 'M', 'g', 'G', 't', 'T')));

    public static long validateSizeString(String byteStr) {
        if (byteStr.isEmpty()) {
            throw new IllegalArgumentException("byte string cannot be empty");
        }

        char last = byteStr.charAt(byteStr.length() - 1);
        String subStr = byteStr.substring(0, byteStr.length() - 1);
        long size;
        try {
            size = sizeUnit.contains(last)
                    ? Long.parseLong(subStr)
                    : Long.parseLong(byteStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(String.format("Invalid size '%s'. Valid formats are: %s",
                    byteStr, "(4096, 100K, 10M, 16G, 2T)"));
        }
        return switch (last) {
            case 'k', 'K' -> size * 1024;
            case 'm', 'M' -> size * 1024 * 1024;
            case 'g', 'G' -> size * 1024 * 1024 * 1024;
            case 't', 'T' -> size * 1024 * 1024 * 1024 * 1024;
            default -> size;
        };
    }
}
