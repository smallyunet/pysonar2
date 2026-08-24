# PySonar2 browser/WASM demo

The Manifest V3 reference extension finds Python code blocks on web pages, infers types, and builds a
small semantic index entirely in a Web Worker with the Rust/WASM PySonar2 core. It makes no network
requests and does not execute the code.

```bash
cargo install wasm-bindgen-cli --version 0.2.127
cd extensions/browser
npm run build
npm run check
npm run package
```

Load `extensions/browser` as an unpacked extension in Chromium, open the side panel, then hover a Python code block and select **Analyze locally**.
