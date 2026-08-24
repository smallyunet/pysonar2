use super::Workspace;
#[cfg(not(target_arch = "wasm32"))]
use std::path::Path;

impl Workspace {
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
