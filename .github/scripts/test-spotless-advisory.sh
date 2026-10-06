#!/usr/bin/env bash
set -euo pipefail

# Exercise the real Spotless plugin in an isolated Java project. No repository
# sources are reformatted, and no application tests are skipped or suppressed.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
gradle="${GRADLE_BIN:-$repo_root/gradlew}"
gradle_args=("$@")
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
logs="${SPOTLESS_TEST_LOG_DIR:-$repo_root/build/reports/spotless-advisory-tests}"
mkdir -p "$logs" "$fixture/src/main/java" "$fixture/src/test/java"
cp "$repo_root/gradle/spotless-advisory.gradle" "$fixture/advisory.gradle"
spotless_version="$(sed -n 's/.*id "com.diffplug.spotless".*version "\([^"]*\)".*/\1/p' "$repo_root/build.gradle")"
test -n "$spotless_version"
printf "rootProject.name = 'spotless-advisory-fixture'\n" > "$fixture/settings.gradle"
cat > "$fixture/build.gradle" <<EOF_BUILD
plugins {
    id 'java'
    id 'com.diffplug.spotless' version '$spotless_version'
}
EOF_BUILD
cat >> "$fixture/build.gradle" <<'EOF_BUILD'
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies { testImplementation 'junit:junit:4.13.2' }
test { useJUnit() }
def fixtureFailure = providers.gradleProperty('fixtureFailure').orElse('').get()
if (fixtureFailure == 'configuration') {
    throw new GradleException('intentional configuration failure')
}
spotless {
    java {
        trimTrailingWhitespace()
        endWithNewline()
        if (fixtureFailure == 'syntax') {
            googleJavaFormat('1.28.0')
        }
        if (fixtureFailure == 'setup') {
            googleJavaFormat('0.0.0-intentionally-missing')
        }
        if (fixtureFailure == 'formatter') {
            custom('broken-formatter') { throw new IllegalStateException('intentional formatter failure') }
        }
    }
}
apply from: 'advisory.gradle'
if (fixtureFailure == 'lint') {
    tasks.named('spotlessJava') {
        doLast {
            def lint = new File(lintsDirectory, 'src/main/java/Example.java')
            lint.parentFile.mkdirs()
            lint.text = 'lint output must never be advisory, even alongside a formatting diff'
        }
    }
}
EOF_BUILD
cat > "$fixture/src/main/java/Example.java" <<'EOF_JAVA'
public class Example {
    public static int value() { return 1; }
}
EOF_JAVA
cat > "$fixture/src/test/java/ExampleTest.java" <<'EOF_JAVA'
public class ExampleTest {
    @org.junit.Test public void valueIsOne() {
        org.junit.Assert.assertEquals(1, Example.value());
    }
}
EOF_JAVA

run_case() {
    local name="$1" expected="$2" pattern="$3"
    shift 3
    local status=0
    "$gradle" "${gradle_args[@]}" --no-daemon --console=plain --project-dir "$fixture" "$@" > "$logs/$name.log" 2>&1 || status=$?
    if [[ "$expected" == pass && "$status" != 0 ]] || [[ "$expected" == fail && "$status" == 0 ]]; then
        cat "$logs/$name.log"
        echo "Unexpected result for $name: exit $status, expected $expected" >&2
        exit 1
    fi
    if ! grep -Fq "$pattern" "$logs/$name.log"; then
        cat "$logs/$name.log"
        echo "Missing expected diagnostic for $name: $pattern" >&2
        exit 1
    fi
    echo "PASS: $name ($expected)"
}

run_case clean pass 'BUILD SUCCESSFUL' build --build-cache
# Valid Java, deliberately misformatted. Build and the report pass; strict check fails.
printf '\n// formatting-only difference   \n' >> "$fixture/src/main/java/Example.java"
run_case dirty-build pass 'Spotless formatting differences' build --build-cache
run_case dirty-report pass 'Spotless formatting differences' spotlessReport
grep -Fq '// formatting-only difference   ' "$fixture/src/main/java/Example.java"
grep -Fq '> Task :test' "$logs/dirty-build.log"
run_case strict-check fail 'format violations' spotlessCheck
run_case incremental-report pass 'Spotless formatting differences' spotlessReport --build-cache
grep -Fq '> Task :spotlessJava UP-TO-DATE' "$logs/incremental-report.log"
run_case cached-report pass 'Spotless formatting differences' clean spotlessReport --build-cache
grep -Fq '> Task :spotlessJava FROM-CACHE' "$logs/cached-report.log"
run_case configuration-cache-store pass 'Spotless formatting differences' spotlessReport --configuration-cache --build-cache
run_case configuration-cache-reuse pass 'Reusing configuration cache' spotlessReport --configuration-cache --build-cache
grep -Fq 'Spotless formatting differences' "$logs/configuration-cache-reuse.log"
run_case apply-then-report pass 'BUILD SUCCESSFUL' spotlessApply spotlessReport --build-cache
if grep -Fq 'Spotless formatting differences' "$logs/apply-then-report.log"; then
    echo 'Already applied formatting must not produce an advisory warning' >&2
    exit 1
fi
# Make the source dirty again so real errors are also tested alongside a diff.
printf '\n// another formatting-only difference   \n' >> "$fixture/src/main/java/Example.java"
run_case formatter-error fail 'Spotless formatter/lint errors' build -PfixtureFailure=formatter
run_case formatter-setup-error fail '0.0.0-intentionally-missing' spotlessReport -PfixtureFailure=setup --offline
run_case lint-with-diff fail 'Spotless formatter/lint errors' build -PfixtureFailure=lint
run_case configuration-error fail 'intentional configuration failure' build -PfixtureFailure=configuration
printf '\nthis is not Java;\n' >> "$fixture/src/main/java/Example.java"
run_case formatter-syntax-error fail 'Spotless formatter/lint errors' spotlessReport -PfixtureFailure=syntax
run_case compile-error fail "Execution failed for task ':compileJava'" build
sed -i.bak '$d' "$fixture/src/main/java/Example.java"
sed -i.bak 's/assertEquals(1, /assertEquals(2, /' "$fixture/src/test/java/ExampleTest.java"
run_case test-error fail 'There were failing tests' build
# A plugin-resolution failure must also remain fatal in the formatting CI entry point.
sed -i.bak "s/version '$spotless_version'/version '0.0.0-intentionally-missing'/" "$fixture/build.gradle"
run_case plugin-error fail 'was not found' spotlessReport --offline

echo "All Spotless advisory policy regression checks passed. Logs: $logs"
