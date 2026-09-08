// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the console enables scheduling at all.
 *
 * <p>Worth a test because its absence is silent: {@code @Scheduled} without {@code @EnableScheduling}
 * is not an error, the method simply never runs. The console went without it, so the tenant registry
 * refresh it inherits from datahub-commons never fired and the console served a startup snapshot of
 * every tenant's configuration for the life of the process — while datahub-api, which does enable
 * scheduling, refreshed every five minutes. Nothing failed; the two just disagreed forever.
 */
class SchedulingEnabledTest {

    @Test
    void theConsoleRunsScheduledMethods() {
        assertThat(SchedulingConfig.class.getAnnotation(EnableScheduling.class))
                .as("without this the tenant registry never refreshes after startup")
                .isNotNull();
    }
}
