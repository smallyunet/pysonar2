use crate::args::{CheckArgs, PlanArgs, QueryArgs, RootArgs};
use crate::output::{UsageError, canonical_root, ensure_file, require_format, write_json};
use anyhow::Result;
use pysonar_core::Workspace;
use pysonar_protocol::{envelope, insert};
use std::time::Instant;

pub(crate) fn analyze(args: RootArgs) -> Result<()> {
    require_format(&args.format, &["json"])?;
    let root = canonical_root(&args.root)?;
    let started = Instant::now();
    let mut workspace = Workspace::from_root(&root)?;
    let summary = workspace.analyze();
    let mut result = envelope("analyze");
    for (key, value) in serde_json::to_value(summary)?
        .as_object()
        .expect("analysis summary serializes to object")
    {
        result.insert(key.clone(), value.clone());
    }
    insert(
        &mut result,
        "analysisMillis",
        started.elapsed().as_millis() as u64,
    );
    write_json(&result)
}

pub(crate) fn plan(args: PlanArgs) -> Result<()> {
    require_format(&args.format, &["compact-json", "json"])?;
    if !matches!(args.intent.as_str(), "inspect" | "change") {
        return Err(UsageError::invalid("--intent must be inspect or change"));
    }
    let root = canonical_root(&args.root)?;
    let started = Instant::now();
    let mut workspace = Workspace::from_root(&root)?;
    workspace.analyze();
    let plan = workspace.plan(
        &args.symbol,
        &args.intent,
        args.max_results.max(1),
        args.format == "compact-json",
    );
    let mut result = envelope("plan");
    insert(&mut result, "queries", plan.queries);
    if args.format == "json" {
        insert(&mut result, "root", root.to_string_lossy());
        insert(
            &mut result,
            "analysisMillis",
            started.elapsed().as_millis() as u64,
        );
        insert(
            &mut result,
            "limitations",
            [
                "Plans use saved-workspace definitions and references, not a complete runtime call graph.",
                "Dynamic imports, reflection, monkey patching, and unresolved types may be omitted.",
            ],
        );
    }
    write_json(&result)
}

pub(crate) fn query(args: QueryArgs, impact: bool) -> Result<()> {
    require_format(&args.format, &["json"])?;
    if args.line == 0 || args.character == 0 {
        return Err(UsageError::invalid(
            "--line and --character must be positive",
        ));
    }
    let root = canonical_root(&args.root)?;
    ensure_file(&root, &args.file)?;
    let started = Instant::now();
    let mut workspace = Workspace::from_root(&root)?;
    workspace.analyze();
    let value = if impact {
        workspace.impact(
            &args.file,
            args.line,
            args.character,
            args.max_results.max(1),
        )
    } else {
        workspace.context(
            &args.file,
            args.line,
            args.character,
            args.max_results.max(1),
        )
    };
    let mut result = envelope(if impact { "impact" } else { "context" });
    insert(&mut result, "root", root.to_string_lossy());
    for (key, value) in serde_json::to_value(value)?
        .as_object()
        .expect("context serializes to object")
    {
        result.insert(key.clone(), value.clone());
    }
    insert(
        &mut result,
        "analysisMillis",
        started.elapsed().as_millis() as u64,
    );
    write_json(&result)
}

pub(crate) fn check(args: CheckArgs) -> Result<()> {
    require_format(&args.format, &["json"])?;
    let root = canonical_root(&args.root)?;
    let started = Instant::now();
    let mut workspace = Workspace::from_root(&root)?;
    workspace.analyze();
    let changed: Vec<_> = args
        .changed
        .iter()
        .flat_map(|value| value.split(','))
        .filter(|value| !value.trim().is_empty())
        .map(|value| value.trim().to_string())
        .collect();
    let value = workspace.check(&changed);
    let mut result = envelope("check");
    insert(&mut result, "root", root.to_string_lossy());
    for (key, value) in serde_json::to_value(value)?
        .as_object()
        .expect("check serializes to object")
    {
        result.insert(key.clone(), value.clone());
    }
    insert(
        &mut result,
        "analysisMillis",
        started.elapsed().as_millis() as u64,
    );
    write_json(&result)
}
