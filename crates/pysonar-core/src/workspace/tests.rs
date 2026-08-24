use super::*;
use crate::util::{byte_offset, position};
use std::path::Path;

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
