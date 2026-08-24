use crate::output::{canonical_root, escape, html_id};
use anyhow::Result;
use pysonar_core::Workspace;
use std::fs;
use std::path::Path;

pub(crate) fn generate_demo(source: &Path, output: &Path) -> Result<()> {
    let root = canonical_root(source)?;
    let mut workspace = Workspace::from_root(&root)?;
    let summary = workspace.analyze();
    fs::create_dir_all(output)?;
    let files = workspace.paths().map(str::to_string).collect::<Vec<_>>();
    let mut navigation = String::new();
    let mut sections = String::new();
    for file in &files {
        navigation.push_str(&format!(
            "<a href=\"#{}\">{}</a>",
            html_id(file),
            escape(file)
        ));
        sections.push_str(&format!(
            "<section id=\"{}\"><h2>{}</h2><pre><code>{}</code></pre></section>",
            html_id(file),
            escape(file),
            escape(workspace.source(file).unwrap_or_default())
        ));
    }
    let html = format!(
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>PySonar2 analysis</title><style>{}</style></head><body><aside><strong>PySonar2 v4</strong><p>{} files · {} symbols</p>{}</aside><main>{}</main></body></html>",
        DEMO_CSS, summary.file_count, summary.symbol_count, navigation, sections
    );
    fs::write(output.join("index.html"), html)?;
    println!(
        "Wrote {} source pages and an index to {}",
        files.len(),
        output.display()
    );
    Ok(())
}

const DEMO_CSS: &str = "*{box-sizing:border-box}body{margin:0;background:#0b1020;color:#dce5f4;font:15px ui-monospace,SFMono-Regular,Menlo,monospace;display:grid;grid-template-columns:260px 1fr;min-height:100vh}aside{position:sticky;top:0;height:100vh;padding:24px;border-right:1px solid #24304a;background:#11182b}aside a{display:block;color:#8bd5ca;padding:7px 0;text-decoration:none;overflow-wrap:anywhere}main{padding:32px;min-width:0}section{margin:0 auto 28px;max-width:1100px}h2{font:600 15px ui-sans-serif,system-ui;color:#f4f7fb}pre{overflow:auto;padding:22px;border:1px solid #26334e;border-radius:12px;background:#0e1526;line-height:1.6}@media(max-width:760px){body{display:block}aside{position:relative;height:auto;border-right:0;border-bottom:1px solid #24304a}main{padding:18px}}";
