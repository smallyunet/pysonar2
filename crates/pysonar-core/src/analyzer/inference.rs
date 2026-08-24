use crate::model::*;
use crate::util::{infer_expr, source_slice};
use ruff_python_ast::{Expr, Operator, Stmt, UnaryOp};

impl AnalyzerState<'_> {
    pub(super) fn callable_return_type(&self, id: SymbolId) -> Option<String> {
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

    pub(super) fn infer_expression_type(
        &self,
        scope: ScopeId,
        expr: &Expr,
        file: &str,
    ) -> Option<String> {
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

    pub(super) fn merge_types<'a>(values: impl Iterator<Item = &'a str>) -> Option<String> {
        let mut values = values;
        let first = values.next()?.to_string();
        values.all(|value| value == first).then_some(first)
    }

    pub(super) fn infer_collection_type(
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

    pub(super) fn container_element_type(container: &str, index: Option<usize>) -> Option<String> {
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

    pub(super) fn infer_return_type(
        &self,
        scope: ScopeId,
        body: &[Stmt],
        file: &str,
    ) -> Option<String> {
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

    pub(super) fn call_parameter_updates(
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
}
