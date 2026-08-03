import importlib
import sys

sys.path.insert(0, sys.argv[1])
sanitizers = importlib.import_module("app.sanitizers")
webhook = importlib.import_module("app.webhook")
payload = {"email": "a@example.com", "token": "t", "secret": "s", "name": "Ada"}
assert webhook.process_webhook(payload) == {"email": "a@example.com", "name": "Ada"}
assert sanitizers.sanitize_public_preview(payload) == {
    "token": "t", "secret": "s", "name": "Ada"
}
print("aliased active definition changed without touching lookalike")
