# Producing the dataset-ACL claim in Keycloak

Per-dataset access control, replacing the id-bearing realm roles previously documented in
[DATASET_ACL_SETUP.md](DATASET_ACL_SETUP.md) (which now covers what the grants gate).

> **Status: implemented.** Every command and claim shape below was run against a live
> `quay.io/keycloak/keycloak:26.7` and the output pasted back unedited. The API side reads these
> groups: `DatasetGrants` parses the paths, `OrgGroupResolver` fetches them, `DatasetClosureService`
> expands the hierarchy, and `DatasetPermissionsResolver` assembles the result. `DatasetPermissions`
> no longer recognises the id-bearing `DATAHUB_DATASET_READ_<id>` roles at all.

## Version requirements

| Keycloak | Status |
| --- | --- |
| 26.7 | What everything here was verified against, and what the dev stack pins (`quay.io/keycloak/keycloak:26.7`). |
| 26.6 | Minimum on paper: introduced organization groups and the `oidc-organization-group-membership-mapper` the whole dataset ACL stands on. Not tested here. |
| 26.0 to 26.5 | Not usable for the ACL: Organizations exists (the tenant claim works) but organization groups do not, so there is nowhere to put a grant. |

The 26.7 `addGroupRoleMappings` mapper option is deliberately left off (see below), so nothing
requires 26.7 beyond it being the verified version.

## Why

Realm roles are realm-global, but dataset ids are per-tenant (each organization has its own
Postgres schema, so dataset 56 exists in every tenant). A role named `DATAHUB_DATASET_READ_56`
therefore means something different in every organization, and a user who belongs to two
organizations carries it into both. On top of that, granting access needs realm-admin rights, so a
tenant admin cannot manage their own team's access.

Organization groups fix all three: the grant is scoped to one organization by construction, it
appears in the claim nested under that organization, and it can be delegated to a tenant admin
through organization fine-grained admin permissions.

## Two ways to produce it

The API reads a claim. It does not care how that claim was produced, which leaves two supportable
options. Both are verified against a live `keycloak:26.7`.

| | **A. Organization groups** | **B. Protocol mapper** |
|---|---|---|
| Where the data lives | Keycloak organizations, members and group tree | A user attribute holding the JSON |
| Scope selector needed | **yes**, `organization:*` or `organization:<alias>` | no |
| Who administers a grant | a tenant admin, through organization FGAP | whoever can edit user attributes, normally a realm admin |
| Federated from a customer directory | yes, map their group onto an organization group | only if you can drive the attribute from the directory |
| Multi-organization users | selected per token | whatever the attribute says |
| Setup cost | higher, needs the bootstrap steps below | one mapper, one attribute per user |

**Pick A** if tenants should manage their own team's access, or membership comes from a customer's
own directory. That is what the feature exists for. **Pick B** if you already have a working
attribute-based claim, or grants are set centrally and delegation is not a requirement.

They are mutually exclusive for a given client: both write the same `organization` claim key, so
running them together makes them collide rather than compose.

Everything from [Group naming](#group-naming) onward applies to both. The
[Configuration](#configuration) section is option A; option B is
[at the end](#option-b-a-protocol-mapper).

## The split: identity in the token, grants in UserInfo

The access token carries only **who** and **which tenant**. The dataset grants are read separately
from the **UserInfo endpoint** and cached, rather than being embedded in the token.

|  | Access token | UserInfo |
|---|---|---|
| `organization.<alias>.id` | yes, this is what sets `TenantContext` | yes |
| `organization.<alias>.groups` | **no** | yes, this is the grant list |

Reasons for reading grants out of band rather than putting them in the JWT:

- **Token size.** Group paths are longer than role names and the `Authorization` header travels on
  every request, including the JWT `datahub-analysis` forwards through the SDK.
- **Revocation.** A group removed in Keycloak has no effect until the token expires. Reading out of
  band bounds revocation by the cache TTL instead.
- **Entra ID overage.** Where organization membership is federated from Entra, its group claim cuts
  out past roughly 150 to 200 groups and is replaced by a Graph pointer.

Administration still happens entirely in Keycloak (or in the customer's own directory, federated
into it). Nothing about access is managed in DataHub.

## Group naming

Grants are expressed against the dataset's `externalId`, not its numeric id: it is stable, it is
the same string in the Keycloak admin console as in the DataHub UI, and it does not collide across
tenants.

Name the dataset **exactly as it is stored**. External ids are kept verbatim (they are restricted
to `[A-Za-z0-9._:+=-]+`, nothing more), and the only latitude in matching is case, because the
lookup hashes through `ExternalIds.hash` exactly as uniqueness does. The path segment used to be
snake_cased on the way in, which was invisible while every id happened to be snake_case and became
a silent denial the moment they were not: `/datasets/COM-99-PT-1034/read` was rewritten to
`com_99_pt_1034`, matched no dataset, and revoked access that had been granted.

```
/datasets/<externalId>/read
/datasets/<externalId>/write
/datasets/*/read          # every dataset in the organization
/datasets/*/write
```

Datasets form a `BELONGS_TO` hierarchy, so a grant on a parent dataset covers its descendants and
the number of groups stays proportional to access domains rather than to dataset count. The `*`
segment is the all-datasets grant; it cannot collide with a real dataset because external ids are
restricted to `[A-Za-z0-9._:+=-]+`. Living in the group tree makes it organization-scoped — "all
datasets" means all datasets of that organization — and hand-out-able by a tenant admin without
realm-admin rights. The only realm role left in the dataset ACL is `DATAHUB_ADMIN`, the
deliberately cross-tenant operator escape hatch, resolved from the token alone so operator access
survives a UserInfo outage.

## Configuration (option A: organization groups)

Session setup, reused by every step. Admin tokens live 60 seconds, so refresh per call rather than
exporting one.

```bash
KC=http://localhost:8090
REALM=datahub
adm() {
  curl -s $KC/realms/master/protocol/openid-connect/token \
    -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
}
```

### 1. Enable Organizations on the realm

A realm-level flag. No `--features` flag is needed on 26.7.

```bash
curl -s -X PUT $KC/admin/realms/$REALM \
  -H "Authorization: Bearer $(adm)" -H "Content-Type: application/json" \
  -d '{"realm":"'"$REALM"'","organizationsEnabled":true}'
```

### 2. Create the organization

One organization per tenant. The `alias` is what appears as the key of the `organization` claim, so
keep it stable.

```bash
curl -s -X POST $KC/admin/realms/$REALM/organizations \
  -H "Authorization: Bearer $(adm)" -H "Content-Type: application/json" \
  -d '{"name":"Acme","alias":"acme","enabled":true,
       "domains":[{"name":"acme.test","verified":true}]}'

ORG=$(curl -s -H "Authorization: Bearer $(adm)" \
  "$KC/admin/realms/$REALM/organizations?search=acme" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["id"])')
```

### 3. Create the group tree

Organization groups have their own API. The ordinary realm-groups endpoints reject them outright
with `Cannot access organization related group via non Organization API.`

```bash
# top-level group
DS=$(curl -s -X POST $KC/admin/realms/$REALM/organizations/$ORG/groups \
  -H "Authorization: Bearer $(adm)" -H "Content-Type: application/json" \
  -d '{"name":"datasets"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

# nested groups go through /children
SAP=$(curl -s -X POST $KC/admin/realms/$REALM/organizations/$ORG/groups/$DS/children \
  -H "Authorization: Bearer $(adm)" -H "Content-Type: application/json" \
  -d '{"name":"data_set_sap"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

READ=$(curl -s -X POST $KC/admin/realms/$REALM/organizations/$ORG/groups/$SAP/children \
  -H "Authorization: Bearer $(adm)" -H "Content-Type: application/json" \
  -d '{"name":"read"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
```

### 4. Add members

A user must be an organization **member** before group membership means anything.

```bash
USER_ID=$(curl -s -H "Authorization: Bearer $(adm)" \
  "$KC/admin/realms/$REALM/users?username=dev&exact=true" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["id"])')

# organization membership: the body is the bare user id as a JSON string
curl -s -X POST $KC/admin/realms/$REALM/organizations/$ORG/members \
  -H "Authorization: Bearer $(adm)" -H "Content-Type: application/json" \
  -d "\"$USER_ID\""

# group membership: PUT, id in the path, no body
curl -s -X PUT $KC/admin/realms/$REALM/organizations/$ORG/groups/$READ/members/$USER_ID \
  -H "Authorization: Bearer $(adm)"
```

### 5. Configure the two protocol mappers

Both live on the built-in `organization` client scope, so this is done once per realm rather than
per client.

```bash
SCOPE=$(curl -s -H "Authorization: Bearer $(adm)" $KC/admin/realms/$REALM/client-scopes \
  | python3 -c 'import sys,json;print([s["id"] for s in json.load(sys.stdin) if s["name"]=="organization"][0])')
MID=$(curl -s -H "Authorization: Bearer $(adm)" \
  $KC/admin/realms/$REALM/client-scopes/$SCOPE/protocol-mappers/models \
  | python3 -c 'import sys,json;print([m["id"] for m in json.load(sys.stdin) if m["protocolMapper"]=="oidc-organization-membership-mapper"][0])')
```

**Membership mapper.** `addOrganizationId` is off by default, and without it the claim is a flat
array of aliases rather than the nested object the API needs. See [sharp edges](#sharp-edges).

```bash
curl -s -X PUT $KC/admin/realms/$REALM/client-scopes/$SCOPE/protocol-mappers/models/$MID \
  -H "Authorization: Bearer $(adm)" -H "Content-Type: application/json" \
  -d '{"id":"'"$MID"'","name":"organization","protocol":"openid-connect",
       "protocolMapper":"oidc-organization-membership-mapper",
       "config":{"claim.name":"organization","jsonType.label":"String","multivalued":"true",
                 "addOrganizationId":"true",
                 "access.token.claim":"true","id.token.claim":"true",
                 "introspection.token.claim":"true","userinfo.token.claim":"true"}}'
```

**Group membership mapper.** New mapper, UserInfo only, so grants never enter the access token.

```bash
curl -s -X POST $KC/admin/realms/$REALM/client-scopes/$SCOPE/protocol-mappers/models \
  -H "Authorization: Bearer $(adm)" -H "Content-Type: application/json" \
  -d '{"name":"organization-groups","protocol":"openid-connect",
       "protocolMapper":"oidc-organization-group-membership-mapper",
       "config":{"access.token.claim":"false","id.token.claim":"false",
                 "userinfo.token.claim":"true","introspection.token.claim":"false",
                 "addGroupRoleMappings":"false"}}'
```

`addGroupRoleMappings` is the 26.7 option that also nests realm/client roles assigned to the group
inside the `organization` claim. Left off here because grants come from group paths. Note that
realm roles attached to an organization group land in top-level `realm_access.roles` regardless,
which is how a non-dataset realm role (say `DATAHUB_CONSOLE`) can be delegated to a tenant admin
if that is ever wanted.

### 6. Request the right scope

Clients must ask for the organization with a **selector**:

```
scope=openid organization:*        # every organization the caller belongs to
scope=openid organization:acme     # pin one
```

Without a selector there is no `organization` claim at all, so the API rejects the token.
`organization:*` is the right default for a client that serves many tenants (the console); it
resolves cleanly for the normal case of a user in one organization, and a user in several gets an
ambiguous token that the API refuses rather than guessing at.

**The bare name `organization` is not usable**: it throws a server-side 500. See below.

## Verified claim shapes

Access token, with `scope=openid organization:acme`:

```json
"organization": { "acme": { "id": "f9cf24ba-6f97-46dd-8330-d205851d983d" } }
```

UserInfo (`GET /realms/datahub/protocol/openid-connect/userinfo`, caller's own bearer token), same
request:

```json
{
  "sub": "3e556d26-dbe0-4a57-ac23-a7a355122833",
  "organization": {
    "acme": {
      "id": "f9cf24ba-6f97-46dd-8330-d205851d983d",
      "groups": ["/datasets/data_set_sap/read", "/datasets/data_set_sap/write"]
    }
  },
  "preferred_username": "dev"
}
```

A user in two organizations, with `scope=openid organization:*`:

```json
"organization": {
  "acme": { "id": "f9cf24ba-...", "groups": ["/datasets/data_set_sap/read", "/datasets/data_set_sap/write"] },
  "beta": { "id": "7f2ccf5a-...", "groups": ["/datasets/data_set_beta/read"] }
}
```

Group paths are relative to the organization, and only the groups the user is directly a member of
appear (parents in the path are not listed separately).

## Sharp edges

These are the three that actually bit during verification.

1. **`addOrganizationId` is off by default and the API breaks without it.** The stock mapper emits
   `"organization": ["acme"]`, a flat array of aliases. `SecurityConfig.OrganizationValidator`
   calls `jwt.getClaimAsMap("organization")` and reads `<alias>.id`, which cannot work against an
   array. This has gone unnoticed because `deploy/keycloak/datahub-realm.json` fakes the claim with
   an `oidc-usermodel-attribute-mapper` over a `datahub_org` user attribute, so the real
   Organizations feature has never been exercised.

2. **A multi-organization user gets no claim at all unless the scope names an organization.** With
   plain `scope=openid`, a user in two organizations receives `organization: null` in the access
   token and `{}` from UserInfo, so `OrganizationValidator` rejects the token with "Missing
   required organization context". Single-organization users work by accident. The fix is for the
   client to request `organization:<alias>`, which also makes tenant selection explicit rather than
   leaving `OrganizationValidator` to take `orgs.values().iterator().next()` and pick whichever
   organization happens to serialise first.

3. **The bare scope name `organization` returns HTTP 500.** Passing `scope=openid organization`
   while the scope is assigned as a default client scope throws
   `IllegalStateException: Duplicate key organization` inside `TokenManager.isValidScope`, surfacing
   as `{"error":"unknown_error"}`. Only the selector forms (`organization:acme`, `organization:*`)
   or omitting it entirely are safe. Worth reporting upstream, since a client-supplied scope value
   should not produce a server error.

## How the API side works

- `OrgGroupResolver` calls UserInfo on a cache miss and returns the group paths for the
  caller, behind a two-tier cache: in-process for 10 seconds, Valkey for 45 seconds, both keyed on
  `(tenantId, sub)`. Past 45s a refresh is attempted; if it fails the stale answer is still served
  up to 90 seconds, after which the request fails closed. So a brief Keycloak blip is invisible and
  a sustained outage converges to denial.
- `DatasetClosureService` maps `externalId` to node id, then expands the `BELONGS_TO` closure with
  a recursive CTE over the `edge` table. Postgres, not Neo4j: the Neo4j copy is written after the
  transaction commits, from the `resource_outbox` queue, so an ACL resolved through it would lag
  behind writes.
- Both cached under a per-tenant generation counter in Valkey, bumped when a dataset is created or
  renamed or a `BELONGS_TO` edge changes, so invalidation is one `INCR` and stale entries expire on
  their own.
- `DatasetGrants` parses the group paths — per-dataset ids plus the `/datasets/*` wildcard flags —
  and `DatasetPermissionsResolver` assembles the answer; nothing parses `ROLE_DATAHUB_DATASET_*`
  roles any more. `DataSecurity`'s public surface does not change, so its call sites stay as they
  are.

One authorization change is needed independently of any of this: creating or removing a
`BELONGS_TO` edge between two datasets must require write on **both** endpoints. Otherwise a caller
with write but not read on a dataset (an ingest service account, given that read and write are
independent) can re-parent it under a dataset they can read and inherit read on their own data.

## Realm import: what it can and cannot carry

The dev realm (`deploy/keycloak/datahub-realm.json` plus
`deploy/keycloak/bootstrap-org-groups.sh`) is set up this way because import support is uneven.
All verified against 26.7:

| | Import? | Notes |
|---|---|---|
| `organizationsEnabled` | yes | |
| `organizations` with `domains` | yes | Even though **export omits them entirely** — `partial-export` returns `organizationsEnabled` but no `organizations`, so a realm export is not a faithful backup. |
| Organization `id`, pinned | yes | Needed to keep tenant ids stable. Ids are unique **per Keycloak server**, not per realm, so the same id cannot be reused in a second realm. |
| Organization `members` | yes | `[{"username": "..."}]`. |
| The group tree, via nested `subGroups` | yes | Any depth. |
| Organization **group membership** | **no** | `user.groups` with an organization group path fails with HTTP 500 (`Unable to find group specified by path`); a `members` array inside a group representation fails to parse (HTTP 400). Must be assigned over the Admin API. |
| `clientScopes` | avoid | Declaring it **replaces the entire built-in set** — `roles`, `profile`, `email` and the rest disappear and nothing is attached to clients. Configure the built-in `organization` scope's mappers over the Admin API instead. |

Attaching `organization` as a *default client scope* does not stick on an imported client, and is
not needed: clients request it by selector.

Note also that `--import-realm` only creates realms that do not already exist, so edits to
`datahub-realm.json` do nothing to a running Keycloak. Delete the realm first, or use the Admin API.

## Reproducing

The verification ran against a throwaway container, not the compose stack:

```bash
podman run -d --rm --name kc-verify -p 8099:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.7 start-dev
```

## Option B: a protocol mapper

If you are not using the Organizations feature, one `oidc-usermodel-attribute-mapper` produces the
whole claim. Verified end to end against 26.7: this yields both the tenant id and the grants, in
the access token **and** in UserInfo, with a plain `scope=openid` and no organization selector.

### 1. Put the claim JSON on the user

One attribute per user (or per service-account user), holding the nested shape the API expects:

```json
"attributes": {
  "datahub_org": [
    "{\"acme\":{\"id\":\"11111111-1111-1111-1111-111111111111\",\"groups\":[\"/datasets/data_set_sap/read\",\"/datasets/data_set_sap/write\"]}}"
  ]
}
```

The outer key is any alias you like; only `id` and `groups` are read. `id` must equal the tenant id
the platform knows (the `org-id` in the Vault tenant registry). Unmanaged attributes must be
enabled on the realm, or the attribute is dropped on import.

### 2. Emit it as the `organization` claim

On the client, or on a shared client scope if several clients need it:

```json
{
  "name": "datahub-organization",
  "protocol": "openid-connect",
  "protocolMapper": "oidc-usermodel-attribute-mapper",
  "config": {
    "user.attribute": "datahub_org",
    "claim.name": "organization",
    "jsonType.label": "JSON",
    "access.token.claim": "true",
    "userinfo.token.claim": "true",
    "introspection.token.claim": "true",
    "multivalued": "false"
  }
}
```

**`userinfo.token.claim` must be `true`.** The grants are read from UserInfo, so a mapper that
emits only into the access token leaves every caller with no group grants at all, silently. That is
the one trap in this option: the claim looks correct when you decode the token, and the API behaves
as though the groups were never there. `jsonType.label: JSON` is what stops the value being emitted
as a quoted string.

`access.token.claim` must also be true, since the tenant id is read from the access token.

**This option puts the grants in the access token, and cannot avoid it.** One claim carries both
the tenant id and the groups, and the tenant id has to be in the token, so the groups travel with
it. Nothing breaks: the API reads grants from UserInfo either way and ignores what the token says
(it logs one warning per instance when it sees them). But the [split](#the-split-identity-in-the-token-grants-in-userinfo)
is gone, and with it the three properties it buys, so option A is the better answer wherever the
grant list is large or changes often. Splitting the claim across two mappers writing the same
`organization` claim name, id-only into the access token and id-plus-groups into UserInfo, looks
like it should work and has **not** been verified here.

### 3. Verify

```bash
TOK=$(curl -s $KC/realms/$REALM/protocol/openid-connect/token \
  -d grant_type=password -d client_id=<client> -d client_secret=<secret> \
  -d username=<user> -d password=<pw> -d scope=openid \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

# both must show id, and UserInfo must additionally show groups
python3 -c "import base64,json;p='$TOK'.split('.')[1];p+='='*(-len(p)%4);print(json.loads(base64.urlsafe_b64decode(p))['organization'])"
curl -s $KC/realms/$REALM/protocol/openid-connect/userinfo -H "Authorization: Bearer $TOK"
```

### What you give up

- **Delegation.** Editing a user attribute is a realm-admin action, so a tenant admin cannot manage
  their own team's grants. Option A exists precisely to make that delegable.
- **Federation.** Nothing maps a customer directory group onto the attribute for you; you would be
  writing that sync yourself.
- **A shared namespace.** The attribute is per-user, so a team of twenty sharing a grant means
  twenty attributes to keep in step, where option A has one group with twenty members.
- **The token/UserInfo split.** The grants ride in every access token, as above: the token grows
  with the grant list, a revoked grant keeps working until the token expires rather than until the
  API's cache TTL lapses, and a federated group list large enough to be truncated by the upstream
  directory is truncated in the token too.

None of that matters if grants are set centrally and change rarely.

## Sources

- [Organization Groups](https://www.keycloak.org/2026/04/org-groups) — introduced in 26.6
- [Keycloak 26.7.0 released](https://www.keycloak.org/2026/07/keycloak-2670-released) — role assignment on organization groups, `addGroupRoleMappings`
- [Fine-Grained Admin Permissions for Organizations](https://www.keycloak.org/2026/05/org-fgap) — delegating organization management
- [Organization role management #47326](https://github.com/keycloak/keycloak/issues/47326) — organization-scoped roles, still open
