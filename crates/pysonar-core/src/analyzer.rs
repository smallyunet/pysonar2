use crate::model::*;
use crate::util::*;
use indexmap::IndexMap;
use pysonar_protocol::Diagnostic;
use ruff_python_ast::visitor::{self, Visitor};
use ruff_python_ast::{Expr, ExprContext, Identifier, Stmt};
use ruff_python_parser::parse_module;
use ruff_text_size::{Ranged, TextRange};
use std::collections::{BTreeSet, HashMap, HashSet};

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
    state.rebuild_qnames();
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

impl AnalyzerState<'_> {
    fn new_scope(
        &mut self,
        parent: Option<ScopeId>,
        kind: ScopeKind,
        qualified_name: String,
        file: &str,
        start: u32,
    ) -> ScopeId {
        let id = self.scopes.len();
        self.scopes.push(Scope {
            parent,
            kind,
            qualified_name,
            bindings: IndexMap::new(),
        });
        self.scope_keys.insert((file.to_string(), start), id);
        id
    }

    fn define_identifier(
        &mut self,
        scope: ScopeId,
        identifier: &Identifier,
        kind: SymbolKind,
        file: &str,
        inferred_type: Option<String>,
        alias_target: Option<String>,
    ) -> SymbolId {
        self.define(
            scope,
            identifier.as_str(),
            kind,
            Span {
                file: file.to_string(),
                start: identifier.range.start().to_u32(),
                end: identifier.range.end().to_u32(),
            },
            inferred_type,
            alias_target,
        )
    }

    fn define(
        &mut self,
        scope: ScopeId,
        name: &str,
        kind: SymbolKind,
        definition: Span,
        inferred_type: Option<String>,
        alias_target: Option<String>,
    ) -> SymbolId {
        if let Some(existing) = self.scopes[scope].bindings.get(name).copied() {
            if self.symbols[existing].kind == SymbolKind::Import && kind != SymbolKind::Import {
                self.symbols[existing].kind = kind;
                self.symbols[existing].definition = definition.clone();
                self.symbols[existing].inferred_type = inferred_type;
                self.symbols[existing].alias_target = alias_target;
            }
            self.occurrences.push(Occurrence {
                file: definition.file,
                name: name.to_string(),
                start: definition.start,
                end: definition.end,
                symbol: Some(existing),
            });
            return existing;
        }
        self.define_symbol(scope, name, kind, definition, inferred_type, alias_target)
    }

    fn define_symbol(
        &mut self,
        scope: ScopeId,
        name: &str,
        kind: SymbolKind,
        definition: Span,
        inferred_type: Option<String>,
        alias_target: Option<String>,
    ) -> SymbolId {
        let qualified_name = if self.scopes[scope].qualified_name.is_empty() {
            name.to_string()
        } else {
            format!("{}.{}", self.scopes[scope].qualified_name, name)
        };
        let id = self.symbols.len();
        self.symbols.push(Symbol {
            name: name.to_string(),
            qualified_name: qualified_name.clone(),
            kind,
            definition: definition.clone(),
            inferred_type,
            alias_target,
            references: Vec::new(),
        });
        self.scopes[scope].bindings.insert(name.to_string(), id);
        self.qnames.insert(qualified_name, id);
        if definition.end > definition.start {
            self.occurrences.push(Occurrence {
                file: definition.file,
                name: name.to_string(),
                start: definition.start,
                end: definition.end,
                symbol: Some(id),
            });
        }
        id
    }

    fn rebuild_qnames(&mut self) {
        for (id, symbol) in self.symbols.iter().enumerate() {
            self.qnames.insert(symbol.qualified_name.clone(), id);
        }
    }

    fn link_alias_definitions(&mut self) {
        let aliases = self
            .symbols
            .iter()
            .enumerate()
            .filter_map(|(id, symbol)| symbol.alias_target.as_ref().map(|_| id))
            .collect::<Vec<_>>();
        for alias in aliases {
            let target = self.canonical(alias);
            if target == alias {
                continue;
            }
            let definition = self.symbols[alias].definition.clone();
            if !self.symbols[target].references.iter().any(|reference| {
                reference.file == definition.file
                    && reference.start == definition.start
                    && reference.end == definition.end
            }) {
                self.symbols[target].references.push(definition.clone());
            }
            for occurrence in &mut self.occurrences {
                if occurrence.symbol == Some(alias)
                    && occurrence.file == definition.file
                    && occurrence.start == definition.start
                    && occurrence.end == definition.end
                {
                    occurrence.symbol = Some(target);
                }
            }
        }
    }

    fn canonical(&self, id: SymbolId) -> SymbolId {
        let mut current = id;
        let mut visited = HashSet::new();
        while visited.insert(current) {
            let Some(target) = self.symbols[current].alias_target.as_ref() else {
                break;
            };
            let Some(next) = self.qnames.get(target).copied() else {
                break;
            };
            current = next;
        }
        current
    }

    fn resolve_name(&self, mut scope: ScopeId, name: &str) -> Option<SymbolId> {
        loop {
            if let Some(id) = self.scopes[scope].bindings.get(name).copied() {
                return Some(self.canonical(id));
            }
            scope = self.scopes[scope].parent?;
        }
    }

    fn resolve_attribute(&self, scope: ScopeId, value: &Expr, attr: &str) -> Option<SymbolId> {
        let base = match value {
            Expr::Name(name) => self.resolve_name(scope, name.id.as_str()),
            Expr::Attribute(attribute) => {
                self.resolve_attribute(scope, &attribute.value, attribute.attr.as_str())
            }
            _ => None,
        }?;
        let base = self.canonical(base);
        let symbol = &self.symbols[base];
        let mut candidates = Vec::new();
        if symbol.kind == SymbolKind::Module || symbol.kind == SymbolKind::Class {
            candidates.push(format!("{}.{}", symbol.qualified_name, attr));
        }
        if let Some(target) = &symbol.alias_target {
            candidates.push(format!("{target}.{attr}"));
        }
        if let Some(inferred) = &symbol.inferred_type {
            candidates.push(format!(
                "{}.{}",
                inferred.trim_start_matches("instance "),
                attr
            ));
        }
        candidates
            .iter()
            .find_map(|candidate| self.qnames.get(candidate).copied())
            .map(|id| self.canonical(id))
    }

    fn add_reference(&mut self, id: SymbolId, file: &str, name: &str, range: TextRange) {
        let id = self.canonical(id);
        let span = Span {
            file: file.to_string(),
            start: range.start().to_u32(),
            end: range.end().to_u32(),
        };
        if !self.symbols[id].references.iter().any(|value| {
            value.file == span.file && value.start == span.start && value.end == span.end
        }) {
            self.symbols[id].references.push(span.clone());
        }
        self.occurrences.push(Occurrence {
            file: file.to_string(),
            name: name.to_string(),
            start: span.start,
            end: span.end,
            symbol: Some(id),
        });
    }

    fn nearest_class_scope(&self, mut scope: ScopeId) -> Option<ScopeId> {
        loop {
            if self.scopes[scope].kind == ScopeKind::Class {
                return Some(scope);
            }
            scope = self.scopes[scope].parent?;
        }
    }
}

struct DeclarationVisitor<'a, 'b> {
    state: &'a mut AnalyzerState<'b>,
    file: String,
    scope: ScopeId,
}

impl<'node> Visitor<'node> for DeclarationVisitor<'_, '_> {
    fn visit_stmt(&mut self, stmt: &'node Stmt) {
        match stmt {
            Stmt::FunctionDef(function) => {
                let source = &self.state.sources[&self.file];
                let kind = if self.state.scopes[self.scope].kind == ScopeKind::Class {
                    if function.decorator_list.iter().any(|decorator| {
                        source_slice(source, decorator.range).ends_with("property")
                    }) {
                        SymbolKind::Property
                    } else {
                        SymbolKind::Method
                    }
                } else {
                    SymbolKind::Function
                };
                let return_type = function
                    .returns
                    .as_deref()
                    .map(|annotation| source_slice(source, annotation.range()).to_string())
                    .or_else(|| infer_returns(&function.body, source));
                let parameters = function
                    .parameters
                    .iter()
                    .map(|parameter| parameter.name().as_str())
                    .collect::<Vec<_>>()
                    .join(", ");
                self.state.define_identifier(
                    self.scope,
                    &function.name,
                    kind,
                    &self.file,
                    Some(format!(
                        "{}({}) -> {}",
                        if function.is_async { "async fn" } else { "fn" },
                        parameters,
                        return_type.unwrap_or_else(|| "Unknown".to_string())
                    )),
                    None,
                );
                if function.decorator_list.iter().any(|decorator| {
                    let text = source_slice(source, decorator.range);
                    text == "fixture" || text.ends_with(".fixture")
                }) {
                    self.state
                        .framework_semantics
                        .insert("pytest-fixture-parameter-injection".to_string());
                    self.state
                        .unsupported_symbols
                        .insert(function.name.as_str().to_string());
                }
                let qname = format!(
                    "{}.{}",
                    self.state.scopes[self.scope].qualified_name,
                    function.name.as_str()
                );
                let next = self.state.new_scope(
                    Some(self.scope),
                    ScopeKind::Function,
                    qname,
                    &self.file,
                    function.range.start().to_u32(),
                );
                for parameter in function.parameters.iter() {
                    let inferred = parameter
                        .annotation()
                        .map(|annotation| source_slice(source, annotation.range()).to_string());
                    self.state.define_identifier(
                        next,
                        parameter.name(),
                        SymbolKind::Parameter,
                        &self.file,
                        inferred,
                        None,
                    );
                }
                let previous = self.scope;
                self.scope = next;
                self.visit_body(&function.body);
                self.scope = previous;
            }
            Stmt::ClassDef(class) => {
                self.state.define_identifier(
                    self.scope,
                    &class.name,
                    SymbolKind::Class,
                    &self.file,
                    Some(format!("class {}", class.name.as_str())),
                    None,
                );
                let qname = format!(
                    "{}.{}",
                    self.state.scopes[self.scope].qualified_name,
                    class.name.as_str()
                );
                let next = self.state.new_scope(
                    Some(self.scope),
                    ScopeKind::Class,
                    qname,
                    &self.file,
                    class.range.start().to_u32(),
                );
                let previous = self.scope;
                self.scope = next;
                self.visit_body(&class.body);
                self.scope = previous;
            }
            Stmt::Import(import) => self.visit_import(import),
            Stmt::ImportFrom(import) => self.visit_import_from(import),
            _ => visitor::walk_stmt(self, stmt),
        }
    }

    fn visit_expr(&mut self, expr: &'node Expr) {
        match expr {
            Expr::Name(name) if name.ctx == ExprContext::Store => {
                let source = &self.state.sources[&self.file];
                self.state.define(
                    self.scope,
                    name.id.as_str(),
                    SymbolKind::Variable,
                    Span {
                        file: self.file.clone(),
                        start: name.range.start().to_u32(),
                        end: name.range.end().to_u32(),
                    },
                    infer_assignment_at(source, name.range),
                    None,
                );
            }
            Expr::Attribute(attribute) if attribute.ctx == ExprContext::Store => {
                if matches!(&*attribute.value, Expr::Name(name) if name.id.as_str() == "self" || name.id.as_str() == "cls")
                {
                    if let Some(scope) = self.state.nearest_class_scope(self.scope) {
                        self.state.define_identifier(
                            scope,
                            &attribute.attr,
                            SymbolKind::Variable,
                            &self.file,
                            None,
                            None,
                        );
                    }
                }
                visitor::walk_expr(self, expr);
            }
            _ => visitor::walk_expr(self, expr),
        }
    }
}

impl DeclarationVisitor<'_, '_> {
    fn visit_import(&mut self, import: &ruff_python_ast::StmtImport) {
        for alias in &import.names {
            let local = alias.asname.as_ref().map_or_else(
                || {
                    alias
                        .name
                        .as_str()
                        .split('.')
                        .next()
                        .unwrap_or(alias.name.as_str())
                },
                Identifier::as_str,
            );
            let identifier = alias.asname.as_ref().unwrap_or(&alias.name);
            let target = if alias.asname.is_some() {
                alias.name.as_str().to_string()
            } else {
                local.to_string()
            };
            self.state.define_identifier(
                self.scope,
                identifier,
                SymbolKind::Import,
                &self.file,
                Some(format!("module {}", alias.name.as_str())),
                Some(target),
            );
        }
    }

    fn visit_import_from(&mut self, import: &ruff_python_ast::StmtImportFrom) {
        let module = resolve_import_module(
            &self.state.files[&self.file].module,
            import.module.as_ref().map(Identifier::as_str),
            import.level,
        );
        for alias in &import.names {
            if alias.name.as_str() == "*" {
                continue;
            }
            let identifier = alias.asname.as_ref().unwrap_or(&alias.name);
            self.state.define_identifier(
                self.scope,
                identifier,
                SymbolKind::Import,
                &self.file,
                None,
                Some(
                    format!("{}.{}", module, alias.name.as_str())
                        .trim_start_matches('.')
                        .to_string(),
                ),
            );
        }
    }
}

struct ReferenceVisitor<'a, 'b> {
    state: &'a mut AnalyzerState<'b>,
    file: String,
    scope: ScopeId,
}

impl<'node> Visitor<'node> for ReferenceVisitor<'_, '_> {
    fn visit_stmt(&mut self, stmt: &'node Stmt) {
        match stmt {
            Stmt::FunctionDef(function) => {
                for decorator in &function.decorator_list {
                    self.visit_decorator(decorator);
                }
                if let Some(returns) = &function.returns {
                    self.visit_annotation(returns);
                }
                let previous = self.scope;
                if let Some(scope) = self
                    .state
                    .scope_keys
                    .get(&(self.file.clone(), function.range.start().to_u32()))
                    .copied()
                {
                    self.scope = scope;
                }
                for parameter in function.parameters.iter() {
                    if let Some(annotation) = parameter.annotation() {
                        self.visit_annotation(annotation);
                    }
                }
                self.visit_body(&function.body);
                self.scope = previous;
            }
            Stmt::ClassDef(class) => {
                for decorator in &class.decorator_list {
                    self.visit_decorator(decorator);
                }
                if let Some(arguments) = &class.arguments {
                    self.visit_arguments(arguments);
                }
                let previous = self.scope;
                if let Some(scope) = self
                    .state
                    .scope_keys
                    .get(&(self.file.clone(), class.range.start().to_u32()))
                    .copied()
                {
                    self.scope = scope;
                }
                self.visit_body(&class.body);
                self.scope = previous;
            }
            Stmt::Import(_) | Stmt::ImportFrom(_) => {}
            _ => visitor::walk_stmt(self, stmt),
        }
    }

    fn visit_expr(&mut self, expr: &'node Expr) {
        match expr {
            Expr::Name(name) if name.ctx == ExprContext::Load => {
                if let Some(id) = self.state.resolve_name(self.scope, name.id.as_str()) {
                    self.state
                        .add_reference(id, &self.file, name.id.as_str(), name.range);
                } else {
                    self.state.occurrences.push(Occurrence {
                        file: self.file.clone(),
                        name: name.id.as_str().to_string(),
                        start: name.range.start().to_u32(),
                        end: name.range.end().to_u32(),
                        symbol: None,
                    });
                }
            }
            Expr::Attribute(attribute) if attribute.ctx == ExprContext::Load => {
                self.visit_expr(&attribute.value);
                if let Some(id) = self.state.resolve_attribute(
                    self.scope,
                    &attribute.value,
                    attribute.attr.as_str(),
                ) {
                    self.state.add_reference(
                        id,
                        &self.file,
                        attribute.attr.as_str(),
                        attribute.attr.range,
                    );
                } else {
                    self.state.occurrences.push(Occurrence {
                        file: self.file.clone(),
                        name: attribute.attr.as_str().to_string(),
                        start: attribute.attr.range.start().to_u32(),
                        end: attribute.attr.range.end().to_u32(),
                        symbol: None,
                    });
                }
            }
            Expr::Name(_) | Expr::Attribute(_) => {}
            _ => visitor::walk_expr(self, expr),
        }
    }
}
