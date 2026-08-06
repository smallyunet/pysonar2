import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_benchmark.py")
SPEC = importlib.util.spec_from_file_location("change_safety_benchmark", MODULE_PATH)
BENCHMARK = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(BENCHMARK)


class ChangeSafetyBenchmarkTest(unittest.TestCase):
    def test_identifier_locations_excludes_comments_and_strings(self):
        source = "target = 1\n# target\nvalue = 'target'\n"
        self.assertEqual([(1, 1)], BENCHMARK.identifier_locations(source, "target"))

    def test_score_reports_safe_completion(self):
        gold = {("a.py", 1, 1), ("b.py", 2, 3)}
        self.assertTrue(BENCHMARK.score(set(gold), gold)["safeComplete"])
        partial = BENCHMARK.score({("a.py", 1, 1), ("c.py", 4, 5)}, gold)
        self.assertEqual(1, partial["falsePositive"])
        self.assertEqual(1, partial["falseNegative"])
        self.assertFalse(partial["safeComplete"])

    def test_query_location_requires_one_match(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "sample.py").write_text("def old_name():\n    pass\n")
            case = {
                "id": "sample",
                "queryFile": "sample.py",
                "queryText": "def old_name",
                "oldName": "old_name",
            }
            self.assertEqual((1, 5), BENCHMARK.query_location(root, case))

    def test_committed_result_covers_the_manifest(self):
        cases = BENCHMARK.load_cases()
        result = __import__("json").loads(
            (Path(__file__).parent / "results" / "2026-08-06.json").read_text()
        )
        self.assertEqual(12, len(cases))
        self.assertEqual(12, result["caseCount"])
        self.assertEqual(48, len(result["records"]))
        self.assertEqual(
            {case["id"] for case in cases},
            {record["caseId"] for record in result["records"]},
        )


if __name__ == "__main__":
    unittest.main()
