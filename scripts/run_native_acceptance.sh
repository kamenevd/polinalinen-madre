#!/usr/bin/env bash
# One command for emulator-runner: it splits script lines into separate sh -c calls.
# Keep independent lanes running after failures; never turn a red lane into success.
set -euo pipefail
native="${1:?Expected explicit bake_numbers_native true or false}"
case "$native" in true|false) ;; *) exit 2 ;; esac
exact="${2:-false}"
case "$exact" in true|false) ;; *) exit 2 ;; esac
# QA now precedes the minified helper, so preserve its emulator-only serial guard
# before the first lane can touch adb; never switch to another connected device.
serial="$(adb get-serialno)"
[[ "$serial" == emulator-* ]] || { printf 'Only the authorized CI emulator is admitted\n' >&2; exit 2; }
export ANDROID_SERIAL="$serial"
out="$PWD/performance-evidence/native-acceptance"
mkdir -p "$out"
printf 'group\texit_code\tlog_exit_code\n' > "$out/exit-codes.tsv"
result=0
run_group() {
    local name="$1"
    shift
    local -a codes
    set +e
    "$@" 2>&1 | tee "$out/$name.log"
    codes=("${PIPESTATUS[@]}")
    set -e
    printf '%s\t%s\t%s\n' "$name" "${codes[0]}" "${codes[1]}" >> "$out/exit-codes.tsv"
    if (( codes[0] != 0 || codes[1] != 0 )); then result=1; fi
}
if [[ "$exact" == true ]]; then
    run_group exact-qa-timing python3 scripts/run_exact_qa_timing.py
fi
if [[ "$native" == true || "$exact" == true ]]; then
    # Ordinary, unmodified .qa must get its own attempt before legacy debug.
    run_group qa-runtime bash scripts/run_qa_runtime_acceptance.sh
    run_group minified-persistence python3 scripts/run_minified_plan_regression.py
fi
run_group legacy-debug-domain bash scripts/run_instrumented_acceptance.sh
printf '%s\n' "$result" > "$out/overall-exit-code.txt"
exit "$result"
