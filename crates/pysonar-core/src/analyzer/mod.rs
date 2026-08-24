use crate::model::*;
use crate::util::{module_name, position};
use declarations::DeclarationVisitor;
use indexmap::IndexMap;
use propagation::{CallPropagationVisitor, ReturnTypeVisitor};
use pysonar_protocol::Diagnostic;
use references::ReferenceVisitor;
use ruff_python_ast::visitor::Visitor;
use ruff_python_parser::parse_module;
use std::collections::{BTreeSet, HashMap};

mod declarations;
mod inference;
mod propagation;
mod references;
mod state;

pub(crate) fn analyze(sources: &IndexMap<String, String>) -> Snapshot {
    let mut state = AnalyzerState {
        sources,
        files: IndexMap::new(),
        parsed: HashMap::new(),
        symbols: Vec::new(),
        scopes: Vec::new(),
        module_scopes: HashMap::new(),
        scope_keys: HashMap::new(),
        qnames: HashMap::new(),
        occurrences: Vec::new(),
        failed_files: BTreeSet::new(),
        diagnostics: Vec::new(),
        framework_semantics: BTreeSet::new(),
        unsupported_symbols: BTreeSet::new(),
        star_imports: Vec::new(),
        class_bases: HashMap::new(),
        simple_class_members: HashMap::new(),
    };

    for (path, source) in sources {
        state.files.insert(
            path.clone(),
            FileInfo {
                source: source.clone(),
                module: module_name(path),
            },
        );
        match parse_module(source) {
            Ok(parsed) => {
                state.parsed.insert(path.clone(), parsed.into_syntax());
            }
            Err(error) => {
                state.failed_files.insert(path.clone());
                let (start_line, start_character) =
                    position(source, error.location.start().to_u32());
                let (end_line, end_character) = position(source, error.location.end().to_u32());
                state.diagnostics.push(Diagnostic {
                    file: path.clone(),
                    start_line,
                    start_character,
                    end_line,
                    end_character,
                    severity: Some("Error".to_string()),
                    message: error.to_string(),
                });
            }
        }
    }

    let paths: Vec<_> = state.parsed.keys().cloned().collect();
    for path in &paths {
        let module = state.files[path].module.clone();
        let scope = state.new_scope(None, ScopeKind::Module, module.clone(), path, 0);
        state.module_scopes.insert(path.clone(), scope);
        let id = state.define_symbol(
            scope,
            module.rsplit('.').next().unwrap_or(&module),
            SymbolKind::Module,
            Span {
                file: path.clone(),
                start: 0,
                end: 0,
            },
            Some(format!("module {module}")),
            None,
        );
        state.symbols[id].qualified_name = module.clone();
        state.qnames.insert(module, id);
    }

    for path in &paths {
        let module = state.parsed[path].clone();
        let scope = state.module_scopes[path];
        DeclarationVisitor {
            state: &mut state,
            file: path.clone(),
            scope,
        }
        .visit_body(&module.body);
    }
    for path in &paths {
        let module = state.parsed[path].clone();
        let scope = state.module_scopes[path];
        CallPropagationVisitor {
            state: &mut state,
            file: path.clone(),
            scope,
        }
        .visit_body(&module.body);
    }
    for path in &paths {
        let module = state.parsed[path].clone();
        let scope = state.module_scopes[path];
        ReturnTypeVisitor {
            state: &mut state,
            file: path.clone(),
            scope,
        }
        .visit_body(&module.body);
    }
    state.rebuild_qnames();
    state.link_star_imports();
    state.link_alias_definitions();

    for path in &paths {
        let module = state.parsed[path].clone();
        let scope = state.module_scopes[path];
        ReferenceVisitor {
            state: &mut state,
            file: path.clone(),
            scope,
        }
        .visit_body(&module.body);
    }

    Snapshot {
        files: state.files,
        symbols: state.symbols,
        occurrences: state.occurrences,
        failed_files: state.failed_files,
        diagnostics: state.diagnostics,
        framework_semantics: state.framework_semantics,
        unsupported_symbols: state.unsupported_symbols,
    }
}
