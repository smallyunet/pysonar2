const PYTHON_HINT = /(^|\s)(language-|lang-)?(python|py)(\s|$)/i;
const MIN_CODE_LENGTH = 12;

function candidateSource(element) {
  const code = element.matches("code") ? element : element.querySelector("code") || element;
  const source = code.textContent || "";
  const hint = `${element.className || ""} ${code.className || ""}`;
  const looksPython = PYTHON_HINT.test(hint)
    || /(^|\n)\s*(def |class |from \S+ import |import \S+|async def )/.test(source)
    || location.pathname.endsWith(".py");
  return looksPython && source.trim().length >= MIN_CODE_LENGTH ? source : null;
}

function decorate(element, index) {
  if (element.dataset.pysonar2Decorated) return;
  const source = candidateSource(element);
  if (!source) return;
  element.dataset.pysonar2Decorated = "true";
  element.classList.add("pysonar2-code-host");
  if (getComputedStyle(element).position === "static") element.style.position = "relative";
  const button = document.createElement("button");
  button.type = "button";
  button.className = "pysonar2-analyze-button";
  button.textContent = "Analyze locally";
  button.setAttribute("aria-label", "Analyze this Python code locally with PySonar2");
  button.addEventListener("click", (event) => {
    event.preventDefault();
    event.stopPropagation();
    const path = location.pathname.endsWith(".py")
      ? location.pathname.split("/").filter(Boolean).pop()
      : `snippet-${index + 1}.py`;
    chrome.runtime.sendMessage({
      type: "pysonar2:analyze",
      source,
      path,
      title: document.title,
    });
  });
  element.append(button);
}

function scan(root = document) {
  const candidates = [...root.querySelectorAll("pre, .highlight-source-python, .blob-code-content")];
  candidates.forEach(decorate);
}

scan();
const observer = new MutationObserver((records) => {
  for (const record of records) {
    for (const node of record.addedNodes) {
      if (node instanceof Element) scan(node);
    }
  }
});
observer.observe(document.documentElement, { childList: true, subtree: true });
