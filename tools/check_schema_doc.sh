#!/usr/bin/env bash
# Rot-check (C-5): every Room @Entity tableName in data/model/ must have a
# matching "## <table>" section in docs/schema.md.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODEL_DIR="$ROOT/app/src/main/java/io/github/ntufar/deltasleep/data/model"
DOC="$ROOT/docs/schema.md"

fail=0
while IFS= read -r table; do
  if ! grep -q "^## $table$" "$DOC"; then
    echo "MISSING docs/schema.md section for table: $table" >&2
    fail=1
  fi
done < <(grep -rhoE 'tableName *= *"[^"]+"' "$MODEL_DIR" | sed -E 's/.*"(.*)"/\1/' | sort -u)

if [ "$fail" -ne 0 ]; then
  echo "schema doc check FAILED" >&2
  exit 1
fi
echo "schema doc check passed"
