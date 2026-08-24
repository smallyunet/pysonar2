use anyhow::Result;
use args::{Cli, Command};
use clap::Parser;
use commands::{analyze, check, generate_demo, plan, query, session};
use output::{UsageError, is_command, print_help, require_format, write_error, write_json};
use pysonar_protocol::{CLI_VERSION, envelope, insert};
use serde_json::json;
use std::path::Path;

mod args;
mod commands;
mod output;

const CAPABILITIES: &[&str] = &[
    "workspace-summary",
    "symbol-plan",
    "persistent-session",
    "context",
    "reference-impact",
    "diagnostics",
    "native-rust",
    "webassembly",
];

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if matches!(args.get(1).map(String::as_str), Some("--version" | "-V")) {
        println!("{CLI_VERSION}");
        return;
    }
    if args.len() == 3 && !is_command(&args[1]) {
        if let Err(error) = generate_demo(Path::new(&args[1]), Path::new(&args[2])) {
            write_error(&error.to_string(), 1);
            std::process::exit(1);
        }
        return;
    }
    match run(Cli::parse()) {
        Ok(()) => {}
        Err(error) => {
            let exit_code = error
                .downcast_ref::<UsageError>()
                .map_or(1, |error| error.0);
            write_error(&error.to_string(), exit_code);
            std::process::exit(exit_code);
        }
    }
}

fn run(cli: Cli) -> Result<()> {
    match cli.command {
        Some(Command::Version) => println!("{CLI_VERSION}"),
        Some(Command::Doctor { format }) => {
            require_format(&format, &["json"])?;
            let mut result = envelope("doctor");
            insert(&mut result, "rustVersion", "1.88.0");
            insert(&mut result, "runtime", "native-rust");
            insert(
                &mut result,
                "python",
                json!({"required": false, "used": false}),
            );
            insert(
                &mut result,
                "java",
                json!({"required": false, "used": false}),
            );
            insert(&mut result, "status", "ok");
            insert(&mut result, "capabilities", CAPABILITIES);
            write_json(&result)?;
        }
        Some(Command::Analyze(args)) => analyze(args)?,
        Some(Command::Plan(args)) => plan(args)?,
        Some(Command::Session(args)) => session(args)?,
        Some(Command::Context(args)) => query(args, false)?,
        Some(Command::Impact(args)) => query(args, true)?,
        Some(Command::Check(args)) => check(args)?,
        Some(Command::Demo { source, output }) => generate_demo(&source, &output)?,
        None => print_help(),
    }
    Ok(())
}
