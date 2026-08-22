use crate::analyzer::analyze;
use crate::model::*;
use crate::util::{byte_offset, content_hash, normalize_path, position};
use indexmap::IndexMap;
use pysonar_protocol::{Candidate, Coverage, Diagnostic, PlanQuery, QueryPosition, SourceLocation};
use std::collections::{BTreeSet, HashMap};
#[cfg(not(target_arch = "wasm32"))]
use std::path::Path;

#[derive(Clone, Debug, Default)]
pub struct Workspace {
    root: String,
    files: IndexMap<String, String>,
    hashes: HashMap<String, String>,
    snapshot: Option<Snapshot>,
}

impl Workspace {
    pub fn new(root: impl Into<String>) -> Self {
        Self {
            root: root.into(),
            ..Self::default()
        }
    }

    pub fn root(&self) -> &str {
        &self.root
    }

    pub fn set_file(&mut self, path: impl Into<String>, source: impl Into<String>) {
        self.files
            .insert(normalize_path(&path.into()), source.into());
    }

    pub fn remove_file(&mut self, path: &str) -> bool {
        self.files.shift_remove(&normalize_path(path)).is_some()
    }

    pub fn file_count(&self) -> usize {
        self.files.len()
    }

    pub fn source(&self, path: &str) -> Option<&str> {
        self.files.get(&normalize_path(path)).map(String::as_str)
    }

    pub fn paths(&self) -> impl Iterator<Item = &str> {
        self.files.keys().map(String::as_str)
    }

    pub fn analyze(&mut self) -> AnalysisSummary {
        let snapshot = analyze(&self.files);
        self.hashes = self
            .files
            .iter()
            .map(|(path, source)| (path.clone(), content_hash(source)))
            .collect();
        let summary = snapshot.summary(&self.root);
        self.snapshot = Some(snapshot);
        summary
    }

    pub fn refresh(&mut self) -> (AnalysisSummary, RefreshKind, usize) {
        let changed = self
            .files
            .iter()
            .filter(|(path, source)| self.hashes.get(*path) != Some(&content_hash(source)))
            .count()
            + self
                .hashes
                .keys()
                .filter(|path| !self.files.contains_key(*path))
                .count();
        if changed == 0 && self.snapshot.is_some() {
            return (
                self.snapshot.as_ref().unwrap().summary(&self.root),
                RefreshKind::NoChange,
                0,
            );
        }
        (self.analyze(), RefreshKind::Full, changed)
    }

    fn ensure_snapshot(&mut self) -> &Snapshot {
        if self.snapshot.is_none() {
            self.analyze();
        }
        self.snapshot.as_ref().expect("analysis snapshot")
    }

    pub fn context(
        &mut self,
        file: &str,
        line: u32,
        character: u32,
        max_results: usize,
    ) -> ContextResult {
        self.query(file, line, character, max_results, false)
    }

    pub fn impact(
        &mut self,
        file: &str,
        line: u32,
        character: u32,
        max_results: usize,
    ) -> ContextResult {
        self.query(file, line, character, max_results, true)
    }

    fn query(
        &mut self,
        file: &str,
        line: u32,
        character: u32,
        max_results: usize,
        impact: bool,
    ) -> ContextResult {
        let path = normalize_path(file);
        let source = self.files.get(&path).cloned().unwrap_or_default();
        let offset = byte_offset(&source, line, character);
        let snapshot = self.ensure_snapshot();
        let coverage = snapshot.coverage();
        let occurrence = offset.and_then(|offset| snapshot.occurrence_at(&path, offset));
        let symbol_id = occurrence.and_then(|value| value.symbol);
        let symbol = symbol_id.map(|id| &snapshot.symbols[id]);
        let unsupported: Vec<String> = symbol
            .filter(|value| snapshot.unsupported_symbols.contains(&value.name))
            .map(|_| snapshot.framework_semantics.iter().cloned().collect())
            .unwrap_or_default();
        let locally_applicable = occurrence.is_some()
            && !snapshot.failed_files.contains(&path)
            && unsupported.is_empty();
        let coverage_complete = coverage.status() == "complete";
        let applicable = locally_applicable && (!impact || coverage_complete);
        let mut definitions = symbol
            .map(|value| vec![snapshot.location(&value.definition)])
            .unwrap_or_default();
        let mut references = symbol
            .map(|value| {
                value
                    .references
                    .iter()
                    .map(|reference| snapshot.location(reference))
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        let truncated = definitions.len() > max_results || references.len() > max_results;
        definitions.truncate(max_results);
        references.truncate(max_results);
        let affected_files = impact.then(|| {
            definitions
                .iter()
                .chain(&references)
                .map(|location| location.file.clone())
                .collect::<BTreeSet<_>>()
                .into_iter()
                .collect()
        });
        let mut limitations = vec![if impact {
            "This is a definition/reference impact surface, not a complete runtime call graph."
                .to_string()
        } else {
            "Results describe the saved workspace state and may be incomplete for dynamic Python behavior."
                .to_string()
        }];
        if !snapshot.failed_files.is_empty() {
            limitations.push(format!(
                "{} workspace file(s) failed to parse; project-wide references may be incomplete.",
                snapshot.failed_files.len()
            ));
        }
        if !unsupported.is_empty() {
            limitations.push(format!(
                "Unsupported dynamic framework semantics were detected: {}; injected references may be omitted.",
                unsupported.join(", ")
            ));
        }
        if !applicable {
            limitations.push(if impact {
                "Do not use this result as a complete change-impact boundary.".to_string()
            } else {
                "The query position did not resolve to an analyzed semantic occurrence.".to_string()
            });
        }
        ContextResult {
            query: QueryPosition {
                file: path,
                line,
                character,
            },
            symbol: occurrence.map(|value| value.name.clone()),
            inferred_type: symbol.and_then(|value| value.inferred_type.clone()),
            definitions,
            references,
            truncated,
            coverage_status: coverage.status().to_string(),
            applicable,
            confidence: if !locally_applicable {
                "unsupported"
            } else if coverage_complete {
                "high"
            } else {
                "partial"
            }
            .to_string(),
            unsupported_semantics: unsupported,
            coverage,
            limitations,
            impact_kind: impact.then(|| "reference-based".to_string()),
            affected_files,
        }
    }

    pub fn plan(
        &mut self,
        symbols: &[String],
        intent: &str,
        max_results: usize,
        compact: bool,
    ) -> PlanResult {
        let snapshot = self.ensure_snapshot();
        PlanResult {
            queries: symbols
                .iter()
                .map(|name| snapshot.plan(name, intent, max_results, compact))
                .collect(),
        }
    }

    pub fn check(&mut self, changed: &[String]) -> CheckResult {
        let normalized: BTreeSet<_> = changed.iter().map(|path| normalize_path(path)).collect();
        let snapshot = self.ensure_snapshot();
        let diagnostics: Vec<_> = snapshot
            .diagnostics
            .iter()
            .filter(|diagnostic| normalized.is_empty() || normalized.contains(&diagnostic.file))
            .cloned()
            .collect();
        CheckResult {
            changed: normalized.into_iter().collect(),
            diagnostic_count: diagnostics.len(),
            diagnostics,
            limitations: vec![
                "Diagnostics are conservative semantic findings, not a replacement for tests, lint, or Pyright."
                    .to_string(),
            ],
        }
    }

    pub fn symbols(&mut self, query: Option<&str>, max_results: usize) -> Vec<WorkspaceSymbol> {
        let snapshot = self.ensure_snapshot();
        snapshot
            .symbols
            .iter()
            .filter(|symbol| query.is_none_or(|query| symbol.name.contains(query)))
            .take(max_results)
            .map(|symbol| WorkspaceSymbol {
                name: symbol.name.clone(),
                qualified_name: symbol.qualified_name.clone(),
                kind: symbol.kind.as_str().to_string(),
                location: snapshot.location(&symbol.definition),
                inferred_type: symbol.inferred_type.clone(),
            })
            .collect()
    }

    pub fn diagnostics(&mut self) -> Vec<Diagnostic> {
        self.ensure_snapshot().diagnostics.clone()
    }

    #[cfg(not(target_arch = "wasm32"))]
    pub fn from_root(root: &Path) -> std::io::Result<Self> {
        let canonical = root.canonicalize()?;
        let mut workspace = Workspace::new(canonical.to_string_lossy());
        let mut paths = Vec::new();
        for entry in walkdir::WalkDir::new(&canonical)
            .into_iter()
            .filter_entry(|entry| !is_excluded(entry.path()))
        {
            let entry = entry?;
            if entry.file_type().is_file()
                && entry
                    .path()
                    .extension()
                    .is_some_and(|extension| extension == "py")
            {
                paths.push(entry.path().to_path_buf());
            }
        }
        paths.sort();
        for path in paths {
            let relative = path
                .strip_prefix(&canonical)
                .unwrap_or(&path)
                .to_string_lossy()
                .replace('\\', "/");
            workspace.set_file(relative, std::fs::read_to_string(path)?);
        }
        Ok(workspace)
    }
}

impl Snapshot {
    fn summary(&self, root: &str) -> AnalysisSummary {
        let coverage = self.coverage();
        AnalysisSummary {
            root: root.to_string(),
            file_count: self.files.len(),
            parsed_files: coverage.parsed_files,
            failed_files: coverage.failed_files.clone(),
            symbol_count: self.symbols.len(),
            reference_count: self
                .symbols
                .iter()
                .map(|symbol| symbol.references.len())
                .sum(),
            coverage_status: coverage.status().to_string(),
        }
    }

    fn coverage(&self) -> Coverage {
        Coverage {
            discovered_files: self.files.len(),
            parsed_files: self.files.len().saturating_sub(self.failed_files.len()),
            failed_file_count: self.failed_files.len(),
            failed_files: self.failed_files.iter().cloned().collect(),
            unsupported_node_types: Vec::new(),
            detected_framework_semantics: self.framework_semantics.iter().cloned().collect(),
            unsupported_semantic_symbols: self.unsupported_symbols.iter().cloned().collect(),
        }
    }

    fn occurrence_at(&self, file: &str, offset: u32) -> Option<&Occurrence> {
        self.occurrences
            .iter()
            .filter(|occurrence| {
                occurrence.file == file && occurrence.start <= offset && offset <= occurrence.end
            })
            .min_by_key(|occurrence| occurrence.end.saturating_sub(occurrence.start))
    }

    fn location(&self, span: &Span) -> SourceLocation {
        let info = &self.files[&span.file];
        let (start_line, start_character) = position(&info.source, span.start);
        let (end_line, end_character) = position(&info.source, span.end);
        SourceLocation {
            file: span.file.clone(),
            start_line,
            start_character,
            end_line,
            end_character,
            snippet: info
                .source
                .lines()
                .nth(start_line.saturating_sub(1) as usize)
                .map(|line| line.trim().to_string()),
        }
    }

    fn plan(&self, name: &str, intent: &str, max_results: usize, compact: bool) -> PlanQuery {
        let matching: Vec<_> = self
            .symbols
            .iter()
            .filter(|symbol| symbol.name == name && symbol.alias_target.is_none())
            .collect();
        let occurrences: Vec<_> = self
            .occurrences
            .iter()
            .filter(|occurrence| occurrence.name == name)
            .collect();
        let candidates = matching
            .iter()
            .take(max_results)
            .map(|symbol| {
                let mut definitions = vec![self.location(&symbol.definition)];
                let mut references: Vec<_> = symbol
                    .references
                    .iter()
                    .map(|reference| self.location(reference))
                    .collect();
                let truncated = definitions.len() > max_results || references.len() > max_results;
                definitions.truncate(max_results);
                references.truncate(max_results);
                let affected_files = (intent == "change").then(|| {
                    definitions
                        .iter()
                        .chain(&references)
                        .map(|location| location.file.clone())
                        .collect::<BTreeSet<_>>()
                        .into_iter()
                        .collect()
                });
                Candidate {
                    name: symbol.name.clone(),
                    qualified_name: symbol.qualified_name.clone(),
                    kind: (!compact).then(|| symbol.kind.as_str().to_string()),
                    inferred_type: (!compact).then(|| symbol.inferred_type.clone()).flatten(),
                    definitions,
                    references,
                    affected_files,
                    truncated,
                }
            })
            .collect();
        let limited: Vec<_> = occurrences
            .iter()
            .take(max_results)
            .map(|occurrence| self.location(&occurrence.span()))
            .collect();
        PlanQuery {
            symbol: name.to_string(),
            intent: intent.to_string(),
            match_count: matching.len(),
            candidates,
            occurrence_kind: "exact-identifier-text".to_string(),
            returned_occurrence_count: limited.len(),
            affected_files: (intent == "change").then(|| {
                limited
                    .iter()
                    .map(|location| location.file.clone())
                    .collect::<BTreeSet<_>>()
                    .into_iter()
                    .collect()
            }),
            occurrences: limited,
            truncated: matching.len() > max_results || occurrences.len() > max_results,
        }
    }
}

#[cfg(not(target_arch = "wasm32"))]
fn is_excluded(path: &Path) -> bool {
    path.components().any(|component| {
        matches!(
            component.as_os_str().to_str(),
            Some(
                ".git"
                    | ".venv"
                    | "venv"
                    | "node_modules"
                    | "target"
                    | "build"
                    | "dist"
                    | "__pycache__"
            )
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn workspace() -> Workspace {
        let mut workspace = Workspace::new("/project");
        workspace.set_file("models.py", "class User:\n    pass\n");
        workspace.set_file(
            "app.py",
            "from models import User\n\ndef load():\n    return User()\n\ncurrent = load()\n",
        );
        workspace
    }

    #[test]
    fn resolves_cross_file_import_references() {
        let mut workspace = workspace();
        assert_eq!(workspace.analyze().coverage_status, "complete");
        let result = workspace.context("app.py", 4, 12, 50);
        assert_eq!(result.symbol.as_deref(), Some("User"));
        assert!(result.applicable);
        assert_eq!(result.definitions[0].file, "models.py");
        assert!(
            result
                .references
                .iter()
                .any(|reference| reference.file == "app.py")
        );
    }

    #[test]
    fn partial_coverage_fails_closed_for_impact() {
        let mut workspace = workspace();
        workspace.set_file("broken.py", "def broken(:\n");
        workspace.analyze();
        assert_eq!(workspace.context("app.py", 4, 12, 50).confidence, "partial");
        assert!(!workspace.impact("app.py", 4, 12, 50).applicable);
    }

    #[test]
    fn plan_batches_symbols_and_occurrences() {
        let mut workspace = workspace();
        workspace.analyze();
        let plan = workspace.plan(&["User".into(), "load".into()], "change", 8, true);
        assert_eq!(plan.queries.len(), 2);
        assert!(plan.queries[0].match_count >= 1);
        assert!(plan.queries[0].returned_occurrence_count >= 2);
    }

    #[test]
    fn utf16_positions_round_trip() {
        let source = "value = '🐍'\n结果 = value\n";
        for offset in [0, 8, 14, source.len() as u32] {
            if source.is_char_boundary(offset as usize) {
                let (line, character) = position(source, offset);
                assert_eq!(byte_offset(source, line, character), Some(offset));
            }
        }
    }

    #[test]
    fn parses_the_preserved_legacy_python_corpus() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../tests");
        let mut workspace = Workspace::from_root(&root).expect("load legacy corpus");
        let summary = workspace.analyze();
        assert!(
            summary.file_count >= 25,
            "expected preserved legacy fixtures"
        );
        assert_eq!(summary.failed_files, Vec::<String>::new());
        assert_eq!(summary.coverage_status, "complete");
    }

    #[test]
    fn parses_python_314_template_strings() {
        let mut workspace = Workspace::new("browser");
        workspace.set_file(
            "modern.py",
            "name = 'PySonar2'\nmessage = t'hello {name}'\n",
        );
        let summary = workspace.analyze();
        assert_eq!(summary.failed_files, Vec::<String>::new());
    }
}
