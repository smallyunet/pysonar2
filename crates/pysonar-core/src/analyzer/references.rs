use crate::model::*;
use ruff_python_ast::visitor::{self, Visitor};
use ruff_python_ast::{Expr, ExprContext, Stmt};

pub(super) struct ReferenceVisitor<'a, 'b> {
    pub(super) state: &'a mut AnalyzerState<'b>,
    pub(super) file: String,
    pub(super) scope: ScopeId,
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
