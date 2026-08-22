#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)
corpus=${1:-"$project_root/demo_project"}
iterations=${2:-10}
symbol=${3:-User}
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
output_dir="$project_root/target/benchmarks"
binary="$project_root/target/release/pysonar"

if ! command -v hyperfine >/dev/null 2>&1; then
  echo "hyperfine is required: https://github.com/sharkdp/hyperfine" >&2
  exit 127
fi
if [ ! -x "$binary" ]; then
  echo "Build the release CLI with 'cargo build --release -p pysonar-cli'." >&2
  exit 2
fi

mkdir -p "$output_dir"
output="$output_dir/analyzer-$timestamp.json"
hyperfine --warmup 2 --runs "$iterations" --export-json "$output" \
  "$binary plan --root '$corpus' --symbol '$symbol' --intent inspect --format compact-json"
echo "$output"
