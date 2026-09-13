#!/usr/bin/env sh
set -eu
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
echo "No se encontró Gradle. Abre el proyecto en Android Studio o usa el workflow de GitHub Actions incluido." >&2
exit 1
