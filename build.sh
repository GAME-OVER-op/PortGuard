#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$ROOT_DIR/application"
APP_MODULE_DIR="$APP_DIR/app"
RUST_PROJECT_DIR="$ROOT_DIR/rust/portguard"
MODULE_TEMPLATE_DIR="$ROOT_DIR/module_template"
MODULE_PROP_FILE="$ROOT_DIR/module.prop"
OUT_DIR="$ROOT_DIR/out"
MODULE_BUILD_DIR="$OUT_DIR/module_build"
MODULE_ROOT_DIR="$MODULE_BUILD_DIR/module_root"
MODULE_DIR="$OUT_DIR/module"
MODULE_ZIP="$MODULE_DIR/PortGuard_module.zip"
APK_OUT_DIR="$OUT_DIR/apk"
DIST_DIR="$OUT_DIR/dist"
ASSETS_DIR="$APP_MODULE_DIR/src/main/assets"
GENERATED_ASSET_ZIP="$ASSETS_DIR/module.zip"
GENERATED_ASSET_PROP="$ASSETS_DIR/module.prop"
APP_LOCAL_PROPERTIES="$APP_DIR/local.properties"
KEYSTORE_DIR="$ROOT_DIR/keystores"
DEBUG_KEYSTORE_PATH="${DEBUG_KEYSTORE_PATH:-$KEYSTORE_DIR/portguard-debug.keystore}"
DEBUG_KEYSTORE_PASSWORD="${DEBUG_KEYSTORE_PASSWORD:-android}"
DEBUG_KEY_ALIAS="${DEBUG_KEY_ALIAS:-androiddebugkey}"
DEBUG_KEY_PASSWORD="${DEBUG_KEY_PASSWORD:-android}"
CARGO_TARGET_DIR="$ROOT_DIR/rust/target"
MODE="${1:-apk}"
BUILD_TYPE="${BUILD_TYPE:-Debug}"
GRADLE_TASK="assemble${BUILD_TYPE}"
TERMUX_PREFIX_DEFAULT="/data/data/com.termux/files/usr"
TERMUX_PREFIX="${PREFIX:-$TERMUX_PREFIX_DEFAULT}"
CARGO_BIN=""
RUSTC_BIN=""

msg() { printf '[PortGuard] %s\n' "$*"; }
warn() { printf '[PortGuard][WARN] %s\n' "$*" >&2; }
fail() { printf '[PortGuard][ERR] %s\n' "$*" >&2; exit 1; }

read_prop() {
  local key="$1"
  awk -F= -v k="$key" '$1 == k { sub(/^[^=]*=/, ""); print; exit }' "$MODULE_PROP_FILE"
}

find_cmd() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return 0
  fi
  if [[ -x "$TERMUX_PREFIX/bin/$name" ]]; then
    printf '%s' "$TERMUX_PREFIX/bin/$name"
    return 0
  fi
  return 1
}

resolve_rust_tools() {
  CARGO_BIN="$(find_cmd cargo || true)"
  RUSTC_BIN="$(find_cmd rustc || true)"
  [[ -n "$CARGO_BIN" ]] || fail "cargo not found"
  [[ -n "$RUSTC_BIN" ]] || fail "rustc not found"
}

cargo_bin_path() {
  local profile="$1"
  local host
  host="$($RUSTC_BIN -vV 2>/dev/null | awk '/^host: /{print $2}')"
  if [[ -n "${CARGO_BUILD_TARGET:-}" && "$CARGO_BUILD_TARGET" != "$host" ]]; then
    printf '%s' "$CARGO_TARGET_DIR/$CARGO_BUILD_TARGET/$profile/portguard"
  else
    printf '%s' "$CARGO_TARGET_DIR/$profile/portguard"
  fi
}

doctor() {
  msg "Root: $ROOT_DIR"
  [[ -f "$MODULE_PROP_FILE" ]] || fail "Missing module.prop"
  [[ -d "$MODULE_TEMPLATE_DIR" ]] || fail "Missing module_template/"
  [[ -f "$RUST_PROJECT_DIR/Cargo.toml" ]] || fail "Missing rust/portguard/Cargo.toml"
  resolve_rust_tools
  command -v zip >/dev/null 2>&1 || fail "zip not found"
  msg "Cargo: $CARGO_BIN"
  msg "Rustc: $RUSTC_BIN"
  msg "zip: $(command -v zip)"
  if command -v keytool >/dev/null 2>&1; then
    msg "keytool: $(command -v keytool)"
  else
    warn "keytool not found. APK signing keystore cannot be created automatically."
  fi
  if [[ -x "$APP_DIR/gradlew" ]]; then
    msg "Gradle wrapper: $APP_DIR/gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    msg "Gradle: $(command -v gradle)"
  else
    warn "Gradle not found. APK build will require gradlew or system gradle."
  fi
  local sdk
  sdk="$(resolve_android_sdk_dir || true)"
  if [[ -n "$sdk" ]]; then
    msg "Android SDK: $sdk"
  else
    warn "Android SDK not detected yet. APK build will require ANDROID_HOME / ANDROID_SDK_ROOT or application/local.properties."
  fi
  msg "Module id: $(read_prop id)"
  msg "Module version: $(read_prop version)"
}

build_rust() {
  msg "Building Rust daemon" >&2
  mkdir -p "$CARGO_TARGET_DIR"
  resolve_rust_tools
  if [[ -n "${CARGO_BUILD_TARGET:-}" ]]; then
    CARGO_TARGET_DIR="$CARGO_TARGET_DIR" "$CARGO_BIN" build --manifest-path "$RUST_PROJECT_DIR/Cargo.toml" --release --target "$CARGO_BUILD_TARGET"
  else
    CARGO_TARGET_DIR="$CARGO_TARGET_DIR" "$CARGO_BIN" build --manifest-path "$RUST_PROJECT_DIR/Cargo.toml" --release
  fi
  local bin
  bin="$(cargo_bin_path release)"
  [[ -f "$bin" ]] || fail "Rust binary not found after build: $bin"
  printf '%s' "$bin"
}

prepare_module_root() {
  rm -rf "$MODULE_BUILD_DIR"
  mkdir -p "$MODULE_ROOT_DIR" "$MODULE_DIR" "$DIST_DIR"
  cp -R "$MODULE_TEMPLATE_DIR/." "$MODULE_ROOT_DIR/"
  cp "$MODULE_PROP_FILE" "$MODULE_ROOT_DIR/module.prop"
}

package_module() {
  local built_bin="$1"
  prepare_module_root
  mkdir -p "$MODULE_ROOT_DIR/bin"
  cp "$built_bin" "$MODULE_ROOT_DIR/bin/portguard"
  chmod 755 "$MODULE_ROOT_DIR/bin/portguard" "$MODULE_ROOT_DIR/service.sh" "$MODULE_ROOT_DIR/customize.sh" || true
  rm -f "$MODULE_ROOT_DIR/bin/.gitkeep"
  (cd "$MODULE_ROOT_DIR" && zip -qr "$MODULE_ZIP" .)
  cp "$MODULE_ZIP" "$DIST_DIR/$(basename "$MODULE_ZIP")"
  msg "Module packaged: $MODULE_ZIP"
}

prepare_app_assets() {
  mkdir -p "$ASSETS_DIR"
  cp "$MODULE_ZIP" "$GENERATED_ASSET_ZIP"
  cp "$MODULE_PROP_FILE" "$GENERATED_ASSET_PROP"
  msg "Embedded assets refreshed"
}

read_local_sdk_dir() {
  [[ -f "$APP_LOCAL_PROPERTIES" ]] || return 1
  awk -F= '''$1 == "sdk.dir" { sub(/^[^=]*=/, ""); print; exit }''' "$APP_LOCAL_PROPERTIES"
}

resolve_android_sdk_dir() {
  local sdk=""
  if sdk="$(read_local_sdk_dir 2>/dev/null || true)"; then
    if [[ -n "$sdk" && -d "$sdk" ]]; then
      printf '%s' "$sdk"
      return 0
    fi
  fi
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk"; do
    if [[ -n "$sdk" && -d "$sdk" ]]; then
      printf '%s' "$sdk"
      return 0
    fi
  done
  return 1
}

ensure_android_local_properties() {
  local sdk
  sdk="$(resolve_android_sdk_dir || true)"
  [[ -n "$sdk" ]] || fail "Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT, or create $APP_LOCAL_PROPERTIES with sdk.dir=/path/to/sdk"
  printf 'sdk.dir=%s
' "$sdk" > "$APP_LOCAL_PROPERTIES"
  export ANDROID_HOME="$sdk"
  export ANDROID_SDK_ROOT="$sdk"
  msg "Android SDK: $sdk"
}

ensure_debug_keystore() {
  if [[ -f "$DEBUG_KEYSTORE_PATH" ]]; then
    return 0
  fi
  command -v keytool >/dev/null 2>&1 || fail "keytool not found. Install a JDK so PortGuard can create $DEBUG_KEYSTORE_PATH"
  mkdir -p "$KEYSTORE_DIR"
  msg "Creating debug keystore: $DEBUG_KEYSTORE_PATH"
  keytool -genkeypair     -keystore "$DEBUG_KEYSTORE_PATH"     -storepass "$DEBUG_KEYSTORE_PASSWORD"     -keypass "$DEBUG_KEY_PASSWORD"     -alias "$DEBUG_KEY_ALIAS"     -keyalg RSA     -keysize 2048     -validity 10000     -dname "CN=Android Debug,O=Android,C=US"     >/dev/null 2>&1
  [[ -f "$DEBUG_KEYSTORE_PATH" ]] || fail "Failed to create keystore: $DEBUG_KEYSTORE_PATH"
}

write_signing_properties() {
  mkdir -p "$APP_DIR"
  cat > "$APP_DIR/keystore.properties" <<PROP
storeFile=$DEBUG_KEYSTORE_PATH
storePassword=$DEBUG_KEYSTORE_PASSWORD
keyAlias=$DEBUG_KEY_ALIAS
keyPassword=$DEBUG_KEY_PASSWORD
PROP
  msg "Signing config refreshed: $APP_DIR/keystore.properties -> $DEBUG_KEYSTORE_PATH"
}

find_gradle_cmd() {
  if [[ -x "$APP_DIR/gradlew" ]]; then
    printf '%s' "$APP_DIR/gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    printf '%s' 'gradle'
  else
    return 1
  fi
}

build_apk() {
  local gradle_cmd
  gradle_cmd="$(find_gradle_cmd)" || fail "Gradle wrapper/system gradle not found"
  ensure_android_local_properties
  ensure_debug_keystore
  write_signing_properties
  msg "Building APK via $GRADLE_TASK"
  (cd "$APP_DIR" && "$gradle_cmd" "$GRADLE_TASK")

  local apk_source
  apk_source="$(find "$APP_MODULE_DIR/build/outputs/apk" -type f -name '*.apk' | sort | tail -n1 || true)"
  [[ -n "$apk_source" ]] || fail "APK not found after Gradle build"
  mkdir -p "$APK_OUT_DIR" "$DIST_DIR"
  cp "$apk_source" "$APK_OUT_DIR/$(basename "$apk_source")"
  cp "$apk_source" "$DIST_DIR/$(basename "$apk_source")"
  msg "APK copied: $APK_OUT_DIR/$(basename "$apk_source")"
}

clean() {
  rm -rf "$OUT_DIR"
  rm -f "$GENERATED_ASSET_ZIP" "$GENERATED_ASSET_PROP"
  msg "Cleaned out/ and generated assets"
}

clear_all() {
  clean
  rm -rf "$CARGO_TARGET_DIR" "$APP_DIR/build" "$APP_MODULE_DIR/build"
  rm -f "$APP_DIR/keystore.properties"
  mkdir -p "$KEYSTORE_DIR" && : > "$KEYSTORE_DIR/.gitkeep"
  msg "Cleared Rust target and Android build outputs (persistent keystore in ./keystores preserved)"
}

main_module() {
  local built_bin
  built_bin="$(build_rust)"
  package_module "$built_bin"
  prepare_app_assets
}

case "$MODE" in
  doctor)
    doctor
    ;;
  keystore)
    doctor
    ensure_debug_keystore
    write_signing_properties
    printf '[PortGuard] Persistent keystore ready: %s\n' "$DEBUG_KEYSTORE_PATH"
    ;;
  module)
    doctor
    main_module
    ;;
  apk)
    doctor
    main_module
    build_apk
    ;;
  clean)
    clean
    ;;
  clear)
    clear_all
    ;;
  *)
    fail "Unknown mode: $MODE"
    ;;
esac
