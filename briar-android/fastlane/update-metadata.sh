#!/usr/bin/env bash

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)"

cd "$DIR"/..
tx pull -a -r briar.google-play-full-description,briar.google-play-short-description

for LANG_DIR in "$DIR"/metadata/android/*; do
  if [[ "$LANG_DIR" == *en-US ]]; then
    continue
  fi
  if [[ -f "$LANG_DIR/full_description.txt" ]] && [[ -f "$LANG_DIR/short_description.txt" ]]; then
    # every language uses the same app title
    cp "$DIR/metadata/android/en-US/title.txt" "$LANG_DIR/title.txt"
    echo "$LANG_DIR"
  else
    # not complete, remove
    rm -r "$LANG_DIR"
  fi
done

# Remove languages that aren't available in Google Play
rm -rf "$DIR/metadata/android/nb"
