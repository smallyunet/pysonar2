use pysonar_core::Workspace;
use pysonar_protocol::{Diagnostic as CoreDiagnostic, SourceLocation};
use std::path::PathBuf;
use tokio::sync::RwLock;
use tower_lsp::jsonrpc::Result;
use tower_lsp::lsp_types::*;
use tower_lsp::{Client, LanguageServer, LspService, Server};

struct Backend {
    client: Client,
    root: PathBuf,
    workspace: RwLock<Workspace>,
}

impl Backend {
    fn new(client: Client) -> Self {
        let root = std::env::current_dir()
            .ok()
            .and_then(|path| path.canonicalize().ok())
            .unwrap_or_else(|| PathBuf::from("."));
        let mut workspace =
            Workspace::from_root(&root).unwrap_or_else(|_| Workspace::new(root.to_string_lossy()));
        workspace.analyze();
        Self {
            client,
            root,
            workspace: RwLock::new(workspace),
        }
    }

    fn relative_path(&self, uri: &Url) -> Option<String> {
        let path = uri.to_file_path().ok()?;
        Some(
            path.strip_prefix(&self.root)
                .ok()?
                .to_string_lossy()
                .replace('\\', "/"),
        )
    }

    fn location(&self, value: SourceLocation) -> Option<Location> {
        Some(Location {
            uri: Url::from_file_path(self.root.join(&value.file)).ok()?,
            range: Range {
                start: Position::new(
                    value.start_line.saturating_sub(1),
                    value.start_character.saturating_sub(1),
                ),
                end: Position::new(
                    value.end_line.saturating_sub(1),
                    value.end_character.saturating_sub(1),
                ),
            },
        })
    }

    async fn publish_diagnostics(&self) {
        let diagnostics = self.workspace.write().await.diagnostics();
        let mut by_file = std::collections::BTreeMap::<String, Vec<Diagnostic>>::new();
        for diagnostic in diagnostics {
            by_file
                .entry(diagnostic.file.clone())
                .or_default()
                .push(to_diagnostic(diagnostic));
        }
        let paths = self
            .workspace
            .read()
            .await
            .paths()
            .map(str::to_string)
            .collect::<Vec<_>>();
        for path in paths {
            if let Ok(uri) = Url::from_file_path(self.root.join(&path)) {
                self.client
                    .publish_diagnostics(uri, by_file.remove(&path).unwrap_or_default(), None)
                    .await;
            }
        }
    }

    async fn update_document(&self, uri: &Url, text: String) {
        let Some(path) = self.relative_path(uri) else {
            return;
        };
        let mut workspace = self.workspace.write().await;
        workspace.set_file(path, text);
        workspace.analyze();
        drop(workspace);
        self.publish_diagnostics().await;
    }
}

#[tower_lsp::async_trait]
impl LanguageServer for Backend {
    async fn initialize(&self, _: InitializeParams) -> Result<InitializeResult> {
        Ok(InitializeResult {
            capabilities: ServerCapabilities {
                text_document_sync: Some(TextDocumentSyncCapability::Kind(
                    TextDocumentSyncKind::FULL,
                )),
                definition_provider: Some(OneOf::Left(true)),
                references_provider: Some(OneOf::Left(true)),
                hover_provider: Some(HoverProviderCapability::Simple(true)),
                document_symbol_provider: Some(OneOf::Left(true)),
                workspace_symbol_provider: Some(OneOf::Left(true)),
                ..ServerCapabilities::default()
            },
            server_info: Some(ServerInfo {
                name: "PySonar2".to_string(),
                version: Some(env!("CARGO_PKG_VERSION").to_string()),
            }),
        })
    }

    async fn initialized(&self, _: InitializedParams) {
        let summary = self.workspace.write().await.analyze();
        self.client
            .log_message(
                MessageType::INFO,
                format!(
                    "PySonar2 {} ready: {}/{} Python files parsed",
                    env!("CARGO_PKG_VERSION"),
                    summary.parsed_files,
                    summary.file_count
                ),
            )
            .await;
        self.publish_diagnostics().await;
    }

    async fn shutdown(&self) -> Result<()> {
        Ok(())
    }

    async fn did_open(&self, params: DidOpenTextDocumentParams) {
        self.update_document(&params.text_document.uri, params.text_document.text)
            .await;
    }

    async fn did_change(&self, params: DidChangeTextDocumentParams) {
        if let Some(change) = params.content_changes.into_iter().last() {
            self.update_document(&params.text_document.uri, change.text)
                .await;
        }
    }

    async fn did_save(&self, params: DidSaveTextDocumentParams) {
        if let Ok(path) = params.text_document.uri.to_file_path() {
            if let Ok(text) = tokio::fs::read_to_string(path).await {
                self.update_document(&params.text_document.uri, text).await;
            }
        }
    }

    async fn goto_definition(
        &self,
        params: GotoDefinitionParams,
    ) -> Result<Option<GotoDefinitionResponse>> {
        let position = params.text_document_position_params.position;
        let uri = params.text_document_position_params.text_document.uri;
        let Some(path) = self.relative_path(&uri) else {
            return Ok(None);
        };
        let result = self.workspace.write().await.context(
            &path,
            position.line + 1,
            position.character + 1,
            500,
        );
        let locations = result
            .definitions
            .into_iter()
            .filter_map(|location| self.location(location))
            .collect::<Vec<_>>();
        Ok((!locations.is_empty()).then_some(GotoDefinitionResponse::Array(locations)))
    }

    async fn references(&self, params: ReferenceParams) -> Result<Option<Vec<Location>>> {
        let position = params.text_document_position.position;
        let uri = params.text_document_position.text_document.uri;
        let Some(path) = self.relative_path(&uri) else {
            return Ok(None);
        };
        let result = self.workspace.write().await.context(
            &path,
            position.line + 1,
            position.character + 1,
            2_000,
        );
        let mut locations = result.references;
        if params.context.include_declaration {
            locations.extend(result.definitions);
        }
        Ok(Some(
            locations
                .into_iter()
                .filter_map(|location| self.location(location))
                .collect(),
        ))
    }

    async fn hover(&self, params: HoverParams) -> Result<Option<Hover>> {
        let position = params.text_document_position_params.position;
        let uri = params.text_document_position_params.text_document.uri;
        let Some(path) = self.relative_path(&uri) else {
            return Ok(None);
        };
        let result = self.workspace.write().await.context(
            &path,
            position.line + 1,
            position.character + 1,
            50,
        );
        let Some(symbol) = result.symbol else {
            return Ok(None);
        };
        Ok(Some(Hover {
            contents: HoverContents::Markup(MarkupContent {
                kind: MarkupKind::Markdown,
                value: format!(
                    "**{}**\n\nType: `{}`\n\nConfidence: `{}` · Coverage: `{}`",
                    symbol,
                    result
                        .inferred_type
                        .unwrap_or_else(|| "Unknown".to_string()),
                    result.confidence,
                    result.coverage_status
                ),
            }),
            range: None,
        }))
    }

    async fn document_symbol(
        &self,
        params: DocumentSymbolParams,
    ) -> Result<Option<DocumentSymbolResponse>> {
        let Some(path) = self.relative_path(&params.text_document.uri) else {
            return Ok(None);
        };
        let symbols = self.workspace.write().await.symbols(None, 10_000);
        let values = symbols
            .into_iter()
            .filter(|symbol| symbol.location.file == path)
            .filter_map(|symbol| {
                Some(SymbolInformation {
                    name: symbol.name,
                    kind: symbol_kind(&symbol.kind),
                    tags: None,
                    #[allow(deprecated)]
                    deprecated: None,
                    location: self.location(symbol.location)?,
                    container_name: symbol
                        .qualified_name
                        .rsplit_once('.')
                        .map(|(container, _)| container.to_string()),
                })
            })
            .collect();
        Ok(Some(DocumentSymbolResponse::Flat(values)))
    }

    async fn symbol(
        &self,
        params: WorkspaceSymbolParams,
    ) -> Result<Option<Vec<SymbolInformation>>> {
        let values = self
            .workspace
            .write()
            .await
            .symbols(Some(&params.query), 500)
            .into_iter()
            .filter_map(|symbol| {
                Some(SymbolInformation {
                    name: symbol.name,
                    kind: symbol_kind(&symbol.kind),
                    tags: None,
                    #[allow(deprecated)]
                    deprecated: None,
                    location: self.location(symbol.location)?,
                    container_name: symbol
                        .qualified_name
                        .rsplit_once('.')
                        .map(|(container, _)| container.to_string()),
                })
            })
            .collect();
        Ok(Some(values))
    }
}

fn to_diagnostic(value: CoreDiagnostic) -> Diagnostic {
    Diagnostic {
        range: Range {
            start: Position::new(
                value.start_line.saturating_sub(1),
                value.start_character.saturating_sub(1),
            ),
            end: Position::new(
                value.end_line.saturating_sub(1),
                value.end_character.saturating_sub(1),
            ),
        },
        severity: Some(DiagnosticSeverity::ERROR),
        source: Some("pysonar2".to_string()),
        message: value.message,
        ..Diagnostic::default()
    }
}

fn symbol_kind(kind: &str) -> SymbolKind {
    match kind {
        "Module" => SymbolKind::MODULE,
        "Class" => SymbolKind::CLASS,
        "Function" | "Method" => SymbolKind::FUNCTION,
        "Property" => SymbolKind::PROPERTY,
        _ => SymbolKind::VARIABLE,
    }
}

#[tokio::main]
async fn main() {
    let (service, socket) = LspService::new(Backend::new);
    Server::new(tokio::io::stdin(), tokio::io::stdout(), socket)
        .serve(service)
        .await;
}
