// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.util;

import ai.intellistream.datahub.api.controllers.errors.BadRequestException;

import java.util.Collection;

public class CollectionValidation {

    public static void validateMaxSize(int maxSize, Collection<?> collection) {
        if(collection.size() > maxSize) {
            throw new BadRequestException("Max collection limit : %s".formatted(maxSize));
        }
    }
}
