// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.BadRequestExceptionHandler;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.datasecurity.OrgGroupResolver;
import ai.intellistream.datahub.api.datasecurity.TestDataSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-layer tests for {@link MeController}: the shapes a service acting for the caller relies on. */
class MeControllerTest {

    private final OrgGroupResolver orgGroupResolver = mock(OrgGroupResolver.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc(DataSecurity dataSecurity) {
        return MockMvcBuilders.standaloneSetup(new MeController(orgGroupResolver, dataSecurity))
                .setControllerAdvice(new BadRequestExceptionHandler())
                .build();
    }

    private static void authenticate(String... authorities) {
        Jwt jwt = Jwt.withTokenValue("raw")
                .header("alg", "none")
                .subject("user-1")
                .issuedAt(Instant.ofEpochSecond(1))
                .expiresAt(Instant.ofEpochSecond(9_999_999))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, TestDataSecurity.authorities(authorities)));
    }

    @Test
    void grantsListEveryGroupUnfiltered() throws Exception {
        authenticate("ROLE_DATAHUB_ACCESS");
        when(orgGroupResolver.groupsForCurrentCaller())
                .thenReturn(List.of("/datasets/ds_a/read", "/flows/write", "/some/other/group"));

        mvc(TestDataSecurity.grantingNothing()).perform(get("/me/grants"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"admin":false,"groups":["/datasets/ds_a/read","/flows/write","/some/other/group"]}""",
                        true));
    }

    @Test
    void grantsReportAdminFromTheTokenEvenWithoutGroups() throws Exception {
        authenticate("ROLE_DATAHUB_ACCESS", DataSecurity.ADMIN_AUTHORITY);
        when(orgGroupResolver.groupsForCurrentCaller()).thenReturn(List.of());

        mvc(TestDataSecurity.readingAndWritingEverything()).perform(get("/me/grants"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"admin\":true,\"groups\":[]}", true));
    }

    @Test
    void explicitGrantsListSortedIdsAsStrings() throws Exception {
        DataSecurity granted = TestDataSecurity.granting(Set.of(30L, 10L, 20L), Set.of(20L));

        mvc(granted).perform(get("/me/datasets").param("access", "read"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"all\":false,\"ids\":[\"10\",\"20\",\"30\"]}", true));
        mvc(granted).perform(get("/me/datasets").param("access", "write"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"all\":false,\"ids\":[\"20\"]}", true));
    }

    @Test
    void wildcardAnswersAllWithNoIds() throws Exception {
        DataSecurity readAll = TestDataSecurity.readingEverything();

        mvc(readAll).perform(get("/me/datasets").param("access", "read"))
                .andExpect(content().json("{\"all\":true,\"ids\":[]}", true));
        // Read and write are independent: a read wildcard says nothing about write.
        mvc(readAll).perform(get("/me/datasets").param("access", "write"))
                .andExpect(content().json("{\"all\":false,\"ids\":[]}", true));
    }

    @Test
    void adminAnswersAllForBoth() throws Exception {
        DataSecurity admin = TestDataSecurity.backedBy(DatasetPermissions.allDatasets());

        mvc(admin).perform(get("/me/datasets").param("access", "write"))
                .andExpect(content().json("{\"all\":true,\"ids\":[]}", true));
    }

    @Test
    void unknownOrMissingAccessIsABadRequest() throws Exception {
        MockMvc mvc = mvc(TestDataSecurity.grantingNothing());

        mvc.perform(get("/me/datasets").param("access", "admin")).andExpect(status().isBadRequest());
        mvc.perform(get("/me/datasets")).andExpect(status().isBadRequest());
    }
}
