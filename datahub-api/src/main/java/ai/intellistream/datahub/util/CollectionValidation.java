// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.util;

import ai.intellistream.datahub.api.controllers.errors.BadRequestError;
import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.errors.ResponseError;

import java.util.Collection;

public class CollectionValidation {

    public static void validateMaxSize(int maxSize, Collection<?> collection) {
        if(collection.size() > maxSize) {
            ResponseError<BadRequestError> errors = new ResponseError<>();
            var de = new BadRequestError();
            de.setMessage("Max collection limit : %s".formatted(maxSize));
            errors.setError(de);
            throw new BadRequestException(errors);
        }
    }
}
