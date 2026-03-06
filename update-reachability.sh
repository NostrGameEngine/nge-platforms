#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

COPILOT_BIN="${COPILOT_BIN:-copilot}"
COPILOT_MODEL="${COPILOT_MODEL:-gpt-5-mini}"
COPILOT_EXTRA_ARGS="${COPILOT_EXTRA_ARGS:-}"
AUTO_REFRESH="${AUTO_REFRESH:-1}"

if ! command -v "$COPILOT_BIN" >/dev/null 2>&1; then
  echo "ERROR: Copilot CLI binary '$COPILOT_BIN' was not found in PATH." >&2
  echo "Set COPILOT_BIN to your command (example: COPILOT_BIN=copilot)." >&2
  exit 1
fi

read -r -a EXTRA_ARGS <<< "$COPILOT_EXTRA_ARGS"

read -r -d '' PROMPT <<PROMPT_EOF || true
You are updating GraalVM reachability coverage for this repository.

Repository root: $ROOT_DIR
Target module: nge-platform-jvm
Target harness file: nge-platform-jvm/src/test/java/org/ngengine/platform/jvm/JVMReachAllMain.java
Target metadata file: nge-platform-jvm/src/main/resources/META-INF/native-image/org.ngengine/nge-platform-jvm/reachability-metadata.json

Your task (be precise and exhaustive):
1) Inspect ALL JVM platform classes under nge-platform-jvm/src/main/java/org/ngengine/platform/jvm (full scan, not diff-based).
2) Compare current code with JVMReachAllMain coverage and extend the harness for any missing or weakly covered paths.
3) For transports/clients, do not only instantiate:
   - actively send/receive data when feasible,
   - if real communication requires extra orchestration, add/extend a dedicated local harness and invoke it from ReachAll.
4) Keep the harness resilient (best-effort, no global hard fail on optional runtime features).
5) If runtime/build evidence shows missing reflection registration (for example MissingReflectionRegistrationError, native-image analysis errors, or known provider-internal classes not emitted by the agent):
   - add/update deterministic forced reflection hints in build logic so those types are always included in reachability metadata,
   - keep this forced-hints list minimal and justified by observed evidence.
6) Run:
   ./gradlew --no-daemon :nge-platform-jvm:refreshNativeImageMetadataAll
7) Verify reachability metadata is updated under META-INF/native-image/.../reachability-metadata.json, including any forced hints added.
8) Return a short summary of touched paths and what was newly made reachable.

Important:
- You are running as gpt-5-mini. Think step-by-step, check assumptions, and avoid skipping classes.
- Do NOT rely on git diff. Infer required updates by scanning source code and existing harness coverage.
- When evidence indicates the agent misses required reflection entries, explicitly add forced reflection hints instead of relying only on harness execution.
- Prefer concrete edits over broad claims.
- Write code that is java 25 compatible, do not use var.
- Use reflective access when needed to cover otherwise unreachable paths, but prefer direct access when possible.
- Do not git commit or push, just update the local files. I will review and commit manually.
PROMPT_EOF

echo "Running Copilot CLI with model: $COPILOT_MODEL"
set -x
"$COPILOT_BIN" --model "$COPILOT_MODEL" "${EXTRA_ARGS[@]}" -i "$PROMPT"
set +x

if [[ "$AUTO_REFRESH" == "1" ]]; then
  echo "Refreshing reachability metadata after Copilot changes..."
  ./gradlew --no-daemon :nge-platform-jvm:refreshNativeImageMetadataAll
fi

if [[ -f "nge-platform-jvm/src/main/resources/META-INF/native-image/org.ngengine/nge-platform-jvm/reachability-metadata.json" ]]; then
  echo "Reachability metadata file is present."
else
  echo "ERROR: reachability metadata file was not found after update." >&2
  exit 1
fi

echo "update-reachability.sh completed."
