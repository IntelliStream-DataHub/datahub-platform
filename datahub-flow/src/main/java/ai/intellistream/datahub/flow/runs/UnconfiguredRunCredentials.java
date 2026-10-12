// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.runs;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * The run identity before deploying as an OAuth authorisation exists: there is no token to act
 * with, so every run fails with {@link RunCoordinator#AUTH} and says why. Replaced when runs act as
 * their deployer or a registered client.
 */
@Component
class UnconfiguredRunCredentials implements RunCredentials {

    @Override
    public Supplier<String> tokens(String tenantId, String runAs) {
        throw new RunIdentityException("No identity configured: runs cannot act as their deployer yet.");
    }
}
