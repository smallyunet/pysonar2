use serde_json::Value;
use std::io::Write;
use std::process::{Command, Stdio};

fn binary() -> &'static str {
    env!("CARGO_BIN_EXE_pysonar")
}

#[test]
fn doctor_reports_the_frozen_envelope_and_native_runtime() {
    let output = Command::new(binary())
        .args(["doctor", "--format", "json"])
        .output()
        .expect("run doctor");
    assert!(output.status.success());
    let value: Value = serde_json::from_slice(&output.stdout).expect("doctor JSON");
    assert_eq!(value["schemaVersion"], 1);
    assert_eq!(value["cliVersion"], "4.0.0");
    assert_eq!(value["runtime"], "native-rust");
    assert_eq!(value["python"]["required"], false);
    assert_eq!(value["java"]["required"], false);
}

#[test]
fn session_accepts_the_canonical_protocol_fixture() {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../demo_project");
    let mut child = Command::new(binary())
        .args(["session", "--root"])
        .arg(root)
        .args(["--format", "json"])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .expect("start session");
    child
        .stdin
        .as_mut()
        .expect("session stdin")
        .write_all(include_bytes!("../../../protocol/v1/session.jsonl"))
        .expect("write session requests");
    let output = child.wait_with_output().expect("finish session");
    assert!(output.status.success());
    let responses = String::from_utf8(output.stdout).expect("UTF-8 responses");
    let values = responses
        .lines()
        .map(|line| serde_json::from_str::<Value>(line).expect("response JSON"))
        .collect::<Vec<_>>();
    assert_eq!(
        values.first().and_then(|value| value["command"].as_str()),
        Some("session-ready")
    );
    assert_eq!(
        values.last().and_then(|value| value["command"].as_str()),
        Some("quit")
    );
    assert!(values.iter().all(|value| value["schemaVersion"] == 1));
}
