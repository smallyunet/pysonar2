#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_root=$(CDPATH= cd -- "$script_dir/../.." && pwd)
corpus=${1:-"$project_root/demo_project"}
change_file=${2:-}
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
output_dir="$project_root/target/profiles"
recording="$output_dir/analyzer-$timestamp.jfr"
result="$output_dir/analyzer-$timestamp.json"

mkdir -p "$output_dir"

if [ ! -f "$project_root/target/pysonar-3.3.3.jar" ]; then
  echo "Build target/pysonar-3.3.3.jar with mvn package first." >&2
  exit 2
fi

if [ -n "$change_file" ]; then
  java \
    -Xlog:jfr+startup=off \
    -XX:StartFlightRecording="filename=$recording,settings=profile,dumponexit=true" \
    -cp "$project_root/target/pysonar-3.3.3.jar" \
    org.yinwang.pysonar.bench.AnalyzerBenchmark \
    --root "$corpus" \
    --warmups 1 \
    --iterations 3 \
    --cache-dir "$output_dir/cache" \
    --change-file "$change_file" > "$result"
else
  java \
    -Xlog:jfr+startup=off \
    -XX:StartFlightRecording="filename=$recording,settings=profile,dumponexit=true" \
    -cp "$project_root/target/pysonar-3.3.3.jar" \
    org.yinwang.pysonar.bench.AnalyzerBenchmark \
    --root "$corpus" \
    --warmups 1 \
    --iterations 3 \
    --cache-dir "$output_dir/cache" > "$result"
fi

echo "$recording"
echo "$result"
