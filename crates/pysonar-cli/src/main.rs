use anyhow::{Context, Result};
use clap::{Args, Parser, Subcommand, ValueEnum};
use pysonar_core::Workspace;
use pysonar_protocol::{CLI_VERSION, SessionRequest, envelope, insert};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs;
use std::io::{self, BufRead, Write};
use std::path::{Path, PathBuf};
use std::time::Instant;

const CAPABILITIES: &[&str] = &[
    "symbol-plan",
    "persistent-session",
    "context",
    "reference-impact",
    "diagnostics",
    "skill-install",
    "native-rust",
    "webassembly",
];

#[derive(Parser)]
#[command(name = "pysonar", disable_version_flag = true)]
#[command(about = "Whole-project semantic analysis for safe Python changes")]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Subcommand)]
enum Command {
    Version,
    Doctor {
        #[arg(long, default_value = "json")]
        format: String,
    },
    Plan(PlanArgs),
    Session(RootArgs),
    Context(QueryArgs),
    Impact(QueryArgs),
    Check(CheckArgs),
    Skill(SkillArgs),
    Demo {
        source: PathBuf,
        output: PathBuf,
    },
}

#[derive(Args)]
struct RootArgs {
    #[arg(long, default_value = ".")]
    root: PathBuf,
    #[arg(long, default_value = "json")]
    format: String,
}

#[derive(Args)]
struct PlanArgs {
    #[arg(long, default_value = ".")]
    root: PathBuf,
    #[arg(long, required = true)]
    symbol: Vec<String>,
    #[arg(long, default_value = "inspect")]
    intent: String,
    #[arg(long, default_value_t = 8)]
    max_results: usize,
    #[arg(long, default_value = "compact-json")]
    format: String,
}

#[derive(Args)]
struct QueryArgs {
    #[arg(long, default_value = ".")]
    root: PathBuf,
    #[arg(long)]
    file: String,
    #[arg(long)]
    line: u32,
    #[arg(long, default_value_t = 1)]
    character: u32,
    #[arg(long, default_value_t = 50)]
    max_results: usize,
    #[arg(long, default_value = "json")]
    format: String,
}

#[derive(Args)]
struct CheckArgs {
    #[arg(long, default_value = ".")]
    root: PathBuf,
    #[arg(long)]
    changed: Vec<String>,
    #[arg(long, default_value = "json")]
    format: String,
}

#[derive(Args)]
struct SkillArgs {
    #[arg(value_enum)]
    action: SkillAction,
    #[arg(long, default_value = "portable")]
    agent: String,
    #[arg(long, default_value = "user")]
    scope: String,
    #[arg(long, default_value = ".")]
    root: PathBuf,
}

#[derive(Clone, Copy, ValueEnum)]
enum SkillAction {
    Install,
    Update,
    Uninstall,
    Doctor,
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if matches!(args.get(1).map(String::as_str), Some("--version" | "-V")) {
        println!("{CLI_VERSION}");
        return;
    }
    if args.len() == 3 && !is_command(&args[1]) {
        if let Err(error) = generate_demo(Path::new(&args[1]), Path::new(&args[2])) {
            write_error(&error.to_string(), 1);
            std::process::exit(1);
        }
        return;
    }
    match run(Cli::parse()) {
        Ok(()) => {}
        Err(error) => {
            let exit_code = error
                .downcast_ref::<UsageError>()
                .map_or(1, |error| error.0);
            write_error(&error.to_string(), exit_code);
            std::process::exit(exit_code);
        }
    }
}

fn run(cli: Cli) -> Result<()> {
    match cli.command {
        Some(Command::Version) => println!("{CLI_VERSION}"),
        Some(Command::Doctor { format }) => {
            require_format(&format, &["json"])?;
            let mut result = envelope("doctor");
            insert(&mut result, "rustVersion", "1.88.0");
            insert(&mut result, "runtime", "native-rust");
            insert(
                &mut result,
                "python",
                json!({"required": false, "used": false}),
            );
            insert(
                &mut result,
                "java",
                json!({"required": false, "used": false}),
            );
            insert(&mut result, "status", "ok");
            insert(&mut result, "capabilities", CAPABILITIES);
            write_json(&result)?;
        }
        Some(Command::Plan(args)) => plan(args)?,
        Some(Command::Session(args)) => session(args)?,
        Some(Command::Context(args)) => query(args, false)?,
        Some(Command::Impact(args)) => query(args, true)?,
        Some(Command::Check(args)) => check(args)?,
        Some(Command::Skill(args)) => skill(args)?,
        Some(Command::Demo { source, output }) => generate_demo(&source, &output)?,
        None => print_help(),
    }
    Ok(())
}

fn plan(args: PlanArgs) -> Result<()> {
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

fn query(args: QueryArgs, impact: bool) -> Result<()> {
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

fn check(args: CheckArgs) -> Result<()> {
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

fn session(args: RootArgs) -> Result<()> {
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

fn session_symbols(value: Option<&Value>) -> Result<Vec<String>> {
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

fn skill(args: SkillArgs) -> Result<()> {
    let target = skill_target(&args)?;
    let action = match args.action {
        SkillAction::Install => "install",
        SkillAction::Update => "update",
        SkillAction::Uninstall => "uninstall",
        SkillAction::Doctor => "doctor",
    };
    let mut result = envelope(&format!("skill-{action}"));
    insert(&mut result, "agent", &args.agent);
    insert(&mut result, "scope", &args.scope);
    insert(&mut result, "path", target.to_string_lossy());
    match args.action {
        SkillAction::Install => {
            if target.exists() && fs::read_dir(&target)?.next().is_some() {
                return Err(UsageError(
                    3,
                    format!(
                        "Skill already exists at {}; use 'skill update' for a managed installation",
                        target.display()
                    ),
                )
                .into());
            }
            install_skill(&target, false)?;
            insert(&mut result, "status", "installed");
        }
        SkillAction::Update => {
            install_skill(&target, true)?;
            insert(&mut result, "status", "updated");
        }
        SkillAction::Uninstall => {
            if target.exists() {
                verify_skill(&target)?;
                fs::remove_dir_all(&target)?;
            }
            insert(&mut result, "status", "uninstalled");
        }
        SkillAction::Doctor => {
            let installed = target.join("SKILL.md").is_file();
            insert(
                &mut result,
                "status",
                if installed { "ok" } else { "missing" },
            );
            insert(&mut result, "installed", installed);
        }
    }
    write_json(&result)
}

fn skill_target(args: &SkillArgs) -> Result<PathBuf> {
    if !matches!(args.scope.as_str(), "user" | "project") {
        return Err(UsageError::invalid("--scope must be user or project"));
    }
    let project = args.scope == "project";
    let home = std::env::var_os("HOME")
        .map(PathBuf::from)
        .context("HOME is unavailable")?;
    let root = if project {
        canonical_root(&args.root)?
    } else {
        home
    };
    let base = match args.agent.as_str() {
        "portable" | "codex" | "gemini" => root.join(".agents/skills"),
        "claude" => root.join(".claude/skills"),
        "copilot" => root.join(if project {
            ".github/skills"
        } else {
            ".copilot/skills"
        }),
        "cursor" => root.join(".cursor/skills"),
        _ => {
            return Err(UsageError::invalid(format!(
                "Unsupported --agent: {}",
                args.agent
            )));
        }
    };
    Ok(base.join("pysonar-code-intelligence"))
}

fn install_skill(target: &Path, update: bool) -> Result<()> {
    if update {
        verify_skill(target)?;
    }
    let files = embedded_skill();
    fs::create_dir_all(target)?;
    let mut manifest = format!("version={CLI_VERSION}\n");
    for (relative, content) in files {
        let destination = target.join(relative);
        if let Some(parent) = destination.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&destination, content)?;
        manifest.push_str(&format!("{}={}\n", relative, hash(content.as_bytes())));
    }
    fs::write(target.join(".pysonar-managed"), manifest)?;
    Ok(())
}

fn verify_skill(target: &Path) -> Result<()> {
    let marker = target.join(".pysonar-managed");
    if !marker.is_file() {
        return Err(UsageError(
            3,
            format!(
                "Refusing to change an unmanaged skill at {}",
                target.display()
            ),
        )
        .into());
    }
    let expected: BTreeMap<_, _> = fs::read_to_string(marker)?
        .lines()
        .filter_map(|line| line.split_once('='))
        .filter(|(key, _)| *key != "version")
        .map(|(key, value)| (key.to_string(), value.to_string()))
        .collect();
    for (relative, _) in embedded_skill() {
        let file = target.join(relative);
        let actual = fs::read(&file)
            .map(|content| hash(&content))
            .unwrap_or_default();
        if expected.get(relative) != Some(&actual) {
            return Err(UsageError(
                3,
                format!(
                    "Refusing to overwrite locally modified skill file: {}",
                    file.display()
                ),
            )
            .into());
        }
    }
    Ok(())
}

fn embedded_skill() -> [(&'static str, &'static str); 3] {
    [
        (
            "SKILL.md",
            include_str!("../../../skills/pysonar-code-intelligence/SKILL.md"),
        ),
        (
            "agents/openai.yaml",
            include_str!("../../../skills/pysonar-code-intelligence/agents/openai.yaml"),
        ),
        (
            "references/cli-schema.md",
            include_str!("../../../skills/pysonar-code-intelligence/references/cli-schema.md"),
        ),
    ]
}

fn generate_demo(source: &Path, output: &Path) -> Result<()> {
    let root = canonical_root(source)?;
    let mut workspace = Workspace::from_root(&root)?;
    let summary = workspace.analyze();
    fs::create_dir_all(output)?;
    let files = workspace.paths().map(str::to_string).collect::<Vec<_>>();
    let mut navigation = String::new();
    let mut sections = String::new();
    for file in &files {
        navigation.push_str(&format!(
            "<a href=\"#{}\">{}</a>",
            html_id(file),
            escape(file)
        ));
        sections.push_str(&format!(
            "<section id=\"{}\"><h2>{}</h2><pre><code>{}</code></pre></section>",
            html_id(file),
            escape(file),
            escape(workspace.source(file).unwrap_or_default())
        ));
    }
    let html = format!(
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>PySonar2 analysis</title><style>{}</style></head><body><aside><strong>PySonar2 v4</strong><p>{} files · {} symbols</p>{}</aside><main>{}</main></body></html>",
        DEMO_CSS, summary.file_count, summary.symbol_count, navigation, sections
    );
    fs::write(output.join("index.html"), html)?;
    println!(
        "Wrote {} source pages and an index to {}",
        files.len(),
        output.display()
    );
    Ok(())
}

const DEMO_CSS: &str = "*{box-sizing:border-box}body{margin:0;background:#0b1020;color:#dce5f4;font:15px ui-monospace,SFMono-Regular,Menlo,monospace;display:grid;grid-template-columns:260px 1fr;min-height:100vh}aside{position:sticky;top:0;height:100vh;padding:24px;border-right:1px solid #24304a;background:#11182b}aside a{display:block;color:#8bd5ca;padding:7px 0;text-decoration:none;overflow-wrap:anywhere}main{padding:32px;min-width:0}section{margin:0 auto 28px;max-width:1100px}h2{font:600 15px ui-sans-serif,system-ui;color:#f4f7fb}pre{overflow:auto;padding:22px;border:1px solid #26334e;border-radius:12px;background:#0e1526;line-height:1.6}@media(max-width:760px){body{display:block}aside{position:relative;height:auto;border-right:0;border-bottom:1px solid #24304a}main{padding:18px}}";

fn canonical_root(root: &Path) -> Result<PathBuf> {
    if !root.is_dir() {
        return Err(UsageError::invalid(format!(
            "Project root is not a directory: {}",
            root.display()
        )));
    }
    root.canonicalize().context("resolve project root")
}

fn ensure_file(root: &Path, file: &str) -> Result<()> {
    let candidate = root
        .join(file)
        .canonicalize()
        .context("resolve query file")?;
    if !candidate.starts_with(root) || !candidate.is_file() {
        return Err(UsageError::invalid(format!(
            "File must be inside the project root: {file}"
        )));
    }
    Ok(())
}

fn require_format(format: &str, supported: &[&str]) -> Result<()> {
    if supported.contains(&format) {
        Ok(())
    } else {
        Err(UsageError::invalid(format!(
            "--format must be one of: {}",
            supported.join(", ")
        )))
    }
}

fn write_json(value: &impl serde::Serialize) -> Result<()> {
    let stdout = io::stdout();
    let mut output = stdout.lock();
    serde_json::to_writer(&mut output, value)?;
    output.write_all(b"\n")?;
    output.flush()?;
    Ok(())
}

fn write_error(message: &str, exit_code: i32) {
    let mut value = envelope("error");
    insert(&mut value, "error", message);
    insert(&mut value, "exitCode", exit_code);
    let _ = serde_json::to_writer(io::stderr(), &value);
    eprintln!();
}

fn hash(content: &[u8]) -> String {
    format!("{:x}", Sha256::digest(content))
}

fn escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

fn html_id(value: &str) -> String {
    format!(
        "file-{}",
        hash(value.as_bytes()).chars().take(12).collect::<String>()
    )
}

fn is_command(value: &str) -> bool {
    matches!(
        value,
        "version"
            | "doctor"
            | "plan"
            | "session"
            | "context"
            | "impact"
            | "check"
            | "skill"
            | "demo"
            | "help"
            | "--help"
            | "-h"
    )
}

fn print_help() {
    println!(
        "PySonar2 semantic engine CLI {CLI_VERSION}\nUsage:\n  pysonar version | --version | -V\n  pysonar doctor --format json\n  pysonar plan --root DIR --symbol NAME [--intent inspect|change]\n  pysonar session --root DIR --format json\n  pysonar context --root DIR --file FILE --line N [--character N]\n  pysonar impact --root DIR --file FILE --line N [--character N]\n  pysonar check --root DIR [--changed FILE]\n  pysonar skill <install|update|uninstall|doctor>\n  pysonar demo SOURCE OUTPUT"
    );
}

#[derive(Debug)]
struct UsageError(i32, String);

impl UsageError {
    fn invalid(message: impl Into<String>) -> anyhow::Error {
        Self(2, message.into()).into()
    }
}

impl std::fmt::Display for UsageError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.1)
    }
}

impl std::error::Error for UsageError {}
