use ruff_python_ast::{Expr, Stmt};
use ruff_text_size::TextRange;
use sha2::{Digest, Sha256};

pub(crate) fn normalize_path(path: &str) -> String {
    path.replace('\\', "/").trim_start_matches("./").to_string()
}

pub(crate) fn module_name(path: &str) -> String {
    let mut value = normalize_path(path);
    if value.ends_with("/__init__.py") {
        value.truncate(value.len() - "/__init__.py".len());
    } else if value == "__init__.py" {
        value.clear();
    } else if value.ends_with(".py") {
        value.truncate(value.len() - 3);
    }
    value.replace('/', ".")
}

pub(crate) fn resolve_import_module(current: &str, module: Option<&str>, level: u32) -> String {
    if level == 0 {
        return module.unwrap_or_default().to_string();
    }
    let mut parts: Vec<_> = current.split('.').collect();
    if !parts.is_empty() {
        parts.pop();
    }
    for _ in 1..level {
        parts.pop();
    }
    if let Some(module) = module {
        parts.extend(module.split('.'));
    }
    parts.join(".")
}

pub(crate) fn content_hash(source: &str) -> String {
    format!("{:x}", Sha256::digest(source.as_bytes()))
}

pub(crate) fn source_slice(source: &str, range: TextRange) -> &str {
    source
        .get(range.start().to_usize()..range.end().to_usize())
        .unwrap_or_default()
}

pub(crate) fn position(source: &str, offset: u32) -> (u32, u32) {
    let mut offset = (offset as usize).min(source.len());
    while offset > 0 && !source.is_char_boundary(offset) {
        offset -= 1;
    }
    let prefix = &source[..offset];
    let line = prefix.bytes().filter(|byte| *byte == b'\n').count() as u32 + 1;
    let line_start = prefix.rfind('\n').map_or(0, |index| index + 1);
    let character = prefix[line_start..].encode_utf16().count() as u32 + 1;
    (line, character)
}

pub(crate) fn byte_offset(source: &str, line: u32, character: u32) -> Option<u32> {
    if line == 0 || character == 0 {
        return None;
    }
    let mut line_start = 0usize;
    for _ in 1..line {
        line_start += source[line_start..].find('\n')? + 1;
    }
    let line_end = source[line_start..]
        .find('\n')
        .map(|offset| line_start + offset)
        .unwrap_or(source.len());
    let target = character - 1;
    let mut utf16 = 0u32;
    for (byte, ch) in source[line_start..line_end].char_indices() {
        if utf16 >= target {
            return Some((line_start + byte) as u32);
        }
        utf16 += ch.len_utf16() as u32;
    }
    (utf16 == target).then_some(line_end as u32)
}

pub(crate) fn infer_returns(body: &[Stmt], source: &str) -> Option<String> {
    for stmt in body {
        match stmt {
            Stmt::Return(value) => {
                return value
                    .value
                    .as_deref()
                    .and_then(|expr| infer_expr(expr, source));
            }
            Stmt::If(value) => {
                if let Some(found) = infer_returns(&value.body, source) {
                    return Some(found);
                }
            }
            _ => {}
        }
    }
    None
}

pub(crate) fn infer_expr(expr: &Expr, source: &str) -> Option<String> {
    Some(match expr {
        Expr::StringLiteral(_) | Expr::FString(_) | Expr::TString(_) => "str".to_string(),
        Expr::BytesLiteral(_) => "bytes".to_string(),
        Expr::BooleanLiteral(_) => "bool".to_string(),
        Expr::NoneLiteral(_) => "None".to_string(),
        Expr::NumberLiteral(number) => {
            let text = source_slice(source, number.range);
            if text.contains('.') { "float" } else { "int" }.to_string()
        }
        Expr::List(_) | Expr::ListComp(_) => "list".to_string(),
        Expr::Set(_) | Expr::SetComp(_) => "set".to_string(),
        Expr::Dict(_) | Expr::DictComp(_) => "dict".to_string(),
        Expr::Tuple(_) => "tuple".to_string(),
        Expr::Generator(_) => "generator".to_string(),
        Expr::Lambda(_) => "callable".to_string(),
        Expr::Call(call) => match &*call.func {
            Expr::Name(name) => format!("instance {}", name.id.as_str()),
            Expr::Attribute(attribute) => format!("instance {}", attribute.attr.as_str()),
            _ => "Unknown".to_string(),
        },
        Expr::Await(awaited) => {
            infer_expr(&awaited.value, source).unwrap_or_else(|| "Unknown".to_string())
        }
        _ => return None,
    })
}

pub(crate) fn infer_assignment_at(source: &str, range: TextRange) -> Option<String> {
    let line_end = source[range.end().to_usize()..]
        .find('\n')
        .map(|offset| range.end().to_usize() + offset)
        .unwrap_or(source.len());
    let suffix = &source[range.end().to_usize()..line_end];
    let value = suffix.split_once('=')?.1.trim();
    if value.starts_with(['\'', '"']) {
        Some("str".to_string())
    } else if value == "True" || value == "False" {
        Some("bool".to_string())
    } else if value.parse::<i64>().is_ok() {
        Some("int".to_string())
    } else if value.parse::<f64>().is_ok() {
        Some("float".to_string())
    } else if value.starts_with('[') {
        Some("list".to_string())
    } else if value.starts_with('{') {
        Some("dict".to_string())
    } else {
        None
    }
}
