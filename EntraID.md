# Microsoft Entra ID service accounts

How to let a **service principal registered in Entra ID** reach `datahub-api`, by bridging it
through Keycloak with an Identity Provider. Nothing in `datahub-api` changes — it keeps trusting
exactly one Keycloak issuer.

> **Unverified.** This was written against the Keycloak 26.x documentation but has not been run
> against a live Entra tenant. Treat the field names as a starting point and confirm in the admin
> UI. The [sharp edges](#sharp-edges) section lists what is most likely to bite first.

> **Stale in two respects: the tenant claim and the dataset grant.** The steps below carry the
> tenant in a `datahub_org` user attribute plus an attribute mapper. The dev realm no longer works
> that way: it uses the real Keycloak Organizations feature, and the linked user must be a
> **member of that tenant's organization** instead. The `datahub_org` parts below should become
> organization membership, and the token request needs `scope=organization:*`. Likewise, the
> `DATAHUB_DATASET_ALL` realm role assigned below no longer exists: dataset access is organization
> groups now, and the linked user needs membership of the tenant's `/datasets/*/read` and
> `/datasets/*/write` groups instead. The rest (the JWT Authorization Grant bridge, the
> identity-provider config, the audience and assertion-reuse traps) is unaffected. Rewriting this
> properly needs a live Entra tenant to verify against, so it is deliberately left as-is rather
> than half-updated. See [datahub-api/KEYCLOAK_ORG_GROUPS.md](datahub-api/KEYCLOAK_ORG_GROUPS.md).

## Why an Entra token isn't enough on its own

Pointing an SDK straight at Entra's token endpoint produces a token `datahub-api` rejects, for
three independent reasons:

| | What the API does | What an Entra token has |
|---|---|---|
| **Issuer** | `JwtDecoders.fromIssuerLocation(issuerUri)` — one Keycloak issuer, from Vault (`SecurityConfig.java`) | `iss` = `https://login.microsoftonline.com/<tenant>/v2.0` → signature/issuer check fails |
| **Roles** | reads `realm_access.roles` | flat top-level `roles` (Entra app roles) |
| **Tenant** | `OrganizationValidator` requires `organization.<alias>.id` | no such claim, and the nested shape is awkward to emit |

So the Entra token has to become a *Keycloak* token before it reaches the API.

## The bridge: JWT Authorization Grant

Keycloak's **JWT Authorization Grant** (RFC 7523, Keycloak 26.5+) accepts an externally-signed JWT
as an `assertion` and returns a Keycloak-issued access token. Trust is configured on an **Identity
Provider**, which is the piece you were reaching for.

```
  1. client_credentials                2. jwt-bearer                    3. Bearer
     ──────────────────►                  ──────────────────►              ──────────────────►
  Entra token endpoint              Keycloak token endpoint            datahub-api
  scope=api://…/.default            grant_type=…:jwt-bearer            validates Keycloak issuer,
  → Entra JWT (aud = your API)      assertion=<Entra JWT>              realm_access.roles,
                                    → Keycloak JWT                     organization claim
```

Two older paths exist; **do not use them**. External-to-internal Token Exchange V1
(`--features=token-exchange`) is preview *and* deprecated, and
`token-exchange-external-internal:v2` was removed in Keycloak 26.7. The Keycloak docs recommend
JWT Authorization Grant in their place.

### Prerequisite

Keycloak **26.5 or newer**. `docker-compose.yml` pins `quay.io/keycloak/keycloak:26.7`, so the
local stack qualifies. No `--features` flag is needed — the capability is switched on per client
and per identity provider.

On 26.7 this is the *only* remaining option: the release removed
`token-exchange-external-internal:v2` outright, so there is no external-to-internal token exchange
to fall back to.

## 1. Entra ID

1. **App registration** → note the **Application (client) ID** and **Directory (tenant) ID**.
2. **Certificates & secrets** → new client secret.
3. **Expose an API** → set an Application ID URI (`api://<client-id>` is fine). The service
   principal needs a resource to mint a token *for*; without one there is nothing to put in `aud`.
4. **Enterprise applications** → find the app → note its **Object ID**. This is the `oid`/`sub`
   in the token and is what Keycloak links against. It is *not* the Application ID.

Scriptable equivalent, if you have the Azure CLI (Graph over `curl` needs its own admin token, so
`az` is the shorter path):

```bash
APP_ID=$(az ad app create --display-name datahub-service \
  --identifier-uris "api://datahub-service" --query appId -o tsv)
az ad sp create --id $APP_ID
APP_SECRET=$(az ad app credential reset --id $APP_ID --query password -o tsv)
TENANT_ID=$(az account show --query tenantId -o tsv)

# the Object ID Keycloak links against — not the same as $APP_ID
SP_OBJECT_ID=$(az ad sp show --id $APP_ID --query id -o tsv)
```

Verify the token before touching Keycloak:

```bash
curl -s https://login.microsoftonline.com/$TENANT_ID/oauth2/v2.0/token \
  -d grant_type=client_credentials \
  -d client_id=$APP_ID -d client_secret=$APP_SECRET \
  -d scope=api://$APP_ID/.default \
| python3 -c 'import sys,json,base64; t=json.load(sys.stdin)["access_token"]; p=t.split(".")[1]; p+="="*(-len(p)%4); c=json.loads(base64.urlsafe_b64decode(p)); print({k:c[k] for k in ("iss","aud","sub","oid","exp","iat") if k in c})'
```

Note the `aud` and `sub` — both matter below.

## 2. Keycloak identity provider

Every step below has a `curl` form against the [Admin REST
API](https://www.keycloak.org/docs-api/latest/rest-api/). Prefer it over editing
`deploy/keycloak/datahub-realm.json`: `--import-realm` only creates a realm that does *not*
already exist, so edits to that file do nothing to a running Keycloak until the realm is deleted
and re-imported. The realm JSON is for provisioning a fresh stack; the Admin API is for changing
a live one.

Set up a session first — every later call reuses `$KC`, `$REALM` and `$ADMIN`:

```bash
KC=http://localhost:8090
REALM=datahub
ADMIN=$(curl -s $KC/realms/master/protocol/openid-connect/token \
  -d grant_type=password -d client_id=admin-cli \
  -d username=admin -d password=admin | jq -r .access_token)

# sanity check — should print the realm name
curl -s -H "Authorization: Bearer $ADMIN" $KC/admin/realms/$REALM | jq -r .realm
```

(`jq` is only for readability; every call works without it.)

Then create the identity provider:

```bash
curl -s -X POST $KC/admin/realms/$REALM/identity-provider/instances \
  -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{
    "alias": "entra",
    "displayName": "Microsoft Entra ID",
    "providerId": "oidc",
    "enabled": true,
    "storeToken": false,
    "linkOnly": false,
    "config": {
      "issuer": "https://login.microsoftonline.com/'"$TENANT_ID"'/v2.0",
      "jwksUrl": "https://login.microsoftonline.com/'"$TENANT_ID"'/discovery/v2.0/keys",
      "useJwksUrl": "true",
      "validateSignature": "true",
      "clientId": "'"$APP_ID"'",
      "clientSecret": "'"$APP_SECRET"'",
      "clientAuthMethod": "client_secret_post",
      "jwtAuthorizationGrantEnabled": "true",
      "jwtAuthorizationGrantAllowReuse": "true",
      "jwtAuthorizationGrantMaxExpiration": "3600",
      "allowedClockSkew": "30",
      "syncMode": "FORCE"
    }
  }'
```

Read it back to confirm what Keycloak actually stored:

```bash
curl -s -H "Authorization: Bearer $ADMIN" \
  $KC/admin/realms/$REALM/identity-provider/instances/entra | jq .config
```

> The `jwtAuthorizationGrant*` config keys are the least certain strings in this document. If the
> read-back shows them missing or renamed, set the fields in the admin console instead and diff
> the same `GET` — whatever the console writes is authoritative for your build. The console names
> them as in the table below.

Admin console → **Identity providers** → **OpenID Connect v1.0**, then fill in
**Authorization Grant Settings**:

| Field | Value |
|---|---|
| Alias | `entra` |
| Issuer | `https://login.microsoftonline.com/<TENANT_ID>/v2.0` |
| JWT Authorization Grant | **On** |
| Use JWKS URL | On |
| JWKS URL | `https://login.microsoftonline.com/<TENANT_ID>/discovery/v2.0/keys` |
| Client ID | your Entra Application (client) ID |
| Allows Client ID as audience for assertions | **On** (Advanced settings) |
| Max allowed assertion expiration | `3600` — see [sharp edges](#sharp-edges) |
| Allow assertion reuse | **On** — see [sharp edges](#sharp-edges) |
| Allowed clock skew | `30` |

## 3. Link a Keycloak user to the service principal

JWT Authorization Grant does **not** auto-provision. The assertion's `sub` must resolve to an
already-linked Keycloak user, and that user is where the tenant and roles live. Add to
`deploy/keycloak/datahub-realm.json` alongside the existing users:

```json
{
  "username": "svc-entra-foo",
  "enabled": true,
  "federatedIdentities": [
    {
      "identityProvider": "entra",
      "userId": "<ENTRA_SERVICE_PRINCIPAL_OBJECT_ID>",
      "userName": "svc-entra-foo"
    }
  ],
  "realmRoles": ["DATAHUB_ACCESS", "DATAHUB_DATASET_ALL"],
  "attributes": {
    "datahub_org": ["{\"foo\":{\"id\":\"11111111-1111-1111-1111-111111111111\"}}"]
  }
}
```

`userId` must equal the `sub` from step 1. As with the local service-account clients, **the tenant
is bound to the identity** — one linked user per Entra service principal, carrying that tenant's
`datahub_org`.

Against a running Keycloak, create the user, link it, and grant the roles — three calls, because
role mappings need the role's internal id:

```bash
SP_OBJECT_ID=<entra-service-principal-object-id>

curl -s -X POST $KC/admin/realms/$REALM/users \
  -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{
    "username": "svc-entra-foo",
    "enabled": true,
    "attributes": {
      "datahub_org": ["{\"foo\":{\"id\":\"11111111-1111-1111-1111-111111111111\"}}"]
    }
  }'

USER_ID=$(curl -s -H "Authorization: Bearer $ADMIN" \
  "$KC/admin/realms/$REALM/users?username=svc-entra-foo&exact=true" | jq -r '.[0].id')

# link it to the Entra service principal — userId here is the assertion's `sub`
curl -s -X POST $KC/admin/realms/$REALM/users/$USER_ID/federated-identity/entra \
  -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{"identityProvider":"entra","userId":"'"$SP_OBJECT_ID"'","userName":"svc-entra-foo"}'

# grant the realm roles (the API wants full role representations, so fetch them first)
ROLES=$(for r in DATAHUB_ACCESS DATAHUB_DATASET_ALL; do
  curl -s -H "Authorization: Bearer $ADMIN" $KC/admin/realms/$REALM/roles/$r
done | jq -s .)

curl -s -X POST $KC/admin/realms/$REALM/users/$USER_ID/role-mappings/realm \
  -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" -d "$ROLES"
```

Verify the link and the roles took:

```bash
curl -s -H "Authorization: Bearer $ADMIN" \
  $KC/admin/realms/$REALM/users/$USER_ID/federated-identity | jq
curl -s -H "Authorization: Bearer $ADMIN" \
  $KC/admin/realms/$REALM/users/$USER_ID/role-mappings/realm | jq -r '.[].name'
```

## 4. Keycloak client for the exchange

The exchange is performed *by a confidential Keycloak client*, so you still need a Keycloak
client id + secret in addition to the Entra ones. Add to the realm's `clients`:

```json
{
  "clientId": "datahub-jwt-grant",
  "name": "JWT Authorization Grant broker for Entra service principals",
  "enabled": true,
  "protocol": "openid-connect",
  "publicClient": false,
  "clientAuthenticatorType": "client-secret",
  "secret": "changeme-jwt-grant",
  "standardFlowEnabled": false,
  "directAccessGrantsEnabled": false,
  "serviceAccountsEnabled": false,
  "fullScopeAllowed": true,
  "attributes": {
    "oauth2.jwt.authorization.grant.enabled": "true",
    "oauth2.jwt.authorization.grant.identity.providers": "entra"
  },
  "protocolMappers": [
    {
      "name": "datahub-organization",
      "protocol": "openid-connect",
      "protocolMapper": "oidc-usermodel-attribute-mapper",
      "consentRequired": false,
      "config": {
        "user.attribute": "datahub_org",
        "claim.name": "organization",
        "jsonType.label": "JSON",
        "access.token.claim": "true",
        "id.token.claim": "false",
        "userinfo.token.claim": "false",
        "multivalued": "false"
      }
    }
  ]
}
```

> The two `attributes` keys correspond to the **JWT Authorization Grant** capability switch and
> **Allowed Identity Providers for JWT Authorization Grant**. Set them in the admin UI and read
> the client back to confirm the exact attribute names for your build — these are as uncertain as
> the identity-provider config keys above.

The same thing against a running Keycloak, plus the protocol mapper that emits the tenant claim:

```bash
curl -s -X POST $KC/admin/realms/$REALM/clients \
  -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{
    "clientId": "datahub-jwt-grant",
    "name": "JWT Authorization Grant broker for Entra service principals",
    "enabled": true,
    "protocol": "openid-connect",
    "publicClient": false,
    "clientAuthenticatorType": "client-secret",
    "secret": "changeme-jwt-grant",
    "standardFlowEnabled": false,
    "directAccessGrantsEnabled": false,
    "serviceAccountsEnabled": false,
    "fullScopeAllowed": true,
    "attributes": {
      "oauth2.jwt.authorization.grant.enabled": "true",
      "oauth2.jwt.authorization.grant.identity.providers": "entra"
    }
  }'

CLIENT_UUID=$(curl -s -H "Authorization: Bearer $ADMIN" \
  "$KC/admin/realms/$REALM/clients?clientId=datahub-jwt-grant" | jq -r '.[0].id')

curl -s -X POST $KC/admin/realms/$REALM/clients/$CLIENT_UUID/protocol-mappers/models \
  -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{
    "name": "datahub-organization",
    "protocol": "openid-connect",
    "protocolMapper": "oidc-usermodel-attribute-mapper",
    "config": {
      "user.attribute": "datahub_org",
      "claim.name": "organization",
      "jsonType.label": "JSON",
      "access.token.claim": "true",
      "id.token.claim": "false",
      "userinfo.token.claim": "false",
      "multivalued": "false"
    }
  }'
```

Confirm the capability attributes survived — this is the read-back that settles whether those two
keys are named correctly on your build:

```bash
curl -s -H "Authorization: Bearer $ADMIN" \
  $KC/admin/realms/$REALM/clients/$CLIENT_UUID | jq '.attributes'
```

Retrieve the generated secret if you did not set one:

```bash
curl -s -H "Authorization: Bearer $ADMIN" \
  $KC/admin/realms/$REALM/clients/$CLIENT_UUID/client-secret | jq -r .value
```

## 5. Exchange and call

```bash
ENTRA_TOKEN=$(curl -s https://login.microsoftonline.com/$TENANT_ID/oauth2/v2.0/token \
  -d grant_type=client_credentials -d client_id=$APP_ID -d client_secret=$APP_SECRET \
  -d scope=api://$APP_ID/.default | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

DATAHUB_TOKEN=$(curl -s http://localhost:8090/realms/datahub/protocol/openid-connect/token \
  -u datahub-jwt-grant:changeme-jwt-grant \
  -d grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer \
  -d assertion=$ENTRA_TOKEN | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

curl -s -H "Authorization: Bearer $DATAHUB_TOKEN" http://localhost:8081/units
```

Before wiring anything up, confirm the exchanged token carries both claims the API needs:

```bash
python3 -c 'import sys,base64,json; p="'"$DATAHUB_TOKEN"'".split(".")[1]; p+="="*(-len(p)%4); c=json.loads(base64.urlsafe_b64decode(p)); print(c.get("organization"), c.get("realm_access"))'
```

## 6. SDK wiring

The SDKs run both legs. `CLIENT_ID`/`CLIENT_SECRET`/`TOKEN_URI` describe the Keycloak client that
performs the exchange; the `ASSERTION_*` keys describe the Entra app the assertion comes from:

```bash
BASE_URL=https://api.intellistream.ai
CLIENT_ID=datahub-jwt-grant
CLIENT_SECRET=changeme-jwt-grant
TOKEN_URI=http://localhost:8090/realms/datahub/protocol/openid-connect/token
ASSERTION_CLIENT_ID=<entra-application-id>
ASSERTION_CLIENT_SECRET=<entra-secret>
ASSERTION_TOKEN_URI=https://login.microsoftonline.com/<tenant-id>/oauth2/v2.0/token
ASSERTION_SCOPE=api://<entra-application-id>/.default
```

The exchanged token is cached and refreshed like any other; the assertion is re-fetched for each
exchange rather than cached. Builder equivalents are `.assertionCredentials(...)` (Java),
`set_assertion_credentials(...)` (Rust) and `assertion_credentials=` (Python) — see the
[SDK docs](https://intellistream.ai/sdk-documentation/reference/client#exchanging-an-external-token-jwt-bearer).

If you would rather not run this at all, the Keycloak service-account clients in
[GETTING_STARTED.md](GETTING_STARTED.md#machine-to-machine-tokens) stay the simplest option, with
Entra federated for human logins only.

## Sharp edges

1. **Assertion reuse.** Keycloak defaults to one-time-use assertions. The SDKs re-request the
   assertion for every exchange rather than caching it, but that is not enough on its own: Entra
   serves the *same* token (same `jti`) from its own cache until it nears expiry, so the second
   exchange still looks like a replay. Turn **Allow assertion reuse** on.
2. **Max allowed assertion expiration** defaults to **5 minutes**. Entra access tokens live
   60–90 minutes, so the default rejects every assertion. Raise it past the Entra lifetime.
3. **The audience mismatch is the likeliest failure.** RFC 7523 wants `aud` to identify the
   Keycloak server; Entra sets `aud` to your Application ID URI. "Allows Client ID as audience for
   assertions" is what reconciles the two — if exchange fails with an audience error, this is why.
4. **`sub` vs. Application ID.** The federated link needs the service principal's **Object ID**,
   not the Application (client) ID. Easy to mix up and the failure looks like "user not found".
5. **No auto-provisioning.** Every new Entra service principal needs a matching linked Keycloak
   user before it can authenticate. This is manual, and it is the main operational cost.
6. **Two secrets per service.** An Entra client secret *and* the Keycloak `datahub-jwt-grant`
   secret. Both need rotation.
7. **Entra app roles are ignored.** Authorization comes entirely from the linked Keycloak user's
   realm roles. Granting an app role in Entra changes nothing on the DataHub side.

## Alternative: teach `datahub-api` about multiple issuers

Bypassing Keycloak means replacing the single-issuer decoder with a
`JwtIssuerAuthenticationManagerResolver` plus per-issuer role and tenant mappers, and adding
audience validation (which does not exist today — any client in the realm currently mints a token
both `datahub-api` and `datahub-analysis` accept).

That removes the linked-user bookkeeping and the second secret, and lets the SDKs talk to Entra
directly with plain client credentials. It costs real code in `SecurityConfig`, duplicated in
`datahub-analysis`, and it puts tenant resolution in the hands of whatever Entra can be persuaded
to emit. Worth it if Entra becomes the primary identity source; not worth it for one or two
service principals.

## Sources

- [Configuring and using token exchange](https://www.keycloak.org/securing-apps/token-exchange) — V1 external-to-internal is preview and deprecated
- [JWT Authorization Grant](https://www.keycloak.org/securing-apps/jwt-authorization-grant) — configuration reference
- [JWT Authorization Grant and Identity Chaining in Keycloak 26.5](https://www.keycloak.org/2026/01/jwt-authorization-grant) — feature introduction
- [Remove support for token-exchange-external-internal:v2](https://github.com/keycloak/keycloak/issues/48104) — removed in 26.7
