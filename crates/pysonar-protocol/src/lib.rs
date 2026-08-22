use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;

pub const SCHEMA_VERSION: u32 = 1;
pub const CLI_VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SourceLocation {
    pub file: String,
    pub start_line: u32,
    pub start_character: u32,
    pub end_line: u32,
    pub end_character: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub snippet: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct QueryPosition {
    pub file: String,
    pub line: u32,
    pub character: u32,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Coverage {
    pub discovered_files: usize,
    pub parsed_files: usize,
    pub failed_file_count: usize,
    pub failed_files: Vec<String>,
    pub unsupported_node_types: Vec<String>,
    pub detected_framework_semantics: Vec<String>,
    pub unsupported_semantic_symbols: Vec<String>,
}

impl Coverage {
    pub fn status(&self) -> &'static str {
        if self.parsed_files == 0 {
            "empty"
        } else if self.failed_file_count == 0 {
            "complete"
        } else {
            "partial"
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Diagnostic {
    pub file: String,
    pub start_line: u32,
    pub start_character: u32,
    pub end_line: u32,
    pub end_character: u32,
    pub severity: Option<String>,
    pub message: String,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Candidate {
    pub name: String,
    pub qualified_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub inferred_type: Option<String>,
    pub definitions: Vec<SourceLocation>,
    pub references: Vec<SourceLocation>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub affected_files: Option<Vec<String>>,
    pub truncated: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct PlanQuery {
    pub symbol: String,
    pub intent: String,
    pub match_count: usize,
    pub candidates: Vec<Candidate>,
    pub occurrence_kind: String,
    pub returned_occurrence_count: usize,
    pub occurrences: Vec<SourceLocation>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub affected_files: Option<Vec<String>>,
    pub truncated: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RebuildMetrics {
    pub rebuild_mode: String,
    pub changed_files: usize,
    pub affected_files: usize,
    pub analyzed_files: usize,
    pub ast_cache_hits: usize,
    pub ast_cache_misses: usize,
    pub rebuild_reason: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SessionRequest {
    #[serde(default)]
    pub id: Option<String>,
    pub command: String,
    #[serde(default)]
    pub symbol: Option<Value>,
    #[serde(default)]
    pub intent: Option<String>,
    #[serde(default)]
    pub max_results: Option<usize>,
}

pub fn envelope(command: &str) -> BTreeMap<String, Value> {
    BTreeMap::from([
        ("schemaVersion".into(), Value::from(SCHEMA_VERSION)),
        ("cliVersion".into(), Value::from(CLI_VERSION)),
        ("command".into(), Value::from(command)),
    ])
}

pub fn insert<T: Serialize>(map: &mut BTreeMap<String, Value>, key: &str, value: T) {
    map.insert(
        key.to_string(),
        serde_json::to_value(value).expect("protocol values are serializable"),
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn envelope_preserves_schema_one() {
        let value = serde_json::to_value(envelope("doctor")).unwrap();
        assert_eq!(value["schemaVersion"], 1);
        assert_eq!(value["command"], "doctor");
        assert_eq!(value["cliVersion"], "4.0.0");
    }

    #[test]
    fn positions_are_camel_case() {
        let value = serde_json::to_value(SourceLocation {
            file: "app.py".into(),
            start_line: 1,
            start_character: 2,
            end_line: 1,
            end_character: 6,
            snippet: None,
        })
        .unwrap();
        assert_eq!(value["startCharacter"], 2);
        assert!(value.get("start_character").is_none());
    }
}
