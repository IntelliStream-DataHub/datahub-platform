// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.helpers.text;


public class HumanReadableHelper {

    public static String getSize(Long size) {
        if (size == null || size < 0) {
            return "Invalid Size";
        }

        final long KILOBYTE = 1024;
        final long MEGABYTE = KILOBYTE * 1024;
        final long GIGABYTE = MEGABYTE * 1024;
        final long TERABYTE = GIGABYTE * 1024;
        final long PETABYTE = TERABYTE * 1024;

        if (size < KILOBYTE) {
            return size + " B";
        } else if (size < MEGABYTE) {
            return String.format("%.2f KB", (double) size / KILOBYTE);
        } else if (size < GIGABYTE) {
            return String.format("%.2f MB", (double) size / MEGABYTE);
        } else if (size < TERABYTE) {
            return String.format("%.2f GB", (double) size / GIGABYTE);
        } else if (size < PETABYTE) {
            return String.format("%.2f TB", (double) size / TERABYTE);
        } else {
            return String.format("%.2f PB", (double) size / PETABYTE);
        }
    }
}
