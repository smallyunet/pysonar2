use super::Workspace;
use crate::model::*;
use crate::util::{byte_offset, normalize_path};
use pysonar_protocol::{Diagnostic, QueryPosition};
use std::collections::BTreeSet;

impl Workspace {
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
}
