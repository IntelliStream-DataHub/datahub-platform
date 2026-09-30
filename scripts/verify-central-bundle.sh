#!/usr/bin/env bash
#
# Check a locally signed Maven Central bundle before it is uploaded.
#
#   ./scripts/verify-central-bundle.sh <bundle.zip> <version> <expected-staging-dir> <signers-file> [public-key-file]
#
# Releases of datahub-sdk and datahub-api-model are signed on the release manager's own
# machine with their personal key, and CI uploads the result. So before anything reaches
# Central, where a release can never be replaced or deleted, this proves four things:
#
#   1. The bundle holds exactly the files a release should: both modules, at <version>,
#      nothing else. Central rejects files it does not expect.
#   2. Every jar, POM and module file carries a detached signature.
#   3. Every signature is good and was made by a key in <signers-file>: a primary key
#      fingerprint per line, so a signer can rotate subkeys without a change here. The
#      keys are fetched from the public keyservers, which is also where Central looks, so a
#      key that is not published fails here instead of at Central. Only a plain good
#      signature counts: gpg exits 0 for a revoked or expired key too, so the exit code is
#      not enough.
#   4. The signed files are byte-identical to <expected-staging-dir>, which CI builds from
#      the tagged commit. A valid signature only proves a release manager signed
#      something; this proves it was the tagged source. The build is reproducible across
#      JDK vendors, so any difference is a real one.
#
# [public-key-file] imports keys from a file instead of the keyservers. The pull-request
# rehearsal uses it for its throwaway key.

set -euo pipefail

if [ $# -lt 4 ]; then
    sed -n '2,6p' "$0" >&2
    exit 2
fi
bundle=$1 version=$2 expected=$3 signers=$4 pubkeys=${5:-}

fail() { echo "::error::$*" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export GNUPGHOME=$work/gnupg
mkdir -m 700 "$GNUPGHOME"

# --- 1. Contents ---------------------------------------------------------------------
[ "$(basename "$bundle")" = "datahub-central-$version.zip" ] \
    || fail "bundle is $(basename "$bundle"), expected datahub-central-$version.zip"
unzip -q "$bundle" -d "$work/bundle"

mapfile -t files < <(cd "$work/bundle" && find . -type f | sed 's|^\./||' | sort)
[ ${#files[@]} -gt 0 ] || fail "bundle is empty"
for f in "${files[@]}"; do
    [[ $f =~ ^ai/intellistream/(datahub-api-model|datahub-sdk)/$version/[^/]+$ ]] \
        || fail "unexpected path in bundle: $f"
    [[ $f =~ \.(jar|pom|module)(\.(asc|md5|sha1|sha256|sha512))?$|\.asc\.(md5|sha1|sha256|sha512)$ ]] \
        || fail "unexpected file in bundle: $f"
done

mapfile -t artifacts < <(printf '%s\n' "${files[@]}" | grep -E '\.(jar|pom|module)$')
mapfile -t wanted < <(cd "$expected" && find . -type f \( -name '*.jar' -o -name '*.pom' -o -name '*.module' \) \
    | sed 's|^\./||' | sort)
[ ${#wanted[@]} -gt 0 ] || fail "no expected artifacts under $expected"
missing=$(comm -13 <(printf '%s\n' "${artifacts[@]}") <(printf '%s\n' "${wanted[@]}"))
extra=$(comm -23 <(printf '%s\n' "${artifacts[@]}") <(printf '%s\n' "${wanted[@]}"))
[ -z "$missing" ] || fail "bundle is missing: $missing"
[ -z "$extra" ] || fail "bundle has artifacts the tagged source does not build: $extra"

# Central checks these too; failing here names the file.
for f in "${files[@]}"; do
    case $f in
        *.md5)    algo=md5sum ;;
        *.sha1)   algo=sha1sum ;;
        *.sha256) algo=sha256sum ;;
        *.sha512) algo=sha512sum ;;
        *) continue ;;
    esac
    [ "$($algo "$work/bundle/${f%.*}" | cut -d' ' -f1)" = "$(tr -d ' \n' < "$work/bundle/$f")" ] \
        || fail "checksum mismatch: $f"
done

# --- 2 and 3. Signatures ---------------------------------------------------------------
mapfile -t allowed < <(grep -Ev '^\s*(#|$)' "$signers" | awk '{print toupper($1)}')
[ ${#allowed[@]} -gt 0 ] || fail "$signers lists no signers"

if [ -n "$pubkeys" ]; then
    gpg --batch --quiet --import "$pubkeys"
else
    for fpr in "${allowed[@]}"; do
        gpg --batch --quiet --keyserver hkps://keyserver.ubuntu.com --recv-keys "$fpr" 2>/dev/null \
            || gpg --batch --quiet --keyserver hkps://keys.openpgp.org --recv-keys "$fpr" 2>/dev/null \
            || echo "warning: signer $fpr is on neither keyserver" >&2
    done
fi

for f in "${artifacts[@]}"; do
    [ -f "$work/bundle/$f.asc" ] || fail "no signature for $f"
    status=$(gpg --batch --status-fd 1 --verify "$work/bundle/$f.asc" "$work/bundle/$f" 2>/dev/null || true)
    grep -q '^\[GNUPG:\] GOODSIG ' <<<"$status" \
        || fail "$f: not a good signature from a current key ($(grep -oE '^\[GNUPG:\] (BAD|ERR|EXP|EXPKEY|REVKEY)SIG' <<<"$status" | cut -d' ' -f2 | sort -u | tr '\n' ' '))"
    primary=$(awk '$2 == "VALIDSIG" {print $NF}' <<<"$status")
    subkey=$(awk '$2 == "VALIDSIG" {print $3}' <<<"$status")
    printf '%s\n' "${allowed[@]}" | grep -qx "$primary" \
        || fail "$f: signed by $primary, which is not in $signers"
    echo "signed  $f  (subkey $subkey, $(grep -i "^$primary" "$signers" | cut -d' ' -f2- | sed 's/^ *//'))"
done

# --- 4. Built from the tagged source ----------------------------------------------------
for f in "${artifacts[@]}"; do
    cmp -s "$work/bundle/$f" "$expected/$f" \
        || fail "$f differs from what the tagged commit builds. Rebuild the bundle from that commit with gradle.properties unchanged and no -P version overrides."
done

echo "OK: ${#artifacts[@]} artifacts, all signed by listed signers and identical to the tagged build."
