#!/usr/bin/env bash
# Build, install and exercise Edge AI Core on the dedicated test emulator (or a phone).
# Usage: scripts/device.sh <command> [args]   (run from the repo root; see `help`)
#
# Environment (all optional):
#   ANDROID_HOME  SDK root                     (default ~/Android/Sdk)
#   AVD           emulator to boot             (default claude_edgeaicore_api34; never the user's own AVDs)
#   PORT          emulator console port        (default 5584 -> serial emulator-5584)
#   SERIAL        adb target                   (default emulator-$PORT; set to a phone's serial to use a phone)
#   MODEL         .litertlm on the host        (default ~/.cache/edgeaicore-test-models/gemma-4-E2B-it.litertlm)
#   HOST_PORT     host port forwarded to 8080  (default 18080)
#   STATE_DIR     token and emulator log       (default ~/.cache/edgeaicore-device)
set -euo pipefail

SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
AVD=${AVD:-claude_edgeaicore_api34}
PORT=${PORT:-5584}
SERIAL=${SERIAL:-emulator-$PORT}
MODEL=${MODEL:-$HOME/.cache/edgeaicore-test-models/gemma-4-E2B-it.litertlm}
HOST_PORT=${HOST_PORT:-18080}
STATE_DIR=${STATE_DIR:-$HOME/.cache/edgeaicore-device}
PKG=com.aanand.edgeaicore
APK=app/build/outputs/apk/debug/app-debug.apk
LOG_TAGS="InferenceService:* AiEngineManager:* OpenAIServer:* TokenManager:* ConversationManager:* MainActivity:* AndroidRuntime:E"

adb() { "$SDK/platform-tools/adb" -s "$SERIAL" "$@"; }
die() { echo "device.sh: $*" >&2; exit 1; }
mkdir -p "$STATE_DIR"; chmod 700 "$STATE_DIR"

# Tap the centre of the first UI node whose resource-id ends with ":id/<id>".
tap_id() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  local b; b=$(adb shell cat /sdcard/ui.xml | grep -o "resource-id=\"$PKG:id/$1\"[^>]*bounds=\"[^\"]*\"" | head -n 1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' || true)
  [ -n "$b" ] || die "UI element '$1' not found on screen"
  set -- $b; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

cmd_boot() {
  [ "${AVD}" != "" ] || die "AVD not set"
  "$SDK/emulator/emulator" -list-avds | grep -qx "$AVD" || die "AVD '$AVD' does not exist (see docs)"
  if "$SDK/platform-tools/adb" devices | grep -q "^$SERIAL"; then echo "already running: $SERIAL"; return; fi
  # -read-only: nothing is written back to the AVD, every boot starts from the same clean state.
  nohup "$SDK/emulator/emulator" -avd "$AVD" -port "$PORT" -read-only -no-snapshot -no-window -no-audio -no-boot-anim \
    >"$STATE_DIR/emulator.log" 2>&1 &
  local s=$SECONDS
  until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
    [ $((SECONDS - s)) -lt 240 ] || die "emulator did not boot in 240 s (see $STATE_DIR/emulator.log)"
    sleep 3
  done
  adb shell settings put global window_animation_scale 0
  adb shell settings put global transition_animation_scale 0
  adb shell settings put global animator_duration_scale 0
  echo "booted $AVD as $SERIAL in $((SECONDS - s)) s"
}

cmd_build()   { ./gradlew assembleDebug --console=plain -q; echo "built $APK"; }

cmd_install() {
  local apk=${1:-$APK}; [ -f "$apk" ] || die "$apk not found (run: scripts/device.sh build)"
  adb install -r "$apk" >/dev/null
  for p in POST_NOTIFICATIONS RECORD_AUDIO READ_MEDIA_IMAGES READ_MEDIA_AUDIO; do
    adb shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true   # not every permission exists on every API level
  done
  # Skip MainActivity's "run in background" prompt, which would cover the UI.
  adb shell dumpsys deviceidle whitelist "+$PKG" >/dev/null
  echo "installed $apk"
}

cmd_model() {
  local src=${1:-$MODEL} name; [ -f "$src" ] || die "model $src not found"
  name=$(basename "$src")
  adb push "$src" "/data/local/tmp/$name" >/dev/null
  # run-as needs a debug build; a fresh install has no files/ or shared_prefs/ yet.
  adb shell run-as "$PKG" sh -c "'mkdir -p files shared_prefs && cat /data/local/tmp/$name > files/$name'"
  adb shell rm "/data/local/tmp/$name"
  # Seed the path MainActivity restores, so the UI flow can use the model without the file picker.
  printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" "<map>" \
    "    <string name=\"model_path\">/data/user/0/$PKG/files/$name</string>" "</map>" |
    adb shell run-as "$PKG" sh -c "'cat > shared_prefs/litertlm_prefs.xml'"
  echo "model at /data/user/0/$PKG/files/$name"
}

cmd_start() {
  local backend=${1:-CPU} name; name=$(basename "$MODEL")
  adb logcat -c
  adb shell am start-foreground-service -n "$PKG/.InferenceService" \
    --es MODEL_PATH "/data/user/0/$PKG/files/$name" --es BACKEND "$backend" >/dev/null
  local s=$SECONDS line
  while :; do
    # Ready when the service logs "Model loaded successfully (<backend in use>)"; "Ping failed" lines are retries, not errors.
    line=$(adb logcat -d -s InferenceService:* | grep -E "Model loaded successfully|Model verification failed|Failed to initialize|crash loop" | tail -n 1 || true)
    case "$line" in
      *"Model loaded successfully"*) echo "ready in $((SECONDS - s)) s (requested $backend): ${line#*: }"; return;;
      ?*) die "start failed: ${line#*: }";;
    esac
    [ $((SECONDS - s)) -lt 300 ] || die "model not ready after 300 s"
    sleep 3
  done
}

cmd_stop_service() { adb shell am start-foreground-service -n "$PKG/.InferenceService" -a STOP >/dev/null; echo "service stopped"; }

cmd_token() {
  adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 2
  tap_id nav_tokens; sleep 1; tap_id btn_generate_token; sleep 1
  adb shell run-as "$PKG" cat shared_prefs/edge_ai_core_tokens_v2.xml |
    grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' | head -n 1 > "$STATE_DIR/token"
  chmod 600 "$STATE_DIR/token"; [ -s "$STATE_DIR/token" ] || die "no token found after Generate"
  echo "token saved to $STATE_DIR/token (not printed)"
}

cmd_forward() { adb forward "tcp:$HOST_PORT" tcp:8080 >/dev/null; echo "http://127.0.0.1:$HOST_PORT -> device :8080"; }

# api <METHOD> <path> [json-body]  -> prints the response body and the HTTP status on the last line
cmd_api() {
  local method=$1 path=$2 body=${3:-}; [ -s "$STATE_DIR/token" ] || die "no token (run: scripts/device.sh token)"
  local args=(-sN -X "$method" -w '\n[HTTP %{http_code}]\n' -H "Authorization: Bearer $(cat "$STATE_DIR/token")")
  [ -z "$body" ] || args+=(-H "Content-Type: application/json" -d "$body")
  curl "${args[@]}" "http://127.0.0.1:$HOST_PORT$path"
}

cmd_logs()       { adb logcat -d -s $LOG_TAGS; }
cmd_screenshot() { local f=${1:-screenshot.png}; adb exec-out screencap -p > "$f"; echo "saved $f"; }
cmd_ui()         { adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb shell cat /sdcard/ui.xml |
                     grep -oE '(text|resource-id|bounds)="[^"]*"' | paste - - - | grep -v 'text=""' | sed -E 's/resource-id="[^"\/]*\/?([^"]*)"/id=\1/'; }
cmd_tap()        { tap_id "$1"; }

cmd_up() { cmd_boot; cmd_build; cmd_install; cmd_model; cmd_start "${1:-CPU}"; cmd_token; cmd_forward; }

cmd_down() {
  "$SDK/platform-tools/adb" -s "$SERIAL" forward --remove "tcp:$HOST_PORT" 2>/dev/null || true
  case "$SERIAL" in emulator-*) "$SDK/platform-tools/adb" -s "$SERIAL" emu kill >/dev/null 2>&1 || true; echo "emulator $SERIAL stopped";; esac
  rm -f "$STATE_DIR/token"
}

cmd_help() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; cat <<'EOF'

Commands:
  up [BACKEND]          boot + build + install + model + start + token + forward (BACKEND default CPU)
  down                  remove the forward, stop the emulator, delete the saved token
  boot                  start the dedicated emulator headless and read-only
  build                 ./gradlew assembleDebug
  install [APK]         install the debug APK and grant runtime permissions
  model [FILE]          copy a model into the app's files dir and seed its saved model path
  start [BACKEND]       start InferenceService with the model; waits for 'Model loaded successfully' in logcat
  stop-service          send ACTION_STOP to InferenceService
  token                 generate an API token through the UI and save it (never printed)
  forward               adb forward HOST_PORT -> device 8080
  api METHOD PATH [JSON] call the REST API with the saved token
  logs                  app logcat (InferenceService, AiEngineManager, OpenAIServer, ...)
  ui                    list on-screen UI elements (text, id, bounds)
  tap ID                tap the UI element with resource id ID
  screenshot [FILE]     save a screenshot
EOF
}

c=${1:-help}; shift || true
case "$c" in
  up|down|boot|build|install|model|start|token|forward|api|logs|ui|tap|screenshot|help) "cmd_$c" "$@";;
  stop-service) cmd_stop_service;;
  *) die "unknown command '$c' (see: scripts/device.sh help)";;
esac
