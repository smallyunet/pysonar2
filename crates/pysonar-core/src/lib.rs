//! Pure Rust whole-workspace semantic analysis used by the CLI, LSP, and WebAssembly builds.

mod analyzer;
mod model;
mod util;
mod workspace;

pub use model::{
    AnalysisSummary, CheckResult, ContextResult, PlanResult, RefreshKind, WorkspaceSymbol,
};
pub use workspace::Workspace;

pub const ENGINE_VERSION: &str = env!("CARGO_PKG_VERSION");
