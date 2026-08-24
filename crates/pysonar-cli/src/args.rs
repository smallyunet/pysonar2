use clap::{Args, Parser, Subcommand};
use std::path::PathBuf;

#[derive(Parser)]
#[command(name = "pysonar", disable_version_flag = true)]
#[command(about = "Whole-project type inference and semantic indexing for Python")]
pub(crate) struct Cli {
    #[command(subcommand)]
    pub(crate) command: Option<Command>,
}

#[derive(Subcommand)]
pub(crate) enum Command {
    Version,
    Doctor {
        #[arg(long, default_value = "json")]
        format: String,
    },
    Analyze(RootArgs),
    Plan(PlanArgs),
    Session(RootArgs),
    Context(QueryArgs),
    Impact(QueryArgs),
    Check(CheckArgs),
    Demo {
        source: PathBuf,
        output: PathBuf,
    },
}

#[derive(Args)]
pub(crate) struct RootArgs {
    #[arg(long, default_value = ".")]
    pub(crate) root: PathBuf,
    #[arg(long, default_value = "json")]
    pub(crate) format: String,
}

#[derive(Args)]
pub(crate) struct PlanArgs {
    #[arg(long, default_value = ".")]
    pub(crate) root: PathBuf,
    #[arg(long, required = true)]
    pub(crate) symbol: Vec<String>,
    #[arg(long, default_value = "inspect")]
    pub(crate) intent: String,
    #[arg(long, default_value_t = 8)]
    pub(crate) max_results: usize,
    #[arg(long, default_value = "compact-json")]
    pub(crate) format: String,
}

#[derive(Args)]
pub(crate) struct QueryArgs {
    #[arg(long, default_value = ".")]
    pub(crate) root: PathBuf,
    #[arg(long)]
    pub(crate) file: String,
    #[arg(long)]
    pub(crate) line: u32,
    #[arg(long, default_value_t = 1)]
    pub(crate) character: u32,
    #[arg(long, default_value_t = 50)]
    pub(crate) max_results: usize,
    #[arg(long, default_value = "json")]
    pub(crate) format: String,
}

#[derive(Args)]
pub(crate) struct CheckArgs {
    #[arg(long, default_value = ".")]
    pub(crate) root: PathBuf,
    #[arg(long)]
    pub(crate) changed: Vec<String>,
    #[arg(long, default_value = "json")]
    pub(crate) format: String,
}
