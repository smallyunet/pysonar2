use crate::model::*;
use indexmap::IndexMap;
use ruff_python_ast::{Expr, Identifier};
use ruff_text_size::TextRange;
use std::collections::HashSet;

impl AnalyzerState<'_> {
    pub(super) fn new_scope(
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

    pub(super) fn define_identifier(
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

    pub(super) fn define(
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

    pub(super) fn define_symbol(
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

    pub(super) fn rebuild_qnames(&mut self) {
        for (id, symbol) in self.symbols.iter().enumerate() {
            self.qnames.insert(symbol.qualified_name.clone(), id);
        }
    }

    pub(super) fn link_alias_definitions(&mut self) {
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

    pub(super) fn link_star_imports(&mut self) {
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

    pub(super) fn canonical(&self, id: SymbolId) -> SymbolId {
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

    pub(super) fn resolve_name(&self, mut scope: ScopeId, name: &str) -> Option<SymbolId> {
        loop {
            if let Some(id) = self.scopes[scope].bindings.get(name).copied() {
                return Some(self.canonical(id));
            }
            scope = self.scopes[scope].parent?;
        }
    }

    pub(super) fn resolve_attribute(
        &self,
        scope: ScopeId,
        value: &Expr,
        attr: &str,
    ) -> Option<SymbolId> {
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

    pub(super) fn resolve_class_member(&self, class: &str, attr: &str) -> Option<SymbolId> {
        self.resolve_class_member_inner(class, attr, &mut HashSet::new())
    }

    pub(super) fn resolve_class_member_inner(
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

    pub(super) fn add_reference(&mut self, id: SymbolId, file: &str, name: &str, range: TextRange) {
        self.add_reference_with_type(id, file, name, range, None);
    }

    pub(super) fn add_reference_with_type(
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

    pub(super) fn nearest_class_scope(&self, mut scope: ScopeId) -> Option<ScopeId> {
        loop {
            if self.scopes[scope].kind == ScopeKind::Class {
                return Some(scope);
            }
            scope = self.scopes[scope].parent?;
        }
    }

    pub(super) fn module_scope(&self, mut scope: ScopeId) -> ScopeId {
        while let Some(parent) = self.scopes[scope].parent {
            scope = parent;
        }
        scope
    }

    pub(super) fn nonlocal_binding(&self, mut scope: ScopeId, name: &str) -> Option<SymbolId> {
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
