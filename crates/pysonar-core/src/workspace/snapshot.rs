use crate::model::*;
use crate::util::position;
use pysonar_protocol::{Candidate, Coverage, PlanQuery, SourceLocation};
use std::collections::BTreeSet;

impl Snapshot {
    pub(super) fn summary(&self, root: &str) -> AnalysisSummary {
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

    pub(super) fn coverage(&self) -> Coverage {
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

    pub(super) fn occurrence_at(&self, file: &str, offset: u32) -> Option<&Occurrence> {
        self.occurrences
            .iter()
            .filter(|occurrence| {
                occurrence.file == file && occurrence.start <= offset && offset <= occurrence.end
            })
            .min_by_key(|occurrence| occurrence.end.saturating_sub(occurrence.start))
    }

    pub(super) fn location(&self, span: &Span) -> SourceLocation {
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

    pub(super) fn plan(
        &self,
        name: &str,
        intent: &str,
        max_results: usize,
        compact: bool,
    ) -> PlanQuery {
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
