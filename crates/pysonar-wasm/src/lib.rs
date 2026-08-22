use pysonar_core::Workspace;
use wasm_bindgen::prelude::*;

const MAX_FILES: usize = 512;
const MAX_SOURCE_BYTES: usize = 12 * 1024 * 1024;

#[wasm_bindgen]
pub struct PySonar {
    workspace: Workspace,
    source_bytes: usize,
}

#[wasm_bindgen]
impl PySonar {
    #[wasm_bindgen(constructor)]
    pub fn new(root: Option<String>) -> Self {
        console_error_panic_hook::set_once();
        Self {
            workspace: Workspace::new(root.unwrap_or_else(|| "browser".to_string())),
            source_bytes: 0,
        }
    }

    #[wasm_bindgen(js_name = setFile)]
    pub fn set_file(&mut self, path: String, source: String) -> Result<(), JsValue> {
        let previous = self.workspace.source(&path).map_or(0, str::len);
        let next_total = self.source_bytes.saturating_sub(previous) + source.len();
        if self.workspace.source(&path).is_none() && self.workspace.file_count() >= MAX_FILES {
            return Err(JsValue::from_str(
                "PySonar2 browser workspace file limit exceeded",
            ));
        }
        if next_total > MAX_SOURCE_BYTES {
            return Err(JsValue::from_str(
                "PySonar2 browser workspace source limit exceeded",
            ));
        }
        self.workspace.set_file(path, source);
        self.source_bytes = next_total;
        Ok(())
    }

    #[wasm_bindgen(js_name = removeFile)]
    pub fn remove_file(&mut self, path: String) -> bool {
        let previous = self.workspace.source(&path).map_or(0, str::len);
        let removed = self.workspace.remove_file(&path);
        if removed {
            self.source_bytes = self.source_bytes.saturating_sub(previous);
        }
        removed
    }

    pub fn analyze(&mut self) -> Result<String, JsValue> {
        json(&self.workspace.analyze())
    }

    pub fn context(
        &mut self,
        file: String,
        line: u32,
        character: u32,
        max_results: Option<usize>,
    ) -> Result<String, JsValue> {
        json(&self.workspace.context(
            &file,
            line,
            character,
            max_results.unwrap_or(50).clamp(1, 2_000),
        ))
    }

    pub fn impact(
        &mut self,
        file: String,
        line: u32,
        character: u32,
        max_results: Option<usize>,
    ) -> Result<String, JsValue> {
        json(&self.workspace.impact(
            &file,
            line,
            character,
            max_results.unwrap_or(50).clamp(1, 2_000),
        ))
    }

    pub fn plan(
        &mut self,
        symbols_json: String,
        intent: Option<String>,
        max_results: Option<usize>,
    ) -> Result<String, JsValue> {
        let symbols: Vec<String> = serde_json::from_str(&symbols_json)
            .map_err(|error| JsValue::from_str(&format!("invalid symbols JSON: {error}")))?;
        let intent = intent.unwrap_or_else(|| "inspect".to_string());
        if !matches!(intent.as_str(), "inspect" | "change") {
            return Err(JsValue::from_str("intent must be inspect or change"));
        }
        json(&self.workspace.plan(
            &symbols,
            &intent,
            max_results.unwrap_or(8).clamp(1, 2_000),
            true,
        ))
    }

    pub fn check(&mut self, changed_json: Option<String>) -> Result<String, JsValue> {
        let changed: Vec<String> = changed_json
            .map(|value| serde_json::from_str(&value))
            .transpose()
            .map_err(|error| JsValue::from_str(&format!("invalid changed-files JSON: {error}")))?
            .unwrap_or_default();
        json(&self.workspace.check(&changed))
    }

    #[wasm_bindgen(getter, js_name = fileCount)]
    pub fn file_count(&self) -> usize {
        self.workspace.file_count()
    }

    #[wasm_bindgen(getter, js_name = sourceBytes)]
    pub fn source_bytes(&self) -> usize {
        self.source_bytes
    }
}

#[wasm_bindgen]
pub fn version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

fn json(value: &impl serde::Serialize) -> Result<String, JsValue> {
    serde_json::to_string(value)
        .map_err(|error| JsValue::from_str(&format!("serialize analysis result: {error}")))
}
