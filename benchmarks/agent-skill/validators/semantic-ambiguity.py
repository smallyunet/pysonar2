import importlib
import sys

sys.path.insert(0, sys.argv[1])
service = importlib.import_module("pipeline.service")
partner = importlib.import_module("transforms.partner")
public = importlib.import_module("transforms.public")
admin = importlib.import_module("transforms.admin")
audit = importlib.import_module("transforms.audit")
legacy = importlib.import_module("transforms.legacy")

payload = {"email": "a@example.com", "token": "t", "secret": "s", "name": "Ada"}
assert service.process_partner(payload) == {"email": "a@example.com", "name": "Ada"}
assert service.process_public(payload) == {"token": "t", "secret": "s", "name": "Ada"}
assert public.normalize(payload) == {"token": "t", "secret": "s", "name": "Ada"}
assert admin.normalize(payload) == {"admin": payload}
assert audit.normalize(payload) == {"keys": ["email", "name", "secret", "token"]}
assert legacy.normalize(payload)["legacy"] is True
assert partner.normalize(payload) == {"email": "a@example.com", "name": "Ada"}
print("only the partner normalization binding changed")
