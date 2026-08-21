// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled} methods across datahub-api (e.g. DatapointIngestCounter's flush). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
