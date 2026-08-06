#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)
corpus=${1:-"$project_root/demo_project"}
iterations=${2:-5}
change_file=${3:-}
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
output_dir="$project_root/target/benchmarks"
output="$output_dir/analyzer-$timestamp.json"

mkdir -p "$output_dir"

if [ ! -f "$project_root/target/pysonar-3.3.3.jar" ]; then
  echo "Build target/pysonar-3.3.3.jar with mvn package first." >&2
  exit 2
fi

if [ -n "$change_file" ]; then
  java -cp "$project_root/target/pysonar-3.3.3.jar" \
    org.yinwang.pysonar.bench.AnalyzerBenchmark \
    --root "$corpus" \
    --warmups 1 \
    --iterations "$iterations" \
    --cache-dir "$output_dir/cache" \
    --change-file "$change_file" > "$output"
else
  java -cp "$project_root/target/pysonar-3.3.3.jar" \
    org.yinwang.pysonar.bench.AnalyzerBenchmark \
    --root "$corpus" \
    --warmups 1 \
    --iterations "$iterations" \
    --cache-dir "$output_dir/cache" > "$output"
fi

echo "$output"
