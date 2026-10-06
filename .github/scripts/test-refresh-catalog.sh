#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
gradle="${GRADLE_BIN:-$repo_root/gradlew}"
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
logs="${CATALOG_TEST_LOG_DIR:-$repo_root/build/reports/catalog-refresh-tests}"
mkdir -p "$logs" "$fixture/gradle"

# Exercise the production task without resolving the platform build plugins.
sed -n "/^tasks.register('refreshCatalog') {/,/^apply from: /p" "$repo_root/build.gradle" |
    sed '$d' > "$fixture/build.gradle"
grep -Fq "tasks.register('refreshCatalog')" "$fixture/build.gradle"
printf "rootProject.name = 'catalog-refresh-fixture'\n" > "$fixture/settings.gradle"

cat > "$fixture/gradle/libs.versions.toml" <<'EOF_CATALOG'
[versions]
okhttp = "5.4.0"
localOnly = "1.0"
updated = "1.0"
unused = "1.0"
[libraries]
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttpAndroid = { module = "com.squareup.okhttp3:okhttp-android", version.ref = "okhttp" }
localOnly = { module = "test:local", version.ref = "localOnly" }
updated = { module = "test:updated", version.ref = "updated" }
[bundles]
localBundle = ["okhttpAndroid", "localOnly"]
[plugins]
localPlugin = { id = "test.local", version.ref = "localOnly" }
EOF_CATALOG

cat > "$fixture/central.toml" <<'EOF_CATALOG'
[versions]
okhttp = "5.3.2"
updated = "2.0"
remoteOnly = "1.0"
[libraries]
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
updated = { module = "test:updated", version.ref = "updated" }
remoteOnly = { module = "test:remote", version.ref = "remoteOnly" }
EOF_CATALOG

"$gradle" "$@" --no-daemon --console=plain --project-dir "$fixture" refreshCatalog \
    "-PcentralCatalogUrl=file://$fixture/central.toml" > "$logs/refresh.log" 2>&1 || {
    cat "$logs/refresh.log"
    exit 1
}

catalog="$fixture/gradle/libs.versions.toml"
grep -Fq 'okhttpAndroid = { module = "com.squareup.okhttp3:okhttp-android", version.ref = "okhttp" }' "$catalog"
grep -Fq 'okhttp = "5.4.0"' "$catalog"
grep -Fq 'localOnly = "1.0"' "$catalog"
grep -Fq 'localOnly = { module = "test:local", version.ref = "localOnly" }' "$catalog"
grep -Fq 'updated = "2.0"' "$catalog"
grep -Fq 'localBundle = ["okhttpAndroid", "localOnly"]' "$catalog"
grep -Fq 'localPlugin = { id = "test.local", version.ref = "localOnly" }' "$catalog"
if grep -Eq '^(remoteOnly|unused) =' "$catalog"; then
    echo "Refresh included an unused version or an unrelated central alias" >&2
    exit 1
fi
echo "PASS: local aliases and versions preserved; central updates applied without downgrades"

# A second invocation parses the refreshed catalog and must be idempotent.
cp "$catalog" "$fixture/expected.toml"
"$gradle" "$@" --no-daemon --console=plain --project-dir "$fixture" refreshCatalog \
    "-PcentralCatalogUrl=file://$fixture/central.toml" > "$logs/repeat.log" 2>&1 || {
    cat "$logs/repeat.log"
    exit 1
}
cmp "$fixture/expected.toml" "$catalog"
echo "PASS: refreshed catalog is valid and idempotent"

# Reject unresolved version references before overwriting the local catalog.
sed -i.bak 's/version.ref = "updated"/version.ref = "missing"/' "$fixture/central.toml"
if "$gradle" "$@" --no-daemon --console=plain --project-dir "$fixture" refreshCatalog \
    "-PcentralCatalogUrl=file://$fixture/central.toml" > "$logs/missing-version.log" 2>&1; then
    echo "Refresh accepted an unresolved version reference" >&2
    exit 1
fi
grep -Fq "Catalog version reference 'missing' is missing" "$logs/missing-version.log"
cmp "$fixture/expected.toml" "$catalog"
echo "PASS: unresolved version references fail without changing the local catalog"
