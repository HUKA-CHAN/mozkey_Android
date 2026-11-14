#!/usr/bin/env bash
# Prepares everything the Gradle build cannot produce by itself:
#
#   mozc   libmozc.so (Bazel, Android NDK) and the dictionary data file
#   proto  Java (lite) classes generated from src/protocol/*.proto
#   llama  llama.cpp at the commit pinned in llama_runtime.lock.json, patched
#   model  the Zenz GGUF model already tracked in this repository
#
# Usage: android/scripts/prepare_native.sh [mozc] [proto] [llama] [model]
#        (no arguments = all steps)
#
# Nothing is downloaded except Mozc's own build dependencies, protoc (PyPI) and
# the llama.cpp sources (GitHub). The model comes from the repository itself and
# every pinned input is checked against its SHA-256 in the lock file.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC_DIR="$REPO_ROOT/src"
ANDROID_DIR="$REPO_ROOT/android"
APP_MAIN="$ANDROID_DIR/app/src/main"
THIRD_PARTY="$ANDROID_DIR/third_party"
LOCK_FILE="$SRC_DIR/zenz_runtime/llama_runtime.lock.json"

# Keep in sync with android/app/build.gradle.kts.
ABIS=(arm64-v8a x86_64)
# Keep in sync with src/MODULE.bazel and the protobuf-javalite dependency.
PROTOBUF_VERSION="34.1"

log() { printf '\n==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# Prints the value at a dotted path of the lock file, e.g. "model.sha256".
lock_value() {
  python3 - "$LOCK_FILE" "$1" <<'PY'
import json, sys
value = json.load(open(sys.argv[1], encoding="utf-8"))
for part in sys.argv[2].split("."):
    value = value[part]
print(value)
PY
}

sha256_upper() {
  python3 - "$1" <<'PY'
import hashlib, sys
digest = hashlib.sha256()
with open(sys.argv[1], "rb") as stream:
    for chunk in iter(lambda: stream.read(1 << 20), b""):
        digest.update(chunk)
print(digest.hexdigest().upper())
PY
}

verify_sha256() {
  local file="$1" expected actual
  expected="$(printf '%s' "$2" | tr '[:lower:]' '[:upper:]')"
  actual="$(sha256_upper "$file")"
  [[ "$actual" == "$expected" ]] || die "SHA-256 mismatch for $file
  expected $expected
  actual   $actual"
}

step_mozc() {
  log "Building libmozc.so and mozc.data with Bazel"
  command -v bazelisk >/dev/null || die "bazelisk is required (https://github.com/bazelbuild/bazelisk)"
  (
    cd "$SRC_DIR"
    # Downloads Mozc's third-party sources and the Android NDK used by Bazel.
    python3 build_tools/update_deps.py
    bazelisk build --config oss_android --config release_build \
      package //data_manager/oss:mozc.data
  )

  local archive="$SRC_DIR/bazel-bin/android/jni/native_libs.zip"
  local data="$SRC_DIR/bazel-bin/data_manager/oss/mozc.data"
  [[ -f "$archive" ]] || die "$archive was not produced"
  [[ -f "$data" ]] || die "$data was not produced"

  local staging
  staging="$(mktemp -d)"
  python3 -m zipfile -e "$archive" "$staging"
  local abi library
  for abi in "${ABIS[@]}"; do
    library="$staging/libs/$abi/libmozc.so"
    [[ -f "$library" ]] || die "native_libs.zip has no libmozc.so for $abi"
    # The Zenz runtime registers itself through this symbol; without it Zenz
    # can never become available.
    if ! grep -a -q "mozkey_zenz_register_backend" "$library"; then
      die "$library does not contain mozkey_zenz_register_backend"
    fi
    mkdir -p "$APP_MAIN/jniLibs/$abi"
    cp -f "$library" "$APP_MAIN/jniLibs/$abi/libmozc.so"
  done
  rm -rf "$staging"

  mkdir -p "$APP_MAIN/assets/mozc"
  cp -f "$data" "$APP_MAIN/assets/mozc/mozc.data"
}

step_proto() {
  log "Generating Java protobuf classes (protoc $PROTOBUF_VERSION)"
  local protoc=""
  if command -v protoc >/dev/null && [[ "$(protoc --version)" == "libprotoc $PROTOBUF_VERSION" ]]; then
    protoc="$(command -v protoc)"
  else
    # protoc must match the runtime version exactly; fetch that exact build
    # into a private virtualenv instead of touching the system Python.
    local venv="$THIRD_PARTY/protoc-venv"
    if [[ ! -x "$venv/bin/protoc" ]]; then
      python3 -m venv "$venv"
      "$venv/bin/python" -m pip install --quiet "protobuf-protoc-bin==$PROTOBUF_VERSION"
    fi
    protoc="$venv/bin/protoc"
  fi
  [[ "$("$protoc" --version)" == "libprotoc $PROTOBUF_VERSION" ]] ||
    die "protoc $PROTOBUF_VERSION is required, found: $("$protoc" --version)"

  local out="$APP_MAIN/java"
  rm -rf "$out/org/mozc"
  mkdir -p "$out"
  (
    cd "$SRC_DIR"
    "$protoc" -I. --java_out="lite:$out" \
      protocol/commands.proto \
      protocol/config.proto \
      protocol/candidate_window.proto \
      protocol/engine_builder.proto \
      protocol/user_dictionary_storage.proto
  )
}

step_llama() {
  local repository commit patch_file patch_sha
  repository="$(lock_value macos.upstream.repository)"
  commit="$(lock_value macos.upstream.commit)"
  patch_file="$REPO_ROOT/$(lock_value patches.tokenizer.file)"
  patch_sha="$(lock_value patches.tokenizer.sha256)"
  log "Fetching llama.cpp $commit"
  verify_sha256 "$patch_file" "$patch_sha"

  local dir="$THIRD_PARTY/llama.cpp"
  local marker="$dir/.mozkey-prepared"
  if [[ -f "$marker" && "$(cat "$marker")" == "$commit:$patch_sha" ]]; then
    echo "llama.cpp is already prepared"
    return
  fi
  rm -rf "$dir"
  mkdir -p "$dir"
  git -C "$dir" init --quiet
  git -C "$dir" remote add origin "$repository"
  git -C "$dir" fetch --quiet --depth 1 origin "$commit"
  git -C "$dir" checkout --quiet FETCH_HEAD
  [[ "$(git -C "$dir" rev-parse HEAD)" == "$commit" ]] || die "llama.cpp checkout is not $commit"
  # Zenz uses the "gpt2-small-japanese-char" pre-tokenizer, which stock
  # llama.cpp does not accept. Same patch as the Windows/macOS runtimes.
  git -C "$dir" apply "$patch_file"
  printf '%s' "$commit:$patch_sha" > "$marker"
}

step_model() {
  local source file sha
  source="$REPO_ROOT/$(lock_value model.windows_file)"
  file="$(lock_value model.file)"
  sha="$(lock_value model.sha256)"
  log "Bundling the Zenz model $file"
  [[ -f "$source" ]] || die "$source is missing"
  verify_sha256 "$source" "$sha"

  # ZenzManager.MODEL_ASSET must name the same file.
  grep -q "\"zenz/$file\"" \
    "$APP_MAIN/java/org/mozkey/inputmethod/zenz/ZenzManager.kt" ||
    die "ZenzManager.MODEL_ASSET does not match the locked model file $file"

  mkdir -p "$APP_MAIN/assets/zenz" "$APP_MAIN/assets/licenses"
  cp -f "$source" "$APP_MAIN/assets/zenz/$file"
  cp -f "$SRC_DIR"/mac/installer/zenz_runtime/licenses/*.txt "$APP_MAIN/assets/licenses/"
  cp -f "$REPO_ROOT/LICENSE" "$APP_MAIN/assets/licenses/Mozc-LICENSE.txt"
}

steps=("$@")
if [[ ${#steps[@]} -eq 0 ]]; then
  steps=(mozc proto llama model)
fi
for step in "${steps[@]}"; do
  case "$step" in
    mozc) step_mozc ;;
    proto) step_proto ;;
    llama) step_llama ;;
    model) step_model ;;
    *) die "unknown step: $step (expected mozc, proto, llama or model)" ;;
  esac
done

log "Done. Build the APK with: gradle -p android :app:assembleRelease"
