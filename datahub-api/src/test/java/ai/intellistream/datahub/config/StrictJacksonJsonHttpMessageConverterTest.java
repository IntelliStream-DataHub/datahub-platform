// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.api.controllers.errors.UnknownRequestFieldsException;
import ai.intellistream.datahub.api.controllers.errors.UnknownRequestFieldsException.UnknownField;
import ai.intellistream.datahub.models.EventModel;
import ai.intellistream.datahub.models.validation.EventFields;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.mock.http.MockHttpInputMessage;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Collecting every unknown field, rather than throwing on the first, and locating each one.
 *
 * <p>Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES} stops at the first offender, so a body with three
 * stale fields costs three round trips — and which one the caller hears about depends on the order
 * they happen to sit in the JSON.
 */
class StrictJacksonJsonHttpMessageConverterTest {

    private final StrictJacksonJsonHttpMessageConverter converter =
            new StrictJacksonJsonHttpMessageConverter(
                    JsonMapper.builder().addHandler(new UnknownFieldCollector()).build());

    private void read(Class<?> type, String json) throws IOException {
        converter.read(ResolvableType.forClass(type), new MockHttpInputMessage(json.getBytes()), null);
    }

    private UnknownRequestFieldsException rejectionFor(Class<?> type, String json) {
        return catchThrowableOfType(UnknownRequestFieldsException.class, () -> read(type, json));
    }

    /**
     * The regression this class caused: Spring AI's MCP transport reads the body with
     * {@code ServerRequest.body(String.class)} and parses the JSON-RPC envelope itself. When this
     * converter claimed that read it handed the JSON object to Jackson as a {@code String}, and
     * every request to {@code POST /mcp} failed with a 500 — for every MCP client, not just the
     * console chat.
     */
    @Test
    void aRawTextReadIsLeftToTheStringConverter() {
        assertThat(converter.canRead(ResolvableType.forClass(String.class), MediaType.APPLICATION_JSON))
                .isFalse();
        assertThat(converter.canRead(ResolvableType.forClass(CharSequence.class), MediaType.APPLICATION_JSON))
                .isFalse();
    }

    /**
     * The declining above is by target type, not by media type or endpoint: a body we do define the
     * contract for is still read, and still checked.
     */
    @Test
    void aTypedBodyIsStillOurs() {
        assertThat(converter.canRead(ResolvableType.forClass(EventFields.class), MediaType.APPLICATION_JSON))
                .isTrue();
    }

    @Test
    void aBodyWithOnlyValidFieldsIsAccepted() {
        assertThatCode(() -> read(EventFields.class, "{\"description\":{\"set\":\"x\"}}"))
                .doesNotThrowAnyException();
    }

    @Test
    void everyUnknownFieldIsReportedNotJustTheFirst() {
        assertThat(rejectionFor(EventFields.class, "{\"alpha\":1,\"description\":{\"set\":\"x\"},\"omega\":2}")
                .getUnknownFields())
                .extracting(UnknownField::pointer)
                .containsExactly("#/alpha", "#/omega");
    }

    /**
     * Document order, not alphabetical: it mirrors the body the caller sent, so they can scan their
     * own payload top to bottom.
     */
    @Test
    void theyAreReportedInTheOrderTheyAppearInTheBody() {
        assertThat(rejectionFor(EventFields.class, "{\"omega\":2,\"alpha\":1}").getUnknownFields())
                .extracting(UnknownField::pointer).containsExactly("#/omega", "#/alpha");
        assertThat(rejectionFor(EventFields.class, "{\"alpha\":1,\"omega\":2}").getUnknownFields())
                .extracting(UnknownField::pointer).containsExactly("#/alpha", "#/omega");
    }

    /**
     * The hint lists what the rejecting type actually accepts, which is how a caller spots their own
     * typo. Deliberately asserted against fields that exist today: {@code eventTime} was removed from
     * the update form in #325 (an event's time is immutable), so naming it here would be asserting
     * the hint offers a field the API would then reject.
     */
    @Test
    void theAllowedFieldsComeFromTheRejectingPosition() {
        assertThat(rejectionFor(EventFields.class, "{\"externlaId\":1}").getUnknownFields())
                .singleElement()
                .extracting(UnknownField::allowed).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.collection(String.class))
                .contains("externalId", "description", "metadata");
    }

    /** A nested offender is located by path, not by a bare name that could be either level. */
    @Test
    void aNestedUnknownFieldIsPointedAtByPath() {
        assertThat(rejectionFor(EventFields.class,
                "{\"alpha\":1,\"description\":{\"set\":\"x\",\"bogus\":9}}").getUnknownFields())
                .extracting(UnknownField::pointer)
                .containsExactly("#/alpha", "#/description/bogus");
    }

    /** Inside a collection the pointer carries the index, so the caller knows which element. */
    @Test
    void anUnknownFieldInsideAnArrayCarriesItsIndex() {
        assertThat(rejectionFor(EventModel.class,
                "{\"relatedResources\":[{\"id\":\"1\"},{\"id\":\"2\",\"junk\":3}]}").getUnknownFields())
                .extracting(UnknownField::pointer)
                .containsExactly("#/relatedResources/1/junk");
    }

    /**
     * Tomcat reuses worker threads, so a rejected body must not leave entries behind for whatever
     * request lands on this thread next — the same hazard as TenantContext.
     */
    @Test
    void theCollectorIsClearedBetweenReads() {
        assertThat(rejectionFor(EventFields.class, "{\"alpha\":1}")).isNotNull();

        assertThatCode(() -> read(EventFields.class, "{\"description\":{\"set\":\"x\"}}"))
                .doesNotThrowAnyException();

        assertThat(rejectionFor(EventFields.class, "{\"omega\":1}").getUnknownFields())
                .extracting(UnknownField::pointer).containsExactly("#/omega");
    }
}
