use crate::model::*;
use crate::util::*;
use indexmap::IndexMap;
use pysonar_protocol::Diagnostic;
use ruff_python_ast::visitor::{self, Visitor};
use ruff_python_ast::{Expr, ExprContext, Identifier, Operator, Stmt, UnaryOp};
use ruff_python_parser::parse_module;
use ruff_text_size::{Ranged, TextRange, TextSize};
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
            let local_qname = format!("{}.{}", self.scopes[scope].qualified_name, name);
            if self.scopes[scope].kind == ScopeKind::Function
                && self.symbols[existing].qualified_name != local_qname
            {
                self.symbols[existing].references.push(definition.clone());
                self.occurrences.push(Occurrence {
                    file: definition.file,
                    name: name.to_string(),
                    start: definition.start,
                    end: definition.end,
                    symbol: Some(existing),
                    inferred_type: None,
                });
                return existing;
            }
            if self.symbols[existing].kind == SymbolKind::Import && kind != SymbolKind::Import {
                self.symbols[existing].kind = kind;
                self.symbols[existing].definition = definition.clone();
                self.symbols[existing].additional_definitions.clear();
                self.symbols[existing].inferred_type = inferred_type.clone();
                self.symbols[existing].alias_target = alias_target;
            } else if self.symbols[existing].definition.start != definition.start
                || self.symbols[existing].definition.file != definition.file
            {
                self.symbols[existing]
                    .additional_definitions
                    .push(definition.clone());
            }
            if inferred_type.is_some() {
                self.symbols[existing].inferred_type = inferred_type;
            }
            self.occurrences.push(Occurrence {
                file: definition.file,
                name: name.to_string(),
                start: definition.start,
                end: definition.end,
                symbol: Some(existing),
                inferred_type: None,
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
            additional_definitions: Vec::new(),
            inferred_type,
            alias_target,
            references: Vec::new(),
        });
        if self.scopes[scope].kind == ScopeKind::Class {
            let class = self.scopes[scope]
                .qualified_name
                .rsplit('.')
                .next()
                .unwrap_or_default()
                .to_string();
            let key = (class, name.to_string());
            match self.simple_class_members.get(&key).copied() {
                None => {
                    self.simple_class_members.insert(key, Some(id));
                }
                Some(Some(existing)) if existing != id => {
                    self.simple_class_members.insert(key, None);
                }
                _ => {}
            }
        }
        self.scopes[scope].bindings.insert(name.to_string(), id);
        self.qnames.insert(qualified_name, id);
        if definition.end > definition.start {
            self.occurrences.push(Occurrence {
                file: definition.file,
                name: name.to_string(),
                start: definition.start,
                end: definition.end,
                symbol: Some(id),
                inferred_type: None,
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

    fn link_star_imports(&mut self) {
        for (scope, module) in self.star_imports.clone() {
            let prefix = format!("{module}.");
            let exports = self
                .symbols
                .iter()
                .enumerate()
                .filter_map(|(id, symbol)| {
                    let remainder = symbol.qualified_name.strip_prefix(&prefix)?;
                    (!remainder.contains('.') && !symbol.name.starts_with('_'))
                        .then(|| (symbol.name.clone(), id))
                })
                .collect::<Vec<_>>();
            for (name, id) in exports {
                self.scopes[scope].bindings.entry(name).or_insert(id);
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
        if matches!(value, Expr::Name(name) if name.id.as_str() == "self" || name.id.as_str() == "cls")
        {
            if let Some(class_scope) = self.nearest_class_scope(scope) {
                if let Some(id) =
                    self.resolve_class_member(&self.scopes[class_scope].qualified_name, attr)
                {
                    return Some(id);
                }
            }
        }
        if let Expr::Call(call) = value {
            let callable = match &*call.func {
                Expr::Name(name) => self.resolve_name(scope, name.id.as_str()),
                Expr::Attribute(attribute) => {
                    self.resolve_attribute(scope, &attribute.value, attribute.attr.as_str())
                }
                _ => None,
            };
            if let Some(base_type) = callable.and_then(|id| self.callable_return_type(id)) {
                if let Some(id) =
                    self.resolve_class_member(base_type.trim_start_matches("instance "), attr)
                {
                    return Some(id);
                }
            }
        }
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
        candidates.iter().find_map(|candidate| {
            let (base, member) = candidate.rsplit_once('.')?;
            self.resolve_class_member(base, member).or_else(|| {
                self.qnames
                    .get(candidate)
                    .copied()
                    .map(|id| self.canonical(id))
            })
        })
    }

    fn resolve_class_member(&self, class: &str, attr: &str) -> Option<SymbolId> {
        self.resolve_class_member_inner(class, attr, &mut HashSet::new())
    }

    fn resolve_class_member_inner(
        &self,
        class: &str,
        attr: &str,
        visited: &mut HashSet<String>,
    ) -> Option<SymbolId> {
        if !visited.insert(class.to_string()) {
            return None;
        }
        let candidate = format!("{class}.{attr}");
        if let Some(id) = self.qnames.get(&candidate).copied() {
            return Some(self.canonical(id));
        }
        if !class.contains('.') {
            if let Some(Some(id)) = self
                .simple_class_members
                .get(&(class.to_string(), attr.to_string()))
            {
                return Some(self.canonical(*id));
            }
        }
        self.class_bases
            .get(class)?
            .iter()
            .find_map(|base| self.resolve_class_member_inner(base, attr, visited))
    }

    fn callable_return_type(&self, id: SymbolId) -> Option<String> {
        let symbol = &self.symbols[self.canonical(id)];
        match symbol.kind {
            SymbolKind::Class => Some(format!("instance {}", symbol.qualified_name)),
            SymbolKind::Function | SymbolKind::Method | SymbolKind::Property => {
                symbol.inferred_type.as_deref().and_then(|value| {
                    value
                        .rsplit_once(" -> ")
                        .map(|(_, result)| result.to_string())
                })
            }
            _ => None,
        }
    }

    fn infer_expression_type(&self, scope: ScopeId, expr: &Expr, file: &str) -> Option<String> {
        let source = &self.sources[file];
        match expr {
            Expr::List(list) => Some(self.infer_collection_type(scope, &list.elts, file, "list")),
            Expr::Set(set) => Some(self.infer_collection_type(scope, &set.elts, file, "set")),
            Expr::Tuple(tuple) => {
                let elements = tuple
                    .elts
                    .iter()
                    .map(|value| {
                        self.infer_expression_type(scope, value, file)
                            .unwrap_or_else(|| "Unknown".to_string())
                    })
                    .collect::<Vec<_>>();
                Some(format!("tuple[{}]", elements.join(",")))
            }
            Expr::Dict(dict) => {
                let pairs = dict
                    .items
                    .iter()
                    .filter_map(|item| {
                        let key = item.key.as_ref()?;
                        Some((
                            self.infer_expression_type(scope, key, file)?,
                            self.infer_expression_type(scope, &item.value, file)?,
                        ))
                    })
                    .collect::<Vec<_>>();
                let keys = Self::merge_types(pairs.iter().map(|(key, _)| key.as_str()));
                let values = Self::merge_types(pairs.iter().map(|(_, value)| value.as_str()));
                match (keys, values) {
                    (Some(keys), Some(values)) => Some(format!("dict[{keys},{values}]")),
                    _ => Some("dict".to_string()),
                }
            }
            Expr::Name(name) => self
                .resolve_name(scope, name.id.as_str())
                .and_then(|id| self.symbols[self.canonical(id)].inferred_type.clone())
                .or_else(|| infer_expr(expr, source)),
            Expr::Attribute(attribute) => self
                .resolve_attribute(scope, &attribute.value, attribute.attr.as_str())
                .and_then(|id| self.symbols[self.canonical(id)].inferred_type.clone()),
            Expr::Call(call) => {
                let target = match &*call.func {
                    Expr::Name(name) => self.resolve_name(scope, name.id.as_str()),
                    Expr::Attribute(attribute) => {
                        self.resolve_attribute(scope, &attribute.value, attribute.attr.as_str())
                    }
                    _ => None,
                };
                if let Some(result) = target.and_then(|id| self.callable_return_type(id)) {
                    return Some(result);
                }
                if let Expr::Name(name) = &*call.func {
                    if matches!(
                        name.id.as_str(),
                        "str"
                            | "int"
                            | "float"
                            | "bool"
                            | "bytes"
                            | "list"
                            | "dict"
                            | "set"
                            | "tuple"
                    ) {
                        return Some(name.id.as_str().to_string());
                    }
                }
                if let Some(callable) = self.infer_expression_type(scope, &call.func, file) {
                    if let Some((_, result)) = callable.rsplit_once(" -> ") {
                        return Some(result.to_string());
                    }
                }
                infer_expr(expr, source)
            }
            Expr::BinOp(binary) => {
                let left = self.infer_expression_type(scope, &binary.left, file);
                let right = self.infer_expression_type(scope, &binary.right, file);
                match (left.as_deref(), right.as_deref(), binary.op) {
                    (Some("float"), Some("int" | "float"), _) | (Some("int"), Some("float"), _) => {
                        Some("float".to_string())
                    }
                    (Some(left), Some(right), _) if left == right => Some(left.to_string()),
                    (Some("str"), Some(_), Operator::Mod) => Some("str".to_string()),
                    _ => None,
                }
            }
            Expr::BoolOp(boolean) => boolean
                .values
                .iter()
                .filter_map(|value| self.infer_expression_type(scope, value, file))
                .reduce(|left, right| {
                    if left == right {
                        left
                    } else {
                        "Unknown".into()
                    }
                }),
            Expr::If(conditional) => {
                let body = self.infer_expression_type(scope, &conditional.body, file);
                let orelse = self.infer_expression_type(scope, &conditional.orelse, file);
                match (body, orelse) {
                    (Some(left), Some(right)) if left == right => Some(left),
                    (Some(_), Some(_)) => Some("Unknown".to_string()),
                    (left, right) => left.or(right),
                }
            }
            Expr::Compare(_) => Some("bool".to_string()),
            Expr::UnaryOp(unary) => {
                if unary.op == UnaryOp::Not {
                    Some("bool".to_string())
                } else {
                    self.infer_expression_type(scope, &unary.operand, file)
                }
            }
            Expr::Await(awaited) => self.infer_expression_type(scope, &awaited.value, file),
            Expr::Named(named) => self.infer_expression_type(scope, &named.value, file),
            Expr::Subscript(subscript) => {
                let container = self.infer_expression_type(scope, &subscript.value, file)?;
                let index = match &*subscript.slice {
                    Expr::NumberLiteral(number) => {
                        source_slice(source, number.range).parse::<usize>().ok()
                    }
                    _ => None,
                };
                Self::container_element_type(&container, index)
            }
            _ => infer_expr(expr, source),
        }
    }

    fn merge_types<'a>(values: impl Iterator<Item = &'a str>) -> Option<String> {
        let mut values = values;
        let first = values.next()?.to_string();
        values.all(|value| value == first).then_some(first)
    }

    fn infer_collection_type(
        &self,
        scope: ScopeId,
        elements: &[Expr],
        file: &str,
        collection: &str,
    ) -> String {
        let types = elements
            .iter()
            .filter_map(|value| self.infer_expression_type(scope, value, file))
            .collect::<Vec<_>>();
        if let Some(element) = Self::merge_types(types.iter().map(String::as_str)) {
            format!("{collection}[{element}]")
        } else if collection == "list" && !types.is_empty() {
            format!("list-elements[{}]", types.join(";"))
        } else {
            collection.to_string()
        }
    }

    fn container_element_type(container: &str, index: Option<usize>) -> Option<String> {
        let (kind, contents) = container.split_once('[')?;
        let contents = contents.strip_suffix(']')?;
        if kind == "list-elements" {
            let parts = contents.split(';').collect::<Vec<_>>();
            return match index {
                Some(index) => parts.get(index).map(|value| (*value).to_string()),
                None => Self::merge_types(parts.iter().copied()),
            };
        }
        let mut parts = Vec::new();
        let mut depth = 0usize;
        let mut start = 0usize;
        for (offset, byte) in contents.bytes().enumerate() {
            match byte {
                b'[' => depth += 1,
                b']' => depth = depth.saturating_sub(1),
                b',' if depth == 0 => {
                    parts.push(&contents[start..offset]);
                    start = offset + 1;
                }
                _ => {}
            }
        }
        parts.push(&contents[start..]);
        match kind {
            "list" | "set" => parts.first().map(|value| (*value).to_string()),
            "dict" => parts.get(1).map(|value| (*value).to_string()),
            "tuple" => match index {
                Some(index) => parts.get(index).map(|value| (*value).to_string()),
                None => Self::merge_types(parts.iter().copied()),
            },
            _ => None,
        }
    }

    fn infer_return_type(&self, scope: ScopeId, body: &[Stmt], file: &str) -> Option<String> {
        for statement in body {
            match statement {
                Stmt::Return(value) => {
                    return value
                        .value
                        .as_deref()
                        .and_then(|expr| self.infer_expression_type(scope, expr, file));
                }
                Stmt::If(value) => {
                    if let Some(inferred) = self.infer_return_type(scope, &value.body, file) {
                        return Some(inferred);
                    }
                    if let Some(inferred) = self.infer_return_type(
                        scope,
                        &value
                            .elif_else_clauses
                            .iter()
                            .flat_map(|clause| clause.body.iter().cloned())
                            .collect::<Vec<_>>(),
                        file,
                    ) {
                        return Some(inferred);
                    }
                }
                Stmt::For(value) => {
                    if let Some(inferred) = self.infer_return_type(scope, &value.body, file) {
                        return Some(inferred);
                    }
                }
                Stmt::While(value) => {
                    if let Some(inferred) = self.infer_return_type(scope, &value.body, file) {
                        return Some(inferred);
                    }
                }
                _ => {}
            }
        }
        None
    }

    fn call_parameter_updates(
        &self,
        caller_scope: ScopeId,
        call: &ruff_python_ast::ExprCall,
        file: &str,
    ) -> Vec<(SymbolId, String)> {
        let target = match &*call.func {
            Expr::Name(name) => self.resolve_name(caller_scope, name.id.as_str()),
            Expr::Attribute(attribute) => {
                self.resolve_attribute(caller_scope, &attribute.value, attribute.attr.as_str())
            }
            _ => None,
        };
        let Some(target) = target.map(|id| self.canonical(id)) else {
            return Vec::new();
        };
        if !matches!(
            self.symbols[target].kind,
            SymbolKind::Function | SymbolKind::Method
        ) {
            return Vec::new();
        }
        let Some(function_scope) = self
            .scopes
            .iter()
            .position(|scope| scope.qualified_name == self.symbols[target].qualified_name)
        else {
            return Vec::new();
        };
        let parameters = self.scopes[function_scope]
            .bindings
            .iter()
            .filter_map(|(name, id)| {
                (self.symbols[*id].kind == SymbolKind::Parameter).then_some((name.as_str(), *id))
            })
            .collect::<Vec<_>>();
        let implicit = usize::from(
            self.symbols[target].kind == SymbolKind::Method
                && parameters
                    .first()
                    .is_some_and(|(name, _)| matches!(*name, "self" | "cls")),
        );
        parameters
            .into_iter()
            .enumerate()
            .skip(implicit)
            .filter_map(|(index, (name, id))| {
                let value = call.arguments.find_argument_value(name, index - implicit)?;
                self.infer_expression_type(caller_scope, value, file)
                    .map(|inferred| (id, inferred))
            })
            .collect()
    }

    fn add_reference(&mut self, id: SymbolId, file: &str, name: &str, range: TextRange) {
        self.add_reference_with_type(id, file, name, range, None);
    }

    fn add_reference_with_type(
        &mut self,
        id: SymbolId,
        file: &str,
        name: &str,
        range: TextRange,
        inferred_type: Option<String>,
    ) {
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
            inferred_type,
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

    fn module_scope(&self, mut scope: ScopeId) -> ScopeId {
        while let Some(parent) = self.scopes[scope].parent {
            scope = parent;
        }
        scope
    }

    fn nonlocal_binding(&self, mut scope: ScopeId, name: &str) -> Option<SymbolId> {
        scope = self.scopes[scope].parent?;
        loop {
            if self.scopes[scope].kind == ScopeKind::Function {
                if let Some(id) = self.scopes[scope].bindings.get(name) {
                    return Some(*id);
                }
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

struct CallPropagationVisitor<'a, 'b> {
    state: &'a mut AnalyzerState<'b>,
    file: String,
    scope: ScopeId,
}

impl<'node> Visitor<'node> for CallPropagationVisitor<'_, '_> {
    fn visit_stmt(&mut self, stmt: &'node Stmt) {
        match stmt {
            Stmt::FunctionDef(function) => {
                let previous = self.scope;
                if let Some(scope) = self
                    .state
                    .scope_keys
                    .get(&(self.file.clone(), function.range.start().to_u32()))
                    .copied()
                {
                    self.scope = scope;
                }
                self.visit_body(&function.body);
                self.scope = previous;
            }
            Stmt::ClassDef(class) => {
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
            _ => visitor::walk_stmt(self, stmt),
        }
    }

    fn visit_expr(&mut self, expr: &'node Expr) {
        if let Expr::Call(call) = expr {
            for (parameter, inferred) in self
                .state
                .call_parameter_updates(self.scope, call, &self.file)
            {
                self.state.symbols[parameter].inferred_type = Some(inferred);
            }
        }
        visitor::walk_expr(self, expr);
    }
}

struct ReturnTypeVisitor<'a, 'b> {
    state: &'a mut AnalyzerState<'b>,
    file: String,
    scope: ScopeId,
}

impl<'node> Visitor<'node> for ReturnTypeVisitor<'_, '_> {
    fn visit_stmt(&mut self, stmt: &'node Stmt) {
        match stmt {
            Stmt::FunctionDef(function) => {
                let previous = self.scope;
                if let Some(scope) = self
                    .state
                    .scope_keys
                    .get(&(self.file.clone(), function.range.start().to_u32()))
                    .copied()
                {
                    self.scope = scope;
                }
                if function.returns.is_none() {
                    let inferred =
                        self.state
                            .infer_return_type(self.scope, &function.body, &self.file);
                    if let (Some(inferred), Some(symbol)) = (
                        inferred,
                        self.state.scopes[previous]
                            .bindings
                            .get(function.name.as_str())
                            .copied(),
                    ) {
                        if let Some(signature) = self.state.symbols[symbol].inferred_type.as_mut() {
                            if let Some((prefix, _)) = signature.rsplit_once(" -> ") {
                                *signature = format!("{prefix} -> {inferred}");
                            }
                        }
                    }
                }
                self.visit_body(&function.body);
                self.scope = previous;
            }
            Stmt::ClassDef(class) => {
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
            _ => visitor::walk_stmt(self, stmt),
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
                        inferred_type: None,
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
                        inferred_type: None,
                    });
                }
            }
            Expr::Attribute(attribute) if attribute.ctx == ExprContext::Store => {
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
                }
            }
            Expr::Subscript(subscript) => {
                let mut root = &*subscript.value;
                while let Expr::Subscript(inner) = root {
                    root = &inner.value;
                }
                if let Expr::Name(name) = root {
                    if let Some(id) = self.state.resolve_name(self.scope, name.id.as_str()) {
                        let inferred = self
                            .state
                            .infer_expression_type(self.scope, expr, &self.file);
                        self.state.add_reference_with_type(
                            id,
                            &self.file,
                            name.id.as_str(),
                            name.range,
                            inferred,
                        );
                    } else {
                        self.visit_expr(root);
                    }
                } else {
                    self.visit_expr(&subscript.value);
                }
                self.visit_expr(&subscript.slice);
            }
            Expr::Lambda(lambda) => {
                let previous = self.scope;
                if let Some(scope) = self
                    .state
                    .scope_keys
                    .get(&(self.file.clone(), lambda.range.start().to_u32()))
                    .copied()
                {
                    self.scope = scope;
                }
                self.visit_expr(&lambda.body);
                self.scope = previous;
            }
            Expr::Name(_) | Expr::Attribute(_) => {}
            _ => visitor::walk_expr(self, expr),
        }
    }
}
