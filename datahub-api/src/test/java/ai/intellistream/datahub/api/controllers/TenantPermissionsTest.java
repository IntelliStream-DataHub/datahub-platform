// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.SettingsSecurity;
import ai.intellistream.datahub.tenant.CallerPermissions;
import ai.intellistream.datahub.tenant.TenantConfigService;
import ai.intellistream.datahub.tenant.TenantLlmStore;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /tenant/permissions} — the answer a client needs to gate its UI, and the one an
 * agent needs to decide which tools are worth offering a model.
 *
 * <p>The endpoint is a straight projection of {@link DataSecurity}, so what is worth testing is
 * not the arithmetic but the shape: that every field is carried across unchanged, and that the
 * "empty means everything or nothing" trap is expressible by a caller who reads the record
 * correctly.
 */
class TenantPermissionsTest {

    private final DataSecurity dataSecurity = mock(DataSecurity.class);
    private final SettingsSecurity settingsSecurity = mock(SettingsSecurity.class);
    private final TenantController controller = new TenantController(
            mock(TenantConfigService.class), dataSecurity, settingsSecurity,
            mock(TenantLlmStore.class));

    private CallerPermissions permissions() {
        return controller.getPermissions().getBody();
    }

    @Test
    void reportsPerDatasetGrantsAsTheIdsTheyExpandTo() {
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.hasWriteAccessToEverything()).thenReturn(false);
        when(dataSecurity.canManageDataSets()).thenReturn(false);
        when(dataSecurity.readableDataSetIds()).thenReturn(Set.of(1L, 2L, 3L));
        when(dataSecurity.writableDataSetIds()).thenReturn(Set.of(2L));

        CallerPermissions permissions = permissions();

        assertThat(permissions.readAll()).isFalse();
        assertThat(permissions.readableDataSetIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(permissions.writableDataSetIds()).containsExactly(2L);
        assertThat(permissions.canReadNothing()).isFalse();
        assertThat(permissions.canWriteNothing()).isFalse();
    }

    @Test
    void anAdministratorReportsReadAllWithNoIdsRatherThanEveryId() {
        // The trap: DataSecurity returns an EMPTY set for a caller who can read everything,
        // because enumerating every dataset would be wasted work. A client reading the ids alone
        // would conclude the opposite of the truth.
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(true);
        when(dataSecurity.hasWriteAccessToEverything()).thenReturn(true);
        when(dataSecurity.canManageDataSets()).thenReturn(true);
        when(dataSecurity.readableDataSetIds()).thenReturn(Set.of());
        when(dataSecurity.writableDataSetIds()).thenReturn(Set.of());

        CallerPermissions permissions = permissions();

        assertThat(permissions.readAll()).isTrue();
        assertThat(permissions.readableDataSetIds()).isEmpty();
        assertThat(permissions.canReadNothing()).isFalse();
        assertThat(permissions.canWriteNothing()).isFalse();
    }

    @Test
    void aCallerWithNoGrantsAtAllIsDistinguishableFromAnAdministrator() {
        // Same empty id sets as the test above; only the flags tell them apart.
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.hasWriteAccessToEverything()).thenReturn(false);
        when(dataSecurity.canManageDataSets()).thenReturn(false);
        when(dataSecurity.readableDataSetIds()).thenReturn(Set.of());
        when(dataSecurity.writableDataSetIds()).thenReturn(Set.of());

        CallerPermissions permissions = permissions();

        assertThat(permissions.canReadNothing()).isTrue();
        assertThat(permissions.canWriteNothing()).isTrue();
    }

    @Test
    void writeAccessDoesNotImplyReadAccess() {
        // Deliberate in the ACL model, so an ingest identity can write without reading back.
        when(dataSecurity.hasReadAccessToEverything()).thenReturn(false);
        when(dataSecurity.hasWriteAccessToEverything()).thenReturn(true);
        when(dataSecurity.canManageDataSets()).thenReturn(true);
        when(dataSecurity.readableDataSetIds()).thenReturn(Set.of());
        when(dataSecurity.writableDataSetIds()).thenReturn(Set.of());

        CallerPermissions permissions = permissions();

        assertThat(permissions.canReadNothing()).isTrue();
        assertThat(permissions.canWriteNothing()).isFalse();
    }

    @Test
    void reportsTheSettingsGrantsSoAClientCanHideWhatItCannotUse() {
        // Configuration is a different power from data, so it is reported separately rather than
        // inferred from the dataset flags. A client uses it to decide whether to show a settings
        // page at all; the api enforces it regardless of what the client did.
        when(dataSecurity.readableDataSetIds()).thenReturn(Set.of());
        when(dataSecurity.writableDataSetIds()).thenReturn(Set.of());
        when(settingsSecurity.canReadSettings()).thenReturn(true);
        when(settingsSecurity.canWriteSettings()).thenReturn(false);

        CallerPermissions permissions = permissions();

        assertThat(permissions.canReadSettings()).isTrue();
        assertThat(permissions.canWriteSettings()).isFalse();
    }

    @Test
    void nullIdSetsReadAsNoAccessRatherThanThrowing() {
        // Defensive: a deserialized instance from an older client may carry nulls.
        CallerPermissions permissions =
                new CallerPermissions(false, false, false, null, null, false, false);

        assertThat(permissions.canReadNothing()).isTrue();
        assertThat(permissions.canWriteNothing()).isTrue();
    }
}
