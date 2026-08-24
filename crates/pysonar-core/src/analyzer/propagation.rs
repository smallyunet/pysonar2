use crate::model::*;
use ruff_python_ast::visitor::{self, Visitor};
use ruff_python_ast::{Expr, Stmt};

pub(super) struct CallPropagationVisitor<'a, 'b> {
    pub(super) state: &'a mut AnalyzerState<'b>,
    pub(super) file: String,
    pub(super) scope: ScopeId,
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

pub(super) struct ReturnTypeVisitor<'a, 'b> {
    pub(super) state: &'a mut AnalyzerState<'b>,
    pub(super) file: String,
    pub(super) scope: ScopeId,
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
