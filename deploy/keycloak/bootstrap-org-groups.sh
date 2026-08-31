#!/usr/bin/env bash
#
# Finishes the `datahub` realm after --import-realm has loaded datahub-realm.json.
#
# Two things a realm import cannot do, both verified against keycloak:26.7:
#
#   1. Configure the protocol mappers on the built-in `organization` client scope. Declaring
#      `clientScopes` in the import REPLACES the entire built-in set (roles, profile, email, ...
#      all vanish), so the mappers have to be adjusted here instead.
#   2. Assign organization group membership. `user.groups` pointing at an organization group path
#      fails with HTTP 500 ("Unable to find group specified by path"), and a `members` array inside
#      a group representation fails to parse. Organizations, organization members and the group
#      tree itself DO import fine; only group membership has to be done over the API.
#
# Note what this deliberately does NOT do: attach `organization` as a default client scope. It does
# not stick on an imported client, and it is not needed. Clients must ask for the scope by selector
# instead — `scope=openid organization:*`, or `organization:<alias>` to pin one. A bare
# `organization` in the scope parameter throws a server-side 500 (duplicate key in isValidScope).
#
# Idempotent: safe to re-run. See datahub-api/KEYCLOAK_ORG_GROUPS.md for what the result should
# look like and why the claim is split between the access token and UserInfo.
#
# Usage:  ./bootstrap-org-groups.sh [keycloak-url] [realm]
set -euo pipefail

KC="${1:-http://localhost:8090}"
REALM="${2:-datahub}"
ADMIN_USER="${KC_ADMIN_USER:-admin}"
ADMIN_PASS="${KC_ADMIN_PASS:-admin}"

# Admin tokens live 60 seconds by default, so fetch a fresh one per call rather than exporting one.
adm() {
  curl -sf "$KC/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    -d "username=$ADMIN_USER" -d "password=$ADMIN_PASS" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
}

api() {
  local method=$1 path=$2 body=${3:-}
  if [ -n "$body" ]; then
    curl -s -X "$method" "$KC/admin/realms/$REALM$path" \
      -H "Authorization: Bearer $(adm)" -H "Content-Type: application/json" -d "$body"
  else
    curl -s -X "$method" "$KC/admin/realms/$REALM$path" -H "Authorization: Bearer $(adm)"
  fi
}

jq_py() { python3 -c "$1"; }

echo "Bootstrapping organization groups in realm '$REALM' at $KC"

# ---- 1. The organization client scope and its two mappers ------------------------------------

SCOPE_ID=$(api GET "/client-scopes" | jq_py '
import sys,json
scopes=[s for s in json.load(sys.stdin) if s["name"]=="organization"]
print(scopes[0]["id"] if scopes else "")')
if [ -z "$SCOPE_ID" ]; then
  echo "  ERROR: no 'organization' client scope. Is organizationsEnabled true on the realm?" >&2
  exit 1
fi

MEMBERSHIP_ID=$(api GET "/client-scopes/$SCOPE_ID/protocol-mappers/models" | jq_py '
import sys,json
m=[m for m in json.load(sys.stdin) if m["protocolMapper"]=="oidc-organization-membership-mapper"]
print(m[0]["id"] if m else "")')

# addOrganizationId is OFF by default, and without it the claim is a flat array of aliases with no
# id — OrganizationValidator cannot resolve a tenant from that and rejects every token.
if [ -n "$MEMBERSHIP_ID" ]; then
  api PUT "/client-scopes/$SCOPE_ID/protocol-mappers/models/$MEMBERSHIP_ID" '{
    "id":"'"$MEMBERSHIP_ID"'","name":"organization","protocol":"openid-connect",
    "protocolMapper":"oidc-organization-membership-mapper",
    "config":{"claim.name":"organization","jsonType.label":"String","multivalued":"true",
              "addOrganizationId":"true",
              "access.token.claim":"true","id.token.claim":"true",
              "introspection.token.claim":"true","userinfo.token.claim":"true"}}' >/dev/null
  echo "  membership mapper: addOrganizationId=true, emitted to token + userinfo"
fi

# UserInfo only: dataset grants stay out of the access token, so it does not grow with them and
# revocation is bounded by the API's cache TTL rather than by token expiry.
GROUPS_MAPPER_CONFIG='{"access.token.claim":"false","id.token.claim":"false",
              "userinfo.token.claim":"true","introspection.token.claim":"false",
              "addGroupRoleMappings":"false"}'

# id and name of an existing group-membership mapper, whatever it is called.
GROUPS_MAPPER=$(api GET "/client-scopes/$SCOPE_ID/protocol-mappers/models" | jq_py '
import sys,json
m=[m for m in json.load(sys.stdin) if m["protocolMapper"]=="oidc-organization-group-membership-mapper"]
print(m[0]["id"]+" "+m[0]["name"] if m else "")')
GROUPS_MAPPER_ID=${GROUPS_MAPPER%% *}
GROUPS_MAPPER_NAME=${GROUPS_MAPPER#* }

if [ -z "$GROUPS_MAPPER_ID" ]; then
  api POST "/client-scopes/$SCOPE_ID/protocol-mappers/models" '{
    "name":"organization-groups","protocol":"openid-connect",
    "protocolMapper":"oidc-organization-group-membership-mapper",
    "config":'"$GROUPS_MAPPER_CONFIG"'}' >/dev/null
  echo "  group-membership mapper: added, userinfo only"
else
  # Rewritten rather than left alone. This is the only thing enforcing "grants never ride in a
  # token", and an existing mapper is exactly where that can have drifted: one added by hand
  # defaults to access.token.claim=true, and a future Keycloak could ship one on the built-in
  # scope. Skipping it on the grounds that a mapper exists checked the wrong thing.
  api PUT "/client-scopes/$SCOPE_ID/protocol-mappers/models/$GROUPS_MAPPER_ID" '{
    "id":"'"$GROUPS_MAPPER_ID"'","name":"'"$GROUPS_MAPPER_NAME"'","protocol":"openid-connect",
    "protocolMapper":"oidc-organization-group-membership-mapper",
    "config":'"$GROUPS_MAPPER_CONFIG"'}' >/dev/null
  echo "  group-membership mapper: '$GROUPS_MAPPER_NAME' reset to userinfo only"
fi

# ---- 2. Organization group membership ----------------------------------------------------------
#
# All-datasets access is the /datasets/*/read|write wildcard groups (the DATAHUB_DATASET_ALL realm
# role is gone; DATAHUB_ADMIN is the only realm-role escape hatch left). Each dev principal gets
# both wildcard grants so the dev stack behaves as before, plus read+write on the tenant's demo
# dataset so the per-dataset group path shape is exercised end to end as well.
#
# /datahub/tenant-admin is the other thing in the tree: the organization's administrators. It is a
# group and not a realm role precisely because a realm role is realm-global — one person may
# administer one tenant and be an ordinary member of another, which a role cannot express. It is
# namespaced under /datahub so platform-owned groups never collide with whatever the customer
# creates in their own tree, and it is NOT called /admin, which would read as the cross-tenant
# DATAHUB_ADMIN operator role. The tenant manager creates this group for every real tenant and
# joins the tenant's first user to it, so the dev realm mirrors that shape. Nothing reads it yet
# (DatasetGrants ignores paths outside /datasets/).

grant() {
  local alias=$1 username=$2 group_path=$3
  local org_id user_id group_id
  org_id=$(api GET "/organizations?search=$alias" | jq_py '
import sys,json
o=[o for o in json.load(sys.stdin) if o["alias"]=="'"$alias"'"]
print(o[0]["id"] if o else "")')
  user_id=$(api GET "/users?username=$username&exact=true" | jq_py '
import sys,json
u=json.load(sys.stdin)
print(u[0]["id"] if u else "")')
  if [ -z "$org_id" ] || [ -z "$user_id" ]; then
    echo "  grant $username -> $group_path: organization or user missing, skipping"
    return
  fi
  # Walk the path segment by segment; organization groups have their own endpoints and the
  # ordinary realm-groups API refuses them outright.
  group_id=""
  local parent_query="/organizations/$org_id/groups"
  IFS='/' read -ra segments <<< "${group_path#/}"
  for segment in "${segments[@]}"; do
    group_id=$(api GET "$parent_query" | jq_py '
import sys,json
g=[g for g in json.load(sys.stdin) if g["name"]=="'"$segment"'"]
print(g[0]["id"] if g else "")')
    if [ -z "$group_id" ]; then
      echo "  grant $username -> $group_path: no group '$segment', skipping"
      return
    fi
    parent_query="/organizations/$org_id/groups/$group_id/children"
  done
  api PUT "/organizations/$org_id/groups/$group_id/members/$user_id" >/dev/null
  echo "  grant $username -> $group_path"
}

for tenant in foo bar; do
  grant "$tenant" "$tenant" "/datasets/*/read"
  grant "$tenant" "$tenant" "/datasets/*/write"
  grant "$tenant" "$tenant" "/datasets/data_set_demo/read"
  grant "$tenant" "$tenant" "/datasets/data_set_demo/write"
  # The tenant's human user is that tenant's administrator, mirroring what the tenant manager
  # does with a real tenant's first user. The service account is deliberately left out: it is a
  # machine identity for data access, not a person who administers anyone.
  grant "$tenant" "$tenant" "/datahub/tenant-admin"
  grant "$tenant" "service-account-datahub-service-$tenant" "/datasets/*/read"
  grant "$tenant" "service-account-datahub-service-$tenant" "/datasets/*/write"
  grant "$tenant" "service-account-datahub-service-$tenant" "/datasets/data_set_demo/write"
done

cat <<EOF

Done. Verify (note the organization:* selector, without it there is no organization claim):

  TOK=\$(curl -s $KC/realms/$REALM/protocol/openid-connect/token \\
    -d grant_type=password -d client_id=datahub-client -d client_secret=changeme \\
    -d username=foo -d password=foo -d 'scope=openid organization:*' \\
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

  # access token: organization.foo.id, the tenant. No groups, by design.
  # userinfo:     the same plus organization.foo.groups, the dataset grants.
  curl -s $KC/realms/$REALM/protocol/openid-connect/userinfo -H "Authorization: Bearer \$TOK"
EOF
