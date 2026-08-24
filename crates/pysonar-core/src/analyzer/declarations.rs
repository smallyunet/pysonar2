use crate::model::*;
use crate::util::{infer_assignment_at, infer_returns, resolve_import_module, source_slice};
use ruff_python_ast::visitor::{self, Visitor};
use ruff_python_ast::{Expr, ExprContext, Identifier, Stmt};
use ruff_text_size::{Ranged, TextRange, TextSize};

pub(super) struct DeclarationVisitor<'a, 'b> {
    pub(super) state: &'a mut AnalyzerState<'b>,
    pub(super) file: String,
    pub(super) scope: ScopeId,
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
                let symbol = self.state.define_identifier(
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
                for (index, parameter) in function.parameters.iter().enumerate() {
                    let inferred = parameter
                        .annotation()
                        .map(|annotation| source_slice(source, annotation.range()).to_string())
                        .or_else(|| {
                            (kind == SymbolKind::Method && index == 0).then(|| {
                                if parameter.name().as_str() == "cls" {
                                    format!(
                                        "class {}",
                                        self.state.scopes[self.scope].qualified_name
                                    )
                                } else {
                                    format!(
                                        "instance {}",
                                        self.state.scopes[self.scope].qualified_name
                                    )
                                }
                            })
                        });
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
                if function.returns.is_none() {
                    if let Some(return_type) =
                        self.state
                            .infer_return_type(next, &function.body, &self.file)
                    {
                        let prefix = if function.is_async { "async fn" } else { "fn" };
                        self.state.symbols[symbol].inferred_type =
                            Some(format!("{prefix}({parameters}) -> {return_type}"));
                    }
                }
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
                let bases = class
                    .arguments
                    .as_deref()
                    .into_iter()
                    .flat_map(|arguments| arguments.args.iter())
                    .filter_map(|base| {
                        let id = match base {
                            Expr::Name(name) => {
                                self.state.resolve_name(self.scope, name.id.as_str())
                            }
                            Expr::Attribute(attribute) => self.state.resolve_attribute(
                                self.scope,
                                &attribute.value,
                                attribute.attr.as_str(),
                            ),
                            _ => None,
                        }?;
                        (self.state.symbols[id].kind == SymbolKind::Class)
                            .then(|| self.state.symbols[id].qualified_name.clone())
                    })
                    .collect::<Vec<_>>();
                self.state.class_bases.insert(qname.clone(), bases);
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
            Stmt::Global(global) => {
                let module = self.state.module_scope(self.scope);
                for name in &global.names {
                    if let Some(id) = self.state.scopes[module]
                        .bindings
                        .get(name.as_str())
                        .copied()
                    {
                        self.state.scopes[self.scope]
                            .bindings
                            .insert(name.as_str().to_string(), id);
                    }
                }
            }
            Stmt::Nonlocal(nonlocal) => {
                for name in &nonlocal.names {
                    if let Some(id) = self.state.nonlocal_binding(self.scope, name.as_str()) {
                        self.state.scopes[self.scope]
                            .bindings
                            .insert(name.as_str().to_string(), id);
                    }
                }
            }
            Stmt::Assign(assign) => {
                for target in &assign.targets {
                    self.define_assignment(target, &assign.value);
                }
                self.visit_expr(&assign.value);
            }
            Stmt::AnnAssign(assign) => {
                let annotation =
                    source_slice(&self.state.sources[&self.file], assign.annotation.range())
                        .to_string();
                self.define_assignment_target(&assign.target, Some(annotation));
                self.visit_annotation(&assign.annotation);
                if let Some(value) = &assign.value {
                    self.visit_expr(value);
                }
            }
            Stmt::AugAssign(assign) => {
                let current =
                    self.state
                        .infer_expression_type(self.scope, &assign.target, &self.file);
                let value = self
                    .state
                    .infer_expression_type(self.scope, &assign.value, &self.file);
                let inferred = match (current, value, assign.op) {
                    (Some(left), Some(right), _) if left == right => Some(left),
                    (Some(left), Some(right), _)
                        if (left == "int" && right == "float")
                            || (left == "float" && right == "int") =>
                    {
                        Some("float".to_string())
                    }
                    (left, _, _) => left,
                };
                self.define_assignment_target(&assign.target, inferred);
                self.visit_expr(&assign.value);
            }
            Stmt::For(for_loop) => {
                let iterable =
                    self.state
                        .infer_expression_type(self.scope, &for_loop.iter, &self.file);
                let element = match &*for_loop.iter {
                    Expr::Call(call) if matches!(&*call.func, Expr::Name(name) if name.id.as_str() == "range") => {
                        Some("int".to_string())
                    }
                    _ => iterable
                        .as_deref()
                        .and_then(|value| AnalyzerState::container_element_type(value, None)),
                };
                self.define_assignment_target(&for_loop.target, element);
                self.visit_expr(&for_loop.iter);
                self.visit_body(&for_loop.body);
                self.visit_body(&for_loop.orelse);
            }
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
            Expr::Call(call) => {
                for (parameter, inferred) in self
                    .state
                    .call_parameter_updates(self.scope, call, &self.file)
                {
                    self.state.symbols[parameter].inferred_type = Some(inferred);
                }
                visitor::walk_expr(self, expr);
            }
            Expr::Lambda(lambda) => {
                let qname = format!(
                    "{}.<lambda@{}>",
                    self.state.scopes[self.scope].qualified_name,
                    lambda.range.start().to_u32()
                );
                let next = self.state.new_scope(
                    Some(self.scope),
                    ScopeKind::Function,
                    qname,
                    &self.file,
                    lambda.range.start().to_u32(),
                );
                if let Some(parameters) = lambda.parameters.as_deref() {
                    for parameter in parameters.iter() {
                        self.state.define_identifier(
                            next,
                            parameter.name(),
                            SymbolKind::Parameter,
                            &self.file,
                            None,
                            None,
                        );
                    }
                }
                let previous = self.scope;
                self.scope = next;
                self.visit_expr(&lambda.body);
                self.scope = previous;
            }
            _ => visitor::walk_expr(self, expr),
        }
    }
}

impl DeclarationVisitor<'_, '_> {
    fn define_assignment(&mut self, target: &Expr, value: &Expr) {
        match (target, value) {
            (Expr::Tuple(targets), Expr::Tuple(values)) => {
                for (target, value) in targets.elts.iter().zip(&values.elts) {
                    self.define_assignment(target, value);
                }
            }
            (Expr::List(targets), Expr::List(values)) => {
                for (target, value) in targets.elts.iter().zip(&values.elts) {
                    self.define_assignment(target, value);
                }
            }
            _ => {
                let inferred = self
                    .state
                    .infer_expression_type(self.scope, value, &self.file);
                self.define_assignment_target(target, inferred);
            }
        }
    }

    fn define_assignment_target(&mut self, target: &Expr, inferred_type: Option<String>) {
        match target {
            Expr::Name(name) => {
                self.state.define(
                    self.scope,
                    name.id.as_str(),
                    SymbolKind::Variable,
                    Span {
                        file: self.file.clone(),
                        start: name.range.start().to_u32(),
                        end: name.range.end().to_u32(),
                    },
                    inferred_type,
                    None,
                );
            }
            Expr::Attribute(attribute) => {
                if matches!(&*attribute.value, Expr::Name(name) if name.id.as_str() == "self" || name.id.as_str() == "cls")
                {
                    if let Some(scope) = self.state.nearest_class_scope(self.scope) {
                        self.state.define_identifier(
                            scope,
                            &attribute.attr,
                            SymbolKind::Variable,
                            &self.file,
                            inferred_type,
                            None,
                        );
                    }
                } else if let Some(base) =
                    self.state
                        .infer_expression_type(self.scope, &attribute.value, &self.file)
                {
                    let qname = format!(
                        "{}.{}",
                        base.trim_start_matches("instance "),
                        attribute.attr.as_str()
                    );
                    if let Some(id) = self.state.qnames.get(&qname).copied() {
                        if inferred_type.is_some() {
                            self.state.symbols[id].inferred_type = inferred_type;
                        }
                    } else {
                        let class_name = base.trim_start_matches("instance ");
                        let matches = self
                            .state
                            .scopes
                            .iter()
                            .enumerate()
                            .filter_map(|(id, scope)| {
                                (scope.kind == ScopeKind::Class
                                    && (scope.qualified_name == class_name
                                        || scope
                                            .qualified_name
                                            .ends_with(&format!(".{class_name}"))))
                                .then_some(id)
                            })
                            .collect::<Vec<_>>();
                        if matches.len() == 1 {
                            self.state.define_identifier(
                                matches[0],
                                &attribute.attr,
                                SymbolKind::Variable,
                                &self.file,
                                inferred_type,
                                None,
                            );
                        }
                    }
                }
                self.visit_expr(&attribute.value);
            }
            Expr::Tuple(tuple) => {
                for element in &tuple.elts {
                    self.define_assignment_target(element, None);
                }
            }
            Expr::List(list) => {
                for element in &list.elts {
                    self.define_assignment_target(element, None);
                }
            }
            Expr::Starred(starred) => self.define_assignment_target(&starred.value, inferred_type),
            Expr::Subscript(subscript) => {
                let mut root = &*subscript.value;
                while let Expr::Subscript(inner) = root {
                    root = &inner.value;
                }
                if let (Expr::Name(name), Some(inferred)) = (root, inferred_type) {
                    if let Some(id) = self.state.resolve_name(self.scope, name.id.as_str()) {
                        let current = self.state.symbols[id].inferred_type.as_deref();
                        self.state.symbols[id].inferred_type =
                            Some(if current.is_some_and(|value| value.starts_with("dict")) {
                                format!("dict[Unknown,{inferred}]")
                            } else {
                                format!("list[{inferred}]")
                            });
                    }
                }
                self.visit_expr(target);
            }
            _ => self.visit_expr(target),
        }
    }

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
            let target = if alias.asname.is_some() {
                alias.name.as_str().to_string()
            } else {
                local.to_string()
            };
            let identifier = alias.asname.as_ref().unwrap_or(&alias.name);
            self.state.define(
                self.scope,
                local,
                SymbolKind::Import,
                Span {
                    file: self.file.clone(),
                    start: identifier.range.start().to_u32(),
                    end: identifier.range.end().to_u32(),
                },
                Some(format!("module {}", alias.name.as_str())),
                Some(target),
            );
            if alias.asname.is_none() {
                let mut module = String::new();
                let mut byte_offset = 0u32;
                for component in alias.name.as_str().split('.') {
                    if !module.is_empty() {
                        module.push('.');
                        byte_offset += 1;
                    }
                    module.push_str(component);
                    if let Some(id) = self.state.qnames.get(&module).copied() {
                        let start = alias.name.range.start().to_u32() + byte_offset;
                        let end = start + component.len() as u32;
                        self.state.add_reference(
                            id,
                            &self.file,
                            component,
                            TextRange::new(TextSize::new(start), TextSize::new(end)),
                        );
                    }
                    byte_offset += component.len() as u32;
                }
            }
        }
    }

    fn visit_import_from(&mut self, import: &ruff_python_ast::StmtImportFrom) {
        let module = resolve_import_module(
            &self.state.files[&self.file].module,
            import.module.as_ref().map(Identifier::as_str),
            import.level,
        );
        if let (Some(identifier), Some(id)) = (
            import.module.as_ref(),
            self.state.qnames.get(&module).copied(),
        ) {
            self.state
                .add_reference(id, &self.file, identifier.as_str(), identifier.range);
        }
        if let Some(identifier) = import.module.as_ref() {
            let absolute_parts = module.split('.').collect::<Vec<_>>();
            let source_parts = identifier.as_str().split('.').collect::<Vec<_>>();
            let base = absolute_parts.len().saturating_sub(source_parts.len());
            let mut byte_offset = 0u32;
            for (index, component) in source_parts.iter().enumerate() {
                let qualified = absolute_parts[..=base + index].join(".");
                if let Some(id) = self.state.qnames.get(&qualified).copied() {
                    let start = identifier.range.start().to_u32() + byte_offset;
                    let end = start + component.len() as u32;
                    self.state.add_reference(
                        id,
                        &self.file,
                        component,
                        TextRange::new(TextSize::new(start), TextSize::new(end)),
                    );
                }
                byte_offset += component.len() as u32 + 1;
            }
        }
        for alias in &import.names {
            if alias.name.as_str() == "*" {
                self.state.star_imports.push((self.scope, module.clone()));
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
