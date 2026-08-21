// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.json;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Accept a bare value where a list is declared, so {@code "source": "sap"} means the same as
 * {@code "source": ["sap"]}.
 *
 * <p>Filter fields went plural because one value was rarely enough, but most calls still pass one.
 * Making the single form a syntax error would tax the common case to serve the rare one, and it is
 * the kind of error that reads as the API being fussy rather than as the caller being wrong.
 *
 * <p>Declared as a Jackson bundle annotation rather than switching
 * {@code ACCEPT_SINGLE_VALUE_AS_ARRAY} on globally, for two reasons. It travels with the DTO, so the
 * Java SDK and anything else consuming {@code datahub-api-model} behave the same way as the API
 * without configuring their own mapper — a global mapper setting would reach none of them. And it
 * stays opt-in per field: the leniency is right for a filter's pattern list and wrong for a
 * request whose list length is meaningful, where collapsing a stray scalar would hide a mistake.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@JacksonAnnotationsInside
@JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
public @interface SingleOrList {
}
