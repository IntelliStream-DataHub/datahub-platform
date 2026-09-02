# Data Set ACLs

What dataset permissions gate, and how a caller gets them.

All dataset grants — per-dataset and all-datasets alike — are organization group paths carried in
the caller's `organization` claim; the only realm role left in the dataset ACL is `DATAHUB_ADMIN`,
the cross-tenant operator escape hatch. Keycloak is the source of truth and nothing about access is
administered in DataHub.

Two supported ways to produce that claim, both verified: Keycloak's **Organizations** feature
(delegable to a tenant admin, federates from a customer directory) or a **protocol mapper** over a
user attribute (simpler, centrally administered). The API reads the claim and does not care which.

- **How to configure the Keycloak side:** [KEYCLOAK_ORG_GROUPS.md](KEYCLOAK_ORG_GROUPS.md).
- **What the grants gate:** this document.

Implementation: `ai.intellistream.datahub.api.datasecurity` — `DatasetGrants` (group-path grammar),
`OrgGroupResolver` (reads them from UserInfo), `DatasetClosureService` (hierarchy expansion),
`DatasetPermissionsResolver` (assembles), `DataSecurity` (the checks below).

> **Superseded:** the id-bearing realm roles `DATAHUB_DATASET_READ_<id>` and
> `DATAHUB_DATASET_WRITE_<id>` are **no longer read at all**. They were realm-global while dataset
> ids are per-tenant, so the same role meant different things in different organizations. The
> blanket roles `DATAHUB_DATASET_ALL`, `DATAHUB_DATASET_READ_ALL` and `DATAHUB_DATASET_WRITE_ALL`
> followed them out: they had no id-collision problem, but a realm role travels on every token a
> multi-organization user can mint, so "all datasets" quietly spanned tenants. Assign organization
> groups instead — the `/datasets/*` wildcard for all-datasets access. (An older claim-based ACL,
> `datahub_allowed_dataset_ids` / `datahub_access_all_datasets`, was dropped before all of that and
> is likewise ignored.)

## Grant grammar

### Organization groups

Group paths are relative to the caller's organization, so the tenant is implicit:

```
/datasets/<externalId>/read
/datasets/<externalId>/write
/datasets/*/read          # every dataset in the organization
/datasets/*/write
```

`<externalId>` is the dataset's `externalId`, not its numeric id. It is stable, readable in both
the Keycloak admin console and the DataHub UI, and cannot collide across tenants. The segment is
matched **verbatim** against the stored external id (case-insensitively, matching external-id
uniqueness); nothing is normalised, so the group must name the dataset exactly as it is stored.

The `*` segment is the all-datasets grant. Because it is an organization group, it covers all
datasets *of that organization* and nothing else — which is precisely why it is not a realm role.
It cannot collide with a real dataset: external ids are restricted to `[A-Za-z0-9._:+=-]+`, which
does not admit an asterisk.

Group paths that do not match this shape are ignored rather than rejected: an organization's group
tree is theirs and may hold groups with nothing to do with DataHub.

### Grants inherit down the dataset hierarchy

Datasets form a hierarchy through `connectedDataSets` (stored as `BELONGS_TO` edges). **A grant on
a dataset covers every dataset beneath it.** Granting the root of a tree covers the whole tree, so
the number of groups an administrator maintains stays proportional to access domains rather than to
dataset count.

Inheritance travels **downward only**: holding a leaf grants nothing on its parents. The `*`
wildcard needs no expansion at all — it is a flag, not a walk of the tree.

### The operator escape hatch: `DATAHUB_ADMIN`

One realm role remains in the dataset ACL:

| Realm role | Grants |
| --- | --- |
| `DATAHUB_ADMIN` | Read **and** write every dataset, in **every** tenant the token can address. |

Deliberately cross-tenant, and deliberately a realm role: it is resolved from the token alone, so
operator access never depends on the UserInfo endpoint or Valkey being reachable — exactly the
components an operator may be logging in to investigate. `SecurityConfig` maps every realm role to
a Spring authority by prefixing it with `ROLE_`.

Everything a customer administers — including all-datasets access — is organization groups, which
an organization admin can manage (or federate from their own directory) without realm-admin
rights.

### Managing datasets needs the all-datasets write grant

Creating, updating or deleting a **dataset itself** requires an all-datasets write grant: the
`/datasets/*/write` organization group, or `DATAHUB_ADMIN`. A grant on individual datasets,
however many, never confers it.

This is deliberate and stricter than it strictly needs to be. A dataset is the unit access is
granted on, so creating one, renaming its `externalId` or moving it in the hierarchy changes what
every existing grant covers. Re-parenting in particular is what makes it security-relevant: a
dataset moved under a widely-granted root becomes visible to everyone holding that root.

Since the wildcard group is organization-scoped, this is delegable per tenant: an organization's
own data steward can hold `/datasets/*/write` and manage that organization's dataset tree without
touching any other tenant. Enforced by `DataSecurity.assertCanManageDataSets()` at the
`/datasets/create`, `/datasets/update` and `/datasets/delete` endpoints.

### Read and write are independent

A write grant grants **only** write — it does **not** imply read. A caller needing both must hold
both. This is intentional so an ingestion service account can write without reading back. Note the
consequence for relationships, below.

### Propagation

Grants are read from the UserInfo endpoint and cached, so a change in Keycloak takes effect within
about a minute rather than waiting for the caller's token to expire. If the identity provider is
unreachable for longer than the stale window, requests fail closed with **503** (not 403: "we could
not verify your permissions" is not "you have none").

## What is enforced

Permissions gate the dataset-bearing entities: **resources, timeseries, events and files**.

- **Reads** are filtered in **SQL**: list / search / by-id endpoints add a
  `WHERE data_set_id IN (<readable ids>)` clause (ClickHouse for events, JPA/native for the rest),
  so rows in datasets the caller can't read are simply omitted — matching the existing
  "missing items are silently left out" contract. Single-item reads (`GET /resources/{id}`,
  graph traversal start node) return `403` when denied.
- **Writes** (create / update / delete, timeseries data-point insert/delete, file upload/delete)
  throw `AccessDeniedException` → HTTP `403` when the caller lacks write permission to the target
  dataset. Moving an entity into a different dataset additionally requires write access to the
  destination dataset.

### Relationships (edges)

An edge has no dataset of its own, so it is authorised on its two endpoint nodes: creating,
re-pointing or deleting a relationship requires **write access to both endpoints**. This applies to
the `relations[]` array on `/resources/create` and `/resources/update`, and to `/edges/delete`.

Reading an edge is gated on the same axis: `GET /edges/{id}`, `POST /edges/byids` and the MCP
`edge_get` tool require **read access to both endpoints**: an edge necessarily reveals both ends,
and `/edges/byids` returns the endpoint nodes in full. Denied edges are silently left out of
`/edges/byids` like unknown ids; `GET /edges/{id}` reports 404, keeping an unreadable edge
indistinguishable from a missing one. An edge with a dangling endpoint fails closed.

Requiring *both* is deliberate. Because read and write are independent grants, a caller with write
but no read on a resource (the shape of an ingest service account) could otherwise attach it
beneath a dataset they *can* read and inherit read on it through the dataset `BELONGS_TO`
hierarchy. Requiring write on both ends also stops a caller linking arbitrary resources into a
dataset they cannot write at all.

Re-pointing an edge via `RelFields.start` / `.end` is checked against the **old** endpoints as well
as the new ones, since the mutation changes the graph at both.

Edges removed as a *cascade* of deleting a node are not re-checked: the node itself was already
authorised, and demanding write on the far end too would block a legitimate delete of a resource
that merely links into a dataset the caller cannot write.

A denial returns an RFC 9457 `application/problem+json` 403 body (see
`AccessDeniedExceptionHandler`), e.g.:

```json
{
  "type": "https://intellistream.ai/errors/dataset-forbidden",
  "title": "Forbidden",
  "status": 403,
  "detail": "No read permission for data set: 21",
  "dataSetId": 21,
  "permission": "read"
}
```

### Orphan entities (no dataset)

**Resources, timeseries, events:** an entity with no dataset (`data_set_id` null) can only be read
by a read-all caller and only be written/created by a write-all caller. Creating one with no
`dataSetId` therefore requires the `/datasets/*/write` grant (or `DATAHUB_ADMIN`).

**Files and folders are different — no dataset means _public_:** a file or folder with no dataset is
visible to, and writable by, everyone with file access. Datasets gate file/folder access only when a
dataset is set. Folders are created implicitly when a file is uploaded into a path, and they
**inherit the uploaded file's dataset** (`DirectoryService.createDirectoriesFromPath`), so a folder
is gated by the same dataset as the files placed in it; a file uploaded with no `dataSet` field
creates public folders.

Uploading also requires write access to the **containing folder's** dataset: the upload is placed
in the deepest folder that already exists on the target path (a new leaf folder is created inside
it), and if that folder belongs to a dataset the caller can't write, the upload is rejected with
403 — even when the caller can write the file's own dataset. Public/no-dataset folders (e.g. the
root) stay open to everyone. So a caller who can write dataset 66 but not 55 cannot upload into
`/org-a/team-b/...` when `team-b` belongs to dataset 55.

(Known limitation: deleting a folder cascades to its children, but the write check validates only
the directly targeted nodes, not each descendant.)

### Known limitations

- **Resource search across datasets:** datasets themselves have no `data_set_id`, so for non-all
  readers dataset rows are excluded from `/resources/search`; other node types are dataset-filtered.
- **Graph traversal** (`/resources/fetch-related`) gates on read access to the *starting* resource's
  dataset; the reachable network returned by Neo4j is not itself dataset-filtered.

## Keycloak configuration

See [KEYCLOAK_ORG_GROUPS.md](KEYCLOAK_ORG_GROUPS.md) for the full setup, including the
configuration traps that silently produce an empty claim. In outline:

1. Enable Organizations on the realm, one organization per tenant.
2. Create the group tree under the organization: `datasets` → `<externalId>` → `read` / `write`.
3. Add the principal as an organization **member**, then to the grant groups. Membership is what
   puts the `organization` claim in the token, so a principal that is not a member is rejected
   with `401 invalid_token` — not merely left without access. A member holding no grant groups
   authenticates fine and sees empty results.
4. For all-datasets access, use the wildcard subtree: `datasets` → `*` → `read` / `write`.

### Verify

The access token carries the tenant but **not** the grants:

```json
{
  "realm_access": { "roles": ["DATAHUB_ACCESS"] },
  "organization": { "acme": { "id": "f9cf24ba-6f97-46dd-8330-d205851d983d" } }
}
```

The grants come from UserInfo (`GET /realms/<realm>/protocol/openid-connect/userinfo`):

```json
{
  "organization": {
    "acme": {
      "id": "f9cf24ba-6f97-46dd-8330-d205851d983d",
      "groups": ["/datasets/data_set_sap/read", "/datasets/data_set_sap/write"]
    }
  }
}
```

With the above, the caller reads and writes `data_set_sap` and every dataset beneath it, and
nothing else.

## Adding new permission checks

When guarding a new endpoint or service method:

```java
// Single-item read / write asserts (throw AccessDeniedException → 403):
dataSecurity.assertCanRead(node);
dataSecurity.assertCanWriteDataSet(dataSetId);

// List endpoints — narrow the query in SQL instead of filtering after load:
if (dataSecurity.hasReadAccessToEverything()) {
    // unfiltered query
} else {
    Set<Long> allowed = dataSecurity.readableDataSetIds();
    if (allowed.isEmpty()) return DataWrapper.empty();
    // query variant with: WHERE data_set_id IN (:allowed)
}
```

## Settings groups are a separate vocabulary

`/settings/read` and `/settings/write` gate a tenant's own configuration — today its agent
definitions. They are organization groups like the dataset grants and live in the same tree, but
nothing in this document applies to them: no wildcard, no hierarchy, no expansion, and
`DatasetGrants` ignores them entirely. See `SettingsGrants`.

They exist because configuring an assistant used to require an all-datasets write grant, which
made "may curate agents" a consequence of "may write every row in the tenant". Configuration and
data are different powers.
