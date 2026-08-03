import sys

sys.path.insert(0, sys.argv[1])
from ingest.service import ingest_admin, ingest_partner

assert ingest_partner("ext-9") == {"external_id": "EXT-9"}
try:
    ingest_admin("ext-9")
except ValueError:
    pass
else:
    raise AssertionError("admin path became lenient")
assert ingest_admin("EXT-9") == {"external_id": "EXT-9"}
print("first-class partner callback changed; admin callback stayed strict")
