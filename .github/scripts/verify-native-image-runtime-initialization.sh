#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
smoke_dir="${repo_root}/nge-platform-jvm/build/native/runtime-initialization-smoke"
classpath_file="${smoke_dir}/classpath.txt"
binary="${smoke_dir}/runtime-initialization-smoke"
configuration_glob="class_initialization_configuration_*.csv"
report_glob="class_initialization_report_*.csv"
metadata_resource="META-INF/native-image/org.ngengine/nge-platform-jvm/native-image.properties"

# This is an independent CI policy, intentionally separate from the Gradle metadata generator.
# Removing a protected type from native-image.properties must therefore fail this verification.
required_runtime_types=(
    'org.bouncycastle.crypto.CryptoServicesRegistrar'
    'org.bouncycastle.jcajce.provider.drbg.DRBG'
    'org.bouncycastle.jcajce.provider.drbg.DRBG$Default'
    'org.bouncycastle.jcajce.provider.drbg.DRBG$NonceAndIV'
    'org.ngengine.platform.jvm.JVMAsyncPlatform'
    'org.ngengine.platform.jvm.JVMAsyncPlatform$SecureRandomHolder'
    'org.ngengine.platform.jvm.JVMNetworkSecurity'
    'org.ngengine.platform.jvm.JVMNGEAllocator'
    'org.ngengine.platform.jvm.JVMNGEAllocatorGuard'
    'org.ngengine.platform.jvm.JVMRTCTransport'
)

if ! command -v native-image >/dev/null 2>&1; then
    echo "ERROR: native-image is not available on PATH." >&2
    exit 1
fi

rm -rf "${smoke_dir}"

"${repo_root}/gradlew" \
    --no-daemon \
    --console=plain \
    :nge-platform-jvm:prepareNativeImageRuntimeInitializationSmoke

if [[ ! -s "${classpath_file}" ]]; then
    echo "ERROR: Gradle did not produce the smoke-test classpath: ${classpath_file}" >&2
    exit 1
fi

classpath="$(tr -d '\r\n' < "${classpath_file}")"

(
    cd "${smoke_dir}"
    native-image \
        -Ob \
        --no-fallback \
        --exact-reachability-metadata \
        -H:+UnlockExperimentalVMOptions \
        -H:+PrintClassInitialization \
        -H:-UnlockExperimentalVMOptions \
        -cp "${classpath}" \
        -o "${binary}" \
        org.ngengine.platform.jvm.NativeImageRuntimeInitializationSmoke \
        2>&1 | tee native-image.log
)

configuration_report="$(find "${smoke_dir}" -type f -name "${configuration_glob}" -print | sort | tail -n 1)"
initialization_report="$(find "${smoke_dir}" -type f -name "${report_glob}" -print | sort | tail -n 1)"

if [[ -z "${configuration_report}" || -z "${initialization_report}" ]]; then
    echo "ERROR: Native Image did not produce both class-initialization CSV reports." >&2
    exit 1
fi

csv_line_for_type() {
    local report="$1"
    local type="$2"
    awk -F ',' -v expected_type="${type}" '
        $1 == expected_type {
            sub(/\r$/, "", $0)
            print
            exit
        }
    ' "${report}"
}

csv_state_for_line() {
    awk -F ',' '{
        state = $2
        gsub(/^[[:space:]\"]+|[[:space:]\"]+$/, "", state)
        print state
    }' <<< "$1"
}

for type in "${required_runtime_types[@]}"; do
    configuration_line="$(csv_line_for_type "${configuration_report}" "${type}")"
    if [[ -z "${configuration_line}" ]]; then
        echo "ERROR: ${type} is missing from the explicit class-initialization configuration report." >&2
        exit 1
    fi
    if [[ "$(csv_state_for_line "${configuration_line}")" != "RUN_TIME" ]]; then
        echo "ERROR: ${type} is not explicitly configured for RUN_TIME initialization." >&2
        echo "Report row: ${configuration_line}" >&2
        exit 1
    fi
    if [[ "${configuration_line}" != *"${metadata_resource}"* ]]; then
        echo "ERROR: ${type} does not originate from the packaged ${metadata_resource}." >&2
        echo "Report row: ${configuration_line}" >&2
        exit 1
    fi

    initialization_line="$(csv_line_for_type "${initialization_report}" "${type}")"
    if [[ -z "${initialization_line}" ]]; then
        echo "ERROR: ${type} was not reachable in the Native Image smoke analysis." >&2
        exit 1
    fi
    if [[ "$(csv_state_for_line "${initialization_line}")" != "RUN_TIME" ]]; then
        echo "ERROR: ${type} was analyzed with an unsafe initialization policy." >&2
        echo "Report row: ${initialization_line}" >&2
        exit 1
    fi
done

"${binary}"

echo "Verified ${#required_runtime_types[@]} protected classes as reachable and runtime-initialized."
