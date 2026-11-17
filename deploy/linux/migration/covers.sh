#!/bin/bash
# Run only through compose.covers.yaml against the new deployment's empty pic volume.
# The bundle validator verifies the entire data package first. This tool validates
# the checksum manifest paths and rehashes only covers plus cover-manifest.json;
# it does not reread the multi-gigabyte database payload.
set -euo pipefail
export LC_ALL=C
umask 077

DATA_ROOT=/migration-data
TARGET_ROOT=/target-pic
MOUNTINFO=/proc/self/mountinfo

fail() { printf '%s\n' "Cover migration: $*" >&2; exit 1; }
on_exit() {
    status=$?
    if [ "$status" -ne 0 ]; then
        printf '%s\n' 'Cover migration failed. Any partial files in the NEW target volume are retained. A nonempty target cannot be retried; inspect it and select a different empty new volume. Nothing is deleted.' >&2
    fi
}
trap on_exit EXIT

[ "$(id -u)" = 0 ] || fail 'The dedicated migration tool requires root.'
for value in "${APP_UID:-}" "${APP_GID:-}"; do
    [[ "$value" =~ ^[1-9][0-9]{0,9}$ ]] || fail 'APP_UID and APP_GID must be nonzero decimal ids.'
    [ "$value" -le 4294967294 ] || fail 'APP_UID or APP_GID is out of range.'
done
[[ "${PIC_VOLUME_NAME:-}" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || fail 'PIC_VOLUME_NAME must identify the selected new volume.'
[ "${COVER_MIGRATION_ACK:-}" = "initialize-empty-pic:$PIC_VOLUME_NAME" ] || fail "Set COVER_MIGRATION_ACK=initialize-empty-pic:$PIC_VOLUME_NAME after checking the NEW volume name."

[ -d "$DATA_ROOT" ] && [ ! -L "$DATA_ROOT" ] || fail 'Missing regular data mount.'
[ -d "$TARGET_ROOT" ] && [ ! -L "$TARGET_ROOT" ] || fail 'Missing regular target volume mount.'
awk -v root="$DATA_ROOT" '$5 == root && $6 ~ /(^|,)ro(,|$)/ { found=1 } END { exit !found }' "$MOUNTINFO" || fail 'The data package must be mounted read-only.'
awk -v root="$TARGET_ROOT" '$5 == root && $6 ~ /(^|,)rw(,|$)/ { found=1 } END { exit !found }' "$MOUNTINFO" || fail 'The target must be a dedicated writable volume mount.'
[ -z "$(find "$TARGET_ROOT" -mindepth 1 -print -quit)" ] || fail 'Target pic volume is not empty; existing files and directories are never overwritten.'
[ -z "$(find "$DATA_ROOT" ! -type d ! -type f -print -quit)" ] || fail 'Data package contains a symlink or unsupported entry.'
[ -f "$DATA_ROOT/SHA256SUMS" ] || fail 'Missing data/SHA256SUMS.'
[ -f "$DATA_ROOT/cover-manifest.json" ] || fail 'Missing data/cover-manifest.json.'
[ -d "$DATA_ROOT/pic/localPic/cover" ] || fail 'Missing pic/localPic/cover directory.'

# Accept the unescaped sha256sum text format with safe relative ASCII paths only.
# This prevents sha256sum from reading outside the mounted data package.
awk '
    {
        if (length($0) < 67 || substr($0,65,2) != "  ") exit 1;
        hash=substr($0,1,64); name=substr($0,67);
        if (length(hash) != 64 || hash ~ /[^0-9a-fA-F]/) exit 1;
        if (name !~ /^[A-Za-z0-9][A-Za-z0-9._\/-]*$/ || name ~ /(^|\/)\./ || name ~ /\/\// || name ~ /\/$/) exit 1;
        if (name == "SHA256SUMS" || seen[name]++) exit 1;
        if (name ~ /^pic\// && name !~ /^pic\/localPic\/cover\/[1-9][0-9]*\.jpg$/) exit 1;
        count++;
    }
    END { if (count == 0) exit 1; }
' "$DATA_ROOT/SHA256SUMS" || fail 'Invalid, duplicate, escaped, or unsafe checksum path.'

listed_count=$(awk 'END { print NR }' "$DATA_ROOT/SHA256SUMS")
actual_count=$(find "$DATA_ROOT" -type f ! -path "$DATA_ROOT/SHA256SUMS" -print | wc -l)
[ "$listed_count" -eq "$actual_count" ] || fail 'Data package has unlisted files or missing checksum entries.'
awk 'substr($0,67) == "cover-manifest.json" { found=1 } END { exit !found }' "$DATA_ROOT/SHA256SUMS" || fail 'Missing cover-manifest.json checksum.'
# The temporary file is unique and confined to the tool container's tmpfs. The
# container removes its tmpfs on exit; this script deletes no source/target file.
CHECKSUMS_FILE=$(mktemp /tmp/novel-cover-checksums.XXXXXXXX)
awk 'substr($0,67) ~ /^pic\/localPic\/cover\/[1-9][0-9]*\.jpg$/ { print }' "$DATA_ROOT/SHA256SUMS" > "$CHECKSUMS_FILE"
(
    cd "$DATA_ROOT"
    sha256sum --check --strict --quiet "$CHECKSUMS_FILE" || exit 1
    awk 'substr($0,67) == "cover-manifest.json" { print }' SHA256SUMS | sha256sum --check --strict --quiet -
) || fail 'Cover data failed SHA-256 verification; target is untouched.'

# Only these three source directories may be copied. No additional empty directory
# or private file is accepted beneath pic, even if no checksum refers to it.
while IFS= read -r directory; do
    case "$directory" in "$DATA_ROOT/pic"|"$DATA_ROOT/pic/localPic"|"$DATA_ROOT/pic/localPic/cover") ;; *) fail 'Unexpected source pic directory.' ;; esac
done < <(find "$DATA_ROOT/pic" -type d -print)
cover_count=$(wc -l < "$CHECKSUMS_FILE")
[ "$cover_count" -gt 0 ] || fail 'No cover checksums found.'
source_count=$(find "$DATA_ROOT/pic" -type f -print | wc -l)
[ "$cover_count" -eq "$source_count" ] || fail 'Cover checksum count does not match source pic files.'

# Check emptiness again immediately before writing. --no-clobber also protects
# against overwriting a file created concurrently; final verification detects extras.
[ -z "$(find "$TARGET_ROOT" -mindepth 1 -print -quit)" ] || fail 'Target became nonempty before copying.'
cp -R --no-clobber --no-dereference -- "$DATA_ROOT/pic/." "$TARGET_ROOT/"
[ -z "$(find "$TARGET_ROOT" ! -type d ! -type f -print -quit)" ] || fail 'Unexpected target entry after copying.'
copied_count=$(find "$TARGET_ROOT" -type f -print | wc -l)
[ "$cover_count" -eq "$copied_count" ] || fail 'Copied file count does not match the cover manifest.'
chown -hR -- "$APP_UID:$APP_GID" "$TARGET_ROOT"
(
    cd "$TARGET_ROOT"
    awk '{ print substr($0,1,66) substr($0,71) }' "$CHECKSUMS_FILE" | sha256sum --check --strict --quiet -
) || fail 'Copied covers failed SHA-256 verification; partial target is retained.'
printf 'Cover migration verified: %s files in new volume %s, owner %s:%s.\n' "$cover_count" "$PIC_VOLUME_NAME" "$APP_UID" "$APP_GID"
