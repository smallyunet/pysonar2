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
    load_failures: IndexMap<String, String>,
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
        let path = normalize_path(&path.into());
        self.load_failures.shift_remove(&path);
        self.files.insert(path, source.into());
    }

    pub fn remove_file(&mut self, path: &str) -> bool {
        let path = normalize_path(path);
        self.load_failures.shift_remove(&path);
        self.files.shift_remove(&path).is_some()
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
        let mut snapshot = analyze(&self.files);
        for (path, message) in &self.load_failures {
            snapshot.failed_files.insert(path.clone());
            snapshot.diagnostics.push(Diagnostic {
                file: path.clone(),
                start_line: 1,
                start_character: 1,
                end_line: 1,
                end_character: 1,
                severity: Some("Error".to_string()),
                message: message.clone(),
            });
        }
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
            .map(|value| {
                std::iter::once(&value.definition)
                    .chain(&value.additional_definitions)
                    .map(|definition| snapshot.location(definition))
                    .collect::<Vec<_>>()
            })
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
            inferred_type: occurrence
                .and_then(|value| value.inferred_type.clone())
                .or_else(|| symbol.and_then(|value| value.inferred_type.clone())),
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
                    .is_some_and(|extension| extension == "py" || extension == "pyi")
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
            let (source, load_failure) = read_python_source(&path)?;
            workspace.set_file(&relative, source);
            if let Some(message) = load_failure {
                workspace.load_failures.insert(relative, message);
            }
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
                let mut definitions = std::iter::once(&symbol.definition)
                    .chain(&symbol.additional_definitions)
                    .map(|definition| self.location(definition))
                    .collect::<Vec<_>>();
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
            Some(".git" | ".venv" | "node_modules" | "target" | "build" | "dist" | "__pycache__")
        )
    }) || (path.is_dir() && path.join("pyvenv.cfg").is_file())
}

#[cfg(not(target_arch = "wasm32"))]
fn read_python_source(path: &Path) -> std::io::Result<(String, Option<String>)> {
    let bytes = std::fs::read(path)?;
    match String::from_utf8(bytes) {
        Ok(source) => Ok((source, None)),
        Err(error) => {
            let bytes = error.into_bytes();
            let Some(label) = python_encoding_label(&bytes) else {
                let message = format!(
                    "{} is not UTF-8 and has no Python encoding declaration",
                    path.display()
                );
                return Ok((String::from_utf8_lossy(&bytes).into_owned(), Some(message)));
            };
            if matches!(
                label.as_str(),
                "latin-1" | "latin1" | "iso-8859-1" | "iso-latin-1"
            ) {
                return Ok((bytes.iter().map(|byte| char::from(*byte)).collect(), None));
            }
            let Some(encoding) = encoding_rs::Encoding::for_label(label.as_bytes()) else {
                let message = format!(
                    "{} declares unsupported Python encoding {label}",
                    path.display()
                );
                return Ok((String::from_utf8_lossy(&bytes).into_owned(), Some(message)));
            };
            let (source, had_errors) = encoding.decode_without_bom_handling(&bytes);
            if had_errors {
                let message = format!("{} contains invalid {label} source bytes", path.display());
                Ok((source.into_owned(), Some(message)))
            } else {
                Ok((source.into_owned(), None))
            }
        }
    }
}

#[cfg(not(target_arch = "wasm32"))]
fn python_encoding_label(bytes: &[u8]) -> Option<String> {
    let header_end = bytes
        .iter()
        .enumerate()
        .filter(|(_, byte)| **byte == b'\n')
        .nth(1)
        .map_or(bytes.len(), |(index, _)| index);
    let header = String::from_utf8_lossy(&bytes[..header_end]);
    for line in header.lines().take(2) {
        let Some(coding) = line.find("coding") else {
            continue;
        };
        let suffix = line[coding + "coding".len()..].trim_start();
        let Some(suffix) = suffix
            .strip_prefix(':')
            .or_else(|| suffix.strip_prefix('='))
        else {
            continue;
        };
        let label = suffix
            .trim_start()
            .chars()
            .take_while(|character| {
                character.is_ascii_alphanumeric() || matches!(character, '-' | '_' | '.')
            })
            .collect::<String>();
        if !label.is_empty() {
            return Some(label.to_ascii_lowercase());
        }
    }
    None
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
    fn propagates_call_container_field_and_inherited_types() {
        let mut workspace = Workspace::new("/project");
        workspace.set_file(
            "main.py",
            concat!(
                "def label():\n    return 'ok'\n\n",
                "class Box:\n    def __init__(self):\n        self.answer = 41\n\n",
                "numbers = [1, 2]\nfirst = numbers[0]\nresult = label()\n",
                "answer = Box().answer\n\nclass Child(Box):\n    pass\n",
                "inherited = Child().answer\n",
            ),
        );
        workspace.analyze();
        assert_eq!(
            workspace
                .context("main.py", 9, 9, 20)
                .inferred_type
                .as_deref(),
            Some("int")
        );
        assert_eq!(
            workspace
                .context("main.py", 10, 1, 20)
                .inferred_type
                .as_deref(),
            Some("str")
        );
        assert_eq!(
            workspace
                .context("main.py", 11, 16, 20)
                .inferred_type
                .as_deref(),
            Some("int")
        );
        assert_eq!(
            workspace
                .context("main.py", 15, 21, 20)
                .inferred_type
                .as_deref(),
            Some("int")
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

    #[test]
    fn loads_declared_source_encodings_stubs_and_real_venv_packages() {
        let temp = tempfile::tempdir().expect("tempdir");
        std::fs::write(
            temp.path().join("latin.py"),
            b"# coding: latin-1\nname = 'caf\xe9'\n",
        )
        .expect("latin source");
        std::fs::write(temp.path().join("models.pyi"), b"class User: ...\n").expect("stub source");
        std::fs::create_dir(temp.path().join("venv")).expect("venv package");
        std::fs::write(temp.path().join("venv/__init__.py"), b"ACTIVE = True\n")
            .expect("venv package source");

        let mut workspace = Workspace::from_root(temp.path()).expect("load encoded workspace");
        let summary = workspace.analyze();
        assert_eq!(summary.file_count, 3);
        assert_eq!(summary.parsed_files, 3);
        assert!(summary.failed_files.is_empty());
    }

    #[test]
    fn excludes_actual_virtual_environments_by_marker() {
        let temp = tempfile::tempdir().expect("tempdir");
        std::fs::create_dir(temp.path().join("environment")).expect("environment");
        std::fs::write(
            temp.path().join("environment/pyvenv.cfg"),
            b"home = /python\n",
        )
        .expect("venv marker");
        std::fs::write(
            temp.path().join("environment/ignored.py"),
            b"ignored = True\n",
        )
        .expect("ignored source");
        std::fs::write(temp.path().join("app.py"), b"active = True\n").expect("app source");

        let workspace = Workspace::from_root(temp.path()).expect("load workspace");
        assert_eq!(workspace.paths().collect::<Vec<_>>(), vec!["app.py"]);
    }
}
