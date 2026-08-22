chrome.runtime.onInstalled.addListener(() => {
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
});

chrome.runtime.onMessage.addListener((message, sender) => {
  if (message?.type !== "pysonar2:analyze" || typeof message.source !== "string") return;
  const payload = {
    source: message.source,
    path: message.path || "page.py",
    title: message.title || sender.tab?.title || "Python snippet",
    url: sender.tab?.url || "",
    capturedAt: new Date().toISOString(),
  };
  chrome.storage.session.set({ pysonar2Pending: payload }).then(async () => {
    if (sender.tab?.windowId != null) {
      await chrome.sidePanel.open({ windowId: sender.tab.windowId }).catch(() => {});
    }
    chrome.runtime.sendMessage({ type: "pysonar2:pending", payload }).catch(() => {});
  });
});
