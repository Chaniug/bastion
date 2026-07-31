#!/usr/bin/env bash
# Convert all bundled Stratum simple-icons (PNG) to lossless WebP.
#
# Why lossless WebP (not lossy q85):
#   - These are flat, brand UI icons where crisp edges + clean alpha matter.
#   - On simple-icons, lossless WebP already yields ~25% size reduction,
#     essentially matching lossy q85 (~26%) while guaranteeing ZERO quality
#     loss and no alpha-fringe artifacts.
#   - Android BitmapFactory decodes lossless WebP since API 18; Bastion minSdk=26.
#
# Source dirs (relative to repo root):
#   Bastion/app/src/main/assets/stratum_icons/icons
#   Bastion/app/src/main/assets/stratum_icons/extraicons
#
# After conversion the original .png files are removed. The Kotlin loader
# (PasswordCustomIconSupport.kt) is updated to look for .webp (with a .png
# fallback for safety).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ICONS_DIR="$ROOT/Bastion/app/src/main/assets/stratum_icons/icons"
EXTRA_DIR="$ROOT/Bastion/app/src/main/assets/stratum_icons/extraicons"

if ! command -v convert >/dev/null 2>&1; then
  echo "ERROR: ImageMagick 'convert' not found in PATH." >&2
  exit 1
fi

convert_one_dir() {
  local dir="$1"
  local png_count webp_count orig_bytes webp_bytes
  png_count=$(find "$dir" -maxdepth 1 -name '*.png' | wc -l)
  if [ "$png_count" -eq 0 ]; then
    echo "SKIP (no png): $dir"
    return 0
  fi
  orig_bytes=$(find "$dir" -maxdepth 1 -name '*.png' -printf '%s\n' | awk '{s+=$1} END{print s+0}')
  local ok=0 fail=0
  while IFS= read -r -d '' f; do
    if convert "$f" -define webp:lossless=true "${f%.png}.webp"; then
      ok=$((ok+1))
    else
      echo "FAILED: $f" >&2
      fail=$((fail+1))
    fi
  done < <(find "$dir" -maxdepth 1 -name '*.png' -print0)
  # Remove originals only if every conversion succeeded.
  if [ "$fail" -eq 0 ]; then
    find "$dir" -maxdepth 1 -name '*.png' -delete
  else
    echo "NOT removing originals in $dir due to $fail failure(s)." >&2
    return 1
  fi
  webp_bytes=$(find "$dir" -maxdepth 1 -name '*.webp' -printf '%s\n' | awk '{s+=$1} END{print s+0}')
  webp_count=$(find "$dir" -maxdepth 1 -name '*.webp' | wc -l)
  local saved=0
  if [ "$orig_bytes" -gt 0 ]; then saved=$(( (100*(orig_bytes-webp_bytes))/orig_bytes )); fi
  echo "CONVERTED: $dir  png=$png_count -> webp=$webp_count  $((orig_bytes/1024))KB -> $((webp_bytes/1024))KB  (~${saved}% smaller)"
}

convert_one_dir "$ICONS_DIR"
convert_one_dir "$EXTRA_DIR"
echo "DONE."
