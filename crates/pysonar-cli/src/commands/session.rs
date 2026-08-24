use crate::args::RootArgs;
use crate::output::{UsageError, canonical_root, hash, require_format, write_error, write_json};
use anyhow::Result;
use pysonar_core::Workspace;
use pysonar_protocol::{SessionRequest, envelope, insert};
use serde_json::Value;
use std::collections::BTreeMap;
use std::io::{self, BufRead};
use std::time::Instant;

pub(crate) fn session(args: RootArgs) -> Result<()> {
    require_format(&args.format, &["json"])?;
    let root = canonical_root(&args.root)?;
    let started = Instant::now();
    let mut workspace = Workspace::from_root(&root)?;
    let summary = workspace.analyze();
    let mut ready = envelope("session-ready");
    insert(&mut ready, "root", root.to_string_lossy());
    insert(&mut ready, "fileCount", summary.file_count);
    insert(
        &mut ready,
        "analysisMillis",
        started.elapsed().as_millis() as u64,
    );
    add_rebuild_metrics(
        &mut ready,
        "full",
        summary.file_count,
        summary.file_count,
        "initial-analysis",
    );
    write_json(&ready)?;

    for line in io::stdin().lock().lines() {
        let line = line?;
        if line.trim().is_empty() {
            continue;
        }
        let request: SessionRequest = match serde_json::from_str(&line) {
            Ok(request) => request,
            Err(_) => {
                write_error("Session input must be one JSON object per line", 2);
                continue;
            }
        };
        let mut response = match request.command.as_str() {
            "plan" => {
                let symbols = session_symbols(request.symbol.as_ref())?;
                let intent = request.intent.as_deref().unwrap_or("inspect");
                if !matches!(intent, "inspect" | "change") {
                    return Err(UsageError::invalid("intent must be inspect or change"));
                }
                let value = workspace.plan(
                    &symbols,
                    intent,
                    request.max_results.unwrap_or(8).max(1),
                    true,
                );
                let mut response = envelope("plan");
                insert(&mut response, "queries", value.queries);
                response
            }
            "refresh" => {
                let refreshed = Workspace::from_root(&root)?;
                let old_hashes: BTreeMap<_, _> = workspace
                    .paths()
                    .map(|path| {
                        (
                            path.to_string(),
                            hash(workspace.source(path).unwrap_or_default().as_bytes()),
                        )
                    })
                    .collect();
                let new_hashes: BTreeMap<_, _> = refreshed
                    .paths()
                    .map(|path| {
                        (
                            path.to_string(),
                            hash(refreshed.source(path).unwrap_or_default().as_bytes()),
                        )
                    })
                    .collect();
                let changed = old_hashes
                    .keys()
                    .chain(new_hashes.keys())
                    .collect::<std::collections::BTreeSet<_>>()
                    .into_iter()
                    .filter(|path| old_hashes.get(*path) != new_hashes.get(*path))
                    .count();
                workspace = refreshed;
                let started = Instant::now();
                let summary = workspace.analyze();
                let mut response = envelope("refresh");
                insert(&mut response, "fileCount", summary.file_count);
                insert(
                    &mut response,
                    "analysisMillis",
                    started.elapsed().as_millis() as u64,
                );
                add_rebuild_metrics(
                    &mut response,
                    if changed == 0 { "no_change" } else { "full" },
                    changed,
                    if changed == 0 { 0 } else { summary.file_count },
                    if changed == 0 {
                        "content-hashes-unchanged"
                    } else {
                        "workspace-content-changed"
                    },
                );
                response
            }
            "quit" => envelope("quit"),
            _ => {
                return Err(UsageError::invalid(
                    "Session command must be plan, refresh, or quit",
                ));
            }
        };
        if let Some(id) = request.id {
            insert(&mut response, "id", id);
        }
        write_json(&response)?;
        if request.command == "quit" {
            break;
        }
    }
    Ok(())
}

fn add_rebuild_metrics(
    response: &mut BTreeMap<String, Value>,
    mode: &str,
    changed: usize,
    analyzed: usize,
    reason: &str,
) {
    insert(response, "rebuildMode", mode);
    insert(response, "changedFiles", changed);
    insert(response, "affectedFiles", analyzed);
    insert(response, "analyzedFiles", analyzed);
    insert(
        response,
        "astCacheHits",
        if mode == "no_change" { analyzed } else { 0 },
    );
    insert(response, "astCacheMisses", analyzed);
    insert(response, "rebuildReason", reason);
}

pub(crate) fn session_symbols(value: Option<&Value>) -> Result<Vec<String>> {
    match value {
        Some(Value::String(value)) if !value.is_empty() => Ok(vec![value.clone()]),
        Some(Value::Array(values)) if !values.is_empty() => values
            .iter()
            .map(|value| {
                value
                    .as_str()
                    .filter(|value| !value.is_empty())
                    .map(str::to_string)
                    .ok_or_else(|| {
                        anyhow::anyhow!("Session plan symbol array must contain strings")
                    })
            })
            .collect(),
        _ => Err(UsageError::invalid("Session plan requires symbol")),
    }
}
