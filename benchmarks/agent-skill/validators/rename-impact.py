import importlib
import sys

root = sys.argv[1]
sys.path.insert(0, root)
rules = importlib.import_module("pricing.rules")
service = importlib.import_module("pricing.service")
batch = importlib.import_module("pricing.batch")
assert hasattr(rules, "score_trade")
assert not hasattr(rules, "score_order")
assert service.quote({"id": "x", "amount": 3, "tier": "gold"})["score"] == 6
ranked = batch.rank([
    {"id": "low", "amount": 2, "tier": "plain"},
    {"id": "high", "amount": 2, "tier": "gold"},
])
assert [row["id"] for row in ranked] == ["high", "low"]
report = importlib.import_module("pricing.report")
assert report.score_order([{"score": 2}, {"score": 3}]) == 5
print("rename and all semantic references validated")
