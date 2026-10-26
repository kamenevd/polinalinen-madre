#!/bin/sh
set -eu
exec python3 "$(dirname "$(readlink -f "$0")")/ci_wait.py" "$@"
