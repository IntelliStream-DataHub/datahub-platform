// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.datasecurity;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Builds a {@link DataSecurity} over a fixed permission set, for tests of other services that only
 * need "this caller can/cannot touch dataset N".
 *
 * <p>Grants used to be encoded in realm-role strings, so tests stated them by putting
 * {@code ROLE_DATAHUB_DATASET_READ_7} in the SecurityContext. Per-dataset grants now come from
 * Keycloak organization groups resolved through Valkey and Postgres, which is far too much
 * machinery to stand up in a unit test — and would only be re-deriving the id set the test already
 * knows. Stating the ids directly is both simpler and clearer about what is being asserted.
 */
public final class TestDataSecurity {

    private TestDataSecurity() {
    }

    /** A caller granted exactly these dataset ids, with no blanket grants. */
    public static DataSecurity granting(Set<Long> readableIds, Set<Long> writableIds) {
        return backedBy(DatasetPermissions.of(false, false, readableIds, writableIds));
    }

    /** A caller holding the {@code /datasets/*&#47;read} wildcard grant only. */
    public static DataSecurity readingEverything() {
        return backedBy(DatasetPermissions.of(true, false, Set.of(), Set.of()));
    }

    /** A caller holding the {@code /datasets/*&#47;write} wildcard grant only. */
    public static DataSecurity writingEverything() {
        return backedBy(DatasetPermissions.of(false, true, Set.of(), Set.of()));
    }

    /** A caller holding both wildcard grants — the same answer {@code DATAHUB_ADMIN} resolves to. */
    public static DataSecurity readingAndWritingEverything() {
        return backedBy(DatasetPermissions.allDatasets());
    }

    /** A caller with no grants at all. */
    public static DataSecurity grantingNothing() {
        return backedBy(DatasetPermissions.none());
    }

    /** A {@link DataSecurity} whose resolver always returns {@code permissions}. */
    public static DataSecurity backedBy(DatasetPermissions permissions) {
        return backedBy(() -> permissions);
    }

    /**
     * A {@link DataSecurity} that reads its permissions from {@code supplier} on every call, so a
     * test can change the caller's grants after the service under test has already been constructed
     * with it.
     */
    public static DataSecurity backedBy(Supplier<DatasetPermissions> supplier) {
        DatasetPermissionsResolver resolver = mock(DatasetPermissionsResolver.class);
        when(resolver.forCurrentRequest()).thenAnswer(invocation -> supplier.get());
        return new DataSecurity(resolver);
    }

    public static List<GrantedAuthority> authorities(String... roles) {
        return Arrays.stream(roles)
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r))
                .toList();
    }
}
