use anyhow::{Context, Result};
use pysonar_protocol::{CLI_VERSION, envelope, insert};
use sha2::{Digest, Sha256};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

pub(crate) fn canonical_root(root: &Path) -> Result<PathBuf> {
    if !root.is_dir() {
        return Err(UsageError::invalid(format!(
            "Project root is not a directory: {}",
            root.display()
        )));
    }
    root.canonicalize().context("resolve project root")
}

pub(crate) fn ensure_file(root: &Path, file: &str) -> Result<()> {
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

pub(crate) fn require_format(format: &str, supported: &[&str]) -> Result<()> {
    if supported.contains(&format) {
        Ok(())
    } else {
        Err(UsageError::invalid(format!(
            "--format must be one of: {}",
            supported.join(", ")
        )))
    }
}

pub(crate) fn write_json(value: &impl serde::Serialize) -> Result<()> {
    let stdout = io::stdout();
    let mut output = stdout.lock();
    serde_json::to_writer(&mut output, value)?;
    output.write_all(b"\n")?;
    output.flush()?;
    Ok(())
}

pub(crate) fn write_error(message: &str, exit_code: i32) {
    let mut value = envelope("error");
    insert(&mut value, "error", message);
    insert(&mut value, "exitCode", exit_code);
    let _ = serde_json::to_writer(io::stderr(), &value);
    eprintln!();
}

pub(crate) fn hash(content: &[u8]) -> String {
    format!("{:x}", Sha256::digest(content))
}

pub(crate) fn escape(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

pub(crate) fn html_id(value: &str) -> String {
    format!(
        "file-{}",
        hash(value.as_bytes()).chars().take(12).collect::<String>()
    )
}

pub(crate) fn is_command(value: &str) -> bool {
    matches!(
        value,
        "version"
            | "doctor"
            | "analyze"
            | "plan"
            | "session"
            | "context"
            | "impact"
            | "check"
            | "demo"
            | "help"
            | "--help"
            | "-h"
    )
}

pub(crate) fn print_help() {
    println!(
        "PySonar2 type inference and semantic index CLI {CLI_VERSION}\nUsage:\n  pysonar version | --version | -V\n  pysonar doctor --format json\n  pysonar analyze --root DIR --format json\n  pysonar plan --root DIR --symbol NAME [--intent inspect|change]\n  pysonar session --root DIR --format json\n  pysonar context --root DIR --file FILE --line N [--character N]\n  pysonar impact --root DIR --file FILE --line N [--character N]\n  pysonar check --root DIR [--changed FILE]\n  pysonar demo SOURCE OUTPUT"
    );
}

#[derive(Debug)]
pub(crate) struct UsageError(pub(crate) i32, String);

impl UsageError {
    pub(crate) fn invalid(message: impl Into<String>) -> anyhow::Error {
        Self(2, message.into()).into()
    }
}

impl std::fmt::Display for UsageError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.1)
    }
}

impl std::error::Error for UsageError {}
