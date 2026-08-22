use indexmap::IndexMap;
use pysonar_protocol::{Coverage, Diagnostic, PlanQuery, QueryPosition, SourceLocation};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeSet, HashMap};

pub(crate) type SymbolId = usize;
pub(crate) type ScopeId = usize;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalysisSummary {
    pub root: String,
    pub file_count: usize,
    pub parsed_files: usize,
    pub failed_files: Vec<String>,
    pub symbol_count: usize,
    pub reference_count: usize,
    pub coverage_status: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContextResult {
    pub query: QueryPosition,
    pub symbol: Option<String>,
    pub inferred_type: Option<String>,
    pub definitions: Vec<SourceLocation>,
    pub references: Vec<SourceLocation>,
    pub truncated: bool,
    pub coverage_status: String,
    pub applicable: bool,
    pub confidence: String,
    pub unsupported_semantics: Vec<String>,
    pub coverage: Coverage,
    pub limitations: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub impact_kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub affected_files: Option<Vec<String>>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanResult {
    pub queries: Vec<PlanQuery>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CheckResult {
    pub changed: Vec<String>,
    pub diagnostics: Vec<Diagnostic>,
    pub diagnostic_count: usize,
    pub limitations: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RefreshKind {
    Full,
    NoChange,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceSymbol {
    pub name: String,
    pub qualified_name: String,
    pub kind: String,
    pub location: SourceLocation,
    pub inferred_type: Option<String>,
}

#[derive(Clone, Debug)]
pub(crate) struct Snapshot {
    pub files: IndexMap<String, FileInfo>,
    pub symbols: Vec<Symbol>,
    pub occurrences: Vec<Occurrence>,
    pub failed_files: BTreeSet<String>,
    pub diagnostics: Vec<Diagnostic>,
    pub framework_semantics: BTreeSet<String>,
    pub unsupported_symbols: BTreeSet<String>,
}

#[derive(Clone, Debug)]
pub(crate) struct FileInfo {
    pub source: String,
    pub module: String,
}

#[derive(Clone, Debug)]
pub(crate) struct Symbol {
    pub name: String,
    pub qualified_name: String,
    pub kind: SymbolKind,
    pub definition: Span,
    pub inferred_type: Option<String>,
    pub alias_target: Option<String>,
    pub references: Vec<Span>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SymbolKind {
    Module,
    Class,
    Function,
    Method,
    Property,
    Variable,
    Parameter,
    Import,
}

impl SymbolKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Module => "Module",
            Self::Class => "Class",
            Self::Function => "Function",
            Self::Method => "Method",
            Self::Property => "Property",
            Self::Variable => "Variable",
            Self::Parameter => "Parameter",
            Self::Import => "Import",
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct Scope {
    pub parent: Option<ScopeId>,
    pub kind: ScopeKind,
    pub qualified_name: String,
    pub bindings: IndexMap<String, SymbolId>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum ScopeKind {
    Module,
    Class,
    Function,
}

#[derive(Clone, Debug)]
pub(crate) struct Occurrence {
    pub file: String,
    pub name: String,
    pub start: u32,
    pub end: u32,
    pub symbol: Option<SymbolId>,
}

impl Occurrence {
    pub fn span(&self) -> Span {
        Span {
            file: self.file.clone(),
            start: self.start,
            end: self.end,
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct Span {
    pub file: String,
    pub start: u32,
    pub end: u32,
}

pub(crate) struct AnalyzerState<'a> {
    pub sources: &'a IndexMap<String, String>,
    pub files: IndexMap<String, FileInfo>,
    pub parsed: HashMap<String, ruff_python_ast::ModModule>,
    pub symbols: Vec<Symbol>,
    pub scopes: Vec<Scope>,
    pub module_scopes: HashMap<String, ScopeId>,
    pub scope_keys: HashMap<(String, u32), ScopeId>,
    pub qnames: HashMap<String, SymbolId>,
    pub occurrences: Vec<Occurrence>,
    pub failed_files: BTreeSet<String>,
    pub diagnostics: Vec<Diagnostic>,
    pub framework_semantics: BTreeSet<String>,
    pub unsupported_symbols: BTreeSet<String>,
}
