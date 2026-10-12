// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.api.controllers;

import ai.intellistream.datahub.api.controllers.errors.BadRequestException;
import ai.intellistream.datahub.api.datasecurity.DataSecurity;
import ai.intellistream.datahub.api.datasecurity.DatasetPermissions;
import ai.intellistream.datahub.api.datasecurity.OrgGroupResolver;
import ai.intellistream.datahub.models.me.MeDataSets;
import ai.intellistream.datahub.models.me.MeGrants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * What the caller may do, answered for the caller's own token.
 *
 * <p>For services that act for a user and must respect the same dataset access the api enforces —
 * the flow service filters its lists by it — without evaluating grants themselves. The grants are
 * resolved here, by the same resolvers every other endpoint uses, so there is one evaluator.
 * Deliberately ungated beyond the baseline role: answering a caller about themselves discloses
 * nothing.
 */
@RestController
@RequestMapping("/me")
@AllArgsConstructor
@Tag(name = "Me", description = """
        What your token may do in your tenant: the organization groups you hold, and the
        datasets you may read or write. Useful for a service acting on your behalf that has to
        respect the same access the API enforces.""")
public class MeController {

    private final OrgGroupResolver orgGroupResolver;
    private final DataSecurity dataSecurity;

    @Operation(summary = "Get your organization groups",
            description = """
                    Every organization group path you hold in this tenant, unfiltered, and
                    whether you hold the `DATAHUB_ADMIN` realm role. That role is read from your
                    token and is never a group, so an admin may list no groups and still reach
                    everything.
                    """)
    @GetMapping("/grants")
    public ResponseEntity<MeGrants> grants() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = authentication != null && DatasetPermissions.isAdmin(authentication.getAuthorities());
        return ResponseEntity.ok(new MeGrants(admin, orgGroupResolver.groupsForCurrentCaller()));
    }

    @Operation(summary = "Get the datasets you may read or write",
            description = """
                    `all` is true when you may read (or write) every dataset in the tenant, and
                    `ids` is then empty. Otherwise `ids` lists the datasets you may, with a grant
                    on a parent dataset expanded to every dataset beneath it. Check `all` first:
                    an empty `ids` on its own means no access.
                    """)
    @GetMapping("/datasets")
    public ResponseEntity<MeDataSets> dataSets(
            @Parameter(description = "Which access to answer for: `read` or `write`.", example = "write")
            @RequestParam("access") String access) {
        return ResponseEntity.ok(switch (access) {
            case "read" -> dataSetsWith(dataSecurity.hasReadAccessToEverything(), dataSecurity.readableDataSetIds());
            case "write" -> dataSetsWith(dataSecurity.hasWriteAccessToEverything(), dataSecurity.writableDataSetIds());
            default -> throw new BadRequestException("Unknown access '" + access + "'",
                    "access", "must be read or write");
        });
    }

    private static MeDataSets dataSetsWith(boolean all, Set<Long> ids) {
        return all ? new MeDataSets(true, List.of()) : new MeDataSets(false, ids.stream().sorted().toList());
    }
}
