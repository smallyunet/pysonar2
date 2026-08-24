use crate::analyzer::analyze;
use crate::model::*;
use crate::util::{content_hash, normalize_path};
use indexmap::IndexMap;
use pysonar_protocol::Diagnostic;
use std::collections::HashMap;

mod loader;
mod query;
mod snapshot;
#[cfg(test)]
mod tests;

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
}
