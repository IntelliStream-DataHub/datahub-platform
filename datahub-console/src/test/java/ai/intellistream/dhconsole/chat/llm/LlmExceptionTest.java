// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.chat.llm;

import org.junit.jupiter.api.Test;

import static ai.intellistream.dhconsole.chat.llm.LlmException.Reason.CREDENTIAL_REJECTED;
import static ai.intellistream.dhconsole.chat.llm.LlmException.Reason.NOT_FOUND;
import static ai.intellistream.dhconsole.chat.llm.LlmException.Reason.PROVIDER_ERROR;
import static ai.intellistream.dhconsole.chat.llm.LlmException.Reason.RATE_LIMITED;
import static ai.intellistream.dhconsole.chat.llm.LlmException.Reason.REQUEST_REJECTED;
import static ai.intellistream.dhconsole.chat.llm.LlmException.Reason.TIMED_OUT;
import static org.assertj.core.api.Assertions.assertThat;

class LlmExceptionTest {

    @Test
    void aStatusSaysWhoHasToAct() {
        assertThat(LlmException.Reason.forStatus(401)).isEqualTo(CREDENTIAL_REJECTED);
        assertThat(LlmException.Reason.forStatus(403)).isEqualTo(CREDENTIAL_REJECTED);
        assertThat(LlmException.Reason.forStatus(404)).isEqualTo(NOT_FOUND);
        assertThat(LlmException.Reason.forStatus(408)).isEqualTo(TIMED_OUT);
        assertThat(LlmException.Reason.forStatus(429)).isEqualTo(RATE_LIMITED);
        assertThat(LlmException.Reason.forStatus(500)).isEqualTo(PROVIDER_ERROR);
        // Anthropic's "overloaded".
        assertThat(LlmException.Reason.forStatus(529)).isEqualTo(PROVIDER_ERROR);
    }

    @Test
    void anyOtherClientErrorIsTheRequestItself() {
        // 400 covers both a model that refuses the request's settings and an account out of
        // credit, which is why the user is told it could be either.
        assertThat(LlmException.Reason.forStatus(400)).isEqualTo(REQUEST_REJECTED);
        assertThat(LlmException.Reason.forStatus(413)).isEqualTo(REQUEST_REJECTED);
        assertThat(LlmException.Reason.forStatus(422)).isEqualTo(REQUEST_REJECTED);
    }
}
