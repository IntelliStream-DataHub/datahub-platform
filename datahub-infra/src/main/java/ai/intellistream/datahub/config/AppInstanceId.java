// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.helpers.utils.InstanceIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The identifier for this running instance, used to make Pulsar producer/consumer names unique
 * across instances of a service (a duplicate name collides on the broker and silently breaks
 * scale-out). Set explicitly via the {@code app.id} property — e.g. distinct values for two
 * instances co-located on one host — or left empty to derive {@code <hostname>-numa<node>} from
 * {@link InstanceIdentity}.
 */
@Component
public class AppInstanceId {

    private final String id;

    public AppInstanceId(@Value("${app.id:}") String configured) {
        this.id = (configured == null || configured.isBlank()) ? InstanceIdentity.get() : configured.trim();
    }

    public String get() {
        return id;
    }
}
