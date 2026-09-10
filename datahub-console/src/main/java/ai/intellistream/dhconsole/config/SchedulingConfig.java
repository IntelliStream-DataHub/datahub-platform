// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} methods in datahub-console.
 *
 * <p>There was no such configuration here, so every {@code @Scheduled} method the console owns or
 * inherits was silently inert — the annotation is not an error without it, it simply never runs.
 * Two things were affected:
 *
 * <ul>
 *   <li>{@code TenantConfigService.refreshCache()}, from datahub-commons, which every other service
 *       runs every five minutes. The console loaded the tenant registry once at startup and then
 *       never again, so a change to a tenant's feature flags or its model configuration reached the
 *       console only on a restart — while datahub-api, which does enable scheduling, had picked it
 *       up minutes earlier. Two services disagreeing about the same tenant, indefinitely.</li>
 *   <li>{@code ChatConfig.evictUnusedChatBackends()}, which released model clients no tenant names
 *       any more. Hygiene rather than correctness, but it never ran once.</li>
 * </ul>
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
