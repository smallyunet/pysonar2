import importlib.util
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_conformance.py")
SPEC = importlib.util.spec_from_file_location("conformance_benchmark", MODULE_PATH)
BENCHMARK = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(BENCHMARK)


class ConformanceRunnerTest(unittest.TestCase):
    def test_manifest_pins_unique_suites_and_full_commits(self):
        suites = BENCHMARK.load_manifest()["suites"]
        self.assertEqual(len(suites), len({suite["id"] for suite in suites}))
        self.assertTrue(all(len(suite["commit"]) == 40 for suite in suites))

    def test_type_normalization_matches_typeevalpy_adapter_boundary(self):
        self.assertEqual("int", BENCHMARK.normalized_type("fn(value) -> int"))
        self.assertEqual("str", BENCHMARK.normalized_type("async fn() -> str"))
        self.assertEqual("Widget", BENCHMARK.normalized_type("instance Widget"))
        self.assertEqual("Widget", BENCHMARK.normalized_type("instance package.Widget"))
        self.assertEqual("Widget", BENCHMARK.normalized_type("class Widget"))
        self.assertEqual(
            "callable",
            BENCHMARK.normalized_type("fn(value) -> int", function_value=True),
        )
        self.assertIsNone(BENCHMARK.normalized_type(None))

    def test_repository_name_is_stable(self):
        self.assertEqual("cpython", BENCHMARK.repository_name("https://github.com/python/cpython.git"))

    def test_byte_offsets_recover_one_based_utf16_positions(self):
        source = "first\n结果 = value\n"
        offset = len("first\n".encode()) + len("结果 ".encode())
        self.assertEqual((2, 4), BENCHMARK.position_from_byte_offset(source, offset))

    def test_utf8_mirror_reports_non_utf8_sources(self):
        with tempfile.TemporaryDirectory() as source_temp, tempfile.TemporaryDirectory() as output_temp:
            source = Path(source_temp)
            (source / "ok.py").write_text("value = 1\n")
            (source / "legacy.py").write_bytes(b"# coding: latin-1\nvalue = '\xff'\n")
            copied, excluded = BENCHMARK.create_utf8_mirror(source, Path(output_temp))
            self.assertEqual(1, copied)
            self.assertEqual(["legacy.py"], excluded)

    def test_real_venv_package_is_not_excluded(self):
        with tempfile.TemporaryDirectory() as source_temp:
            source = Path(source_temp)
            (source / "stdlib" / "venv").mkdir(parents=True)
            (source / "stdlib" / "venv" / "__init__.pyi").write_text("class Env: ...\n")
            self.assertEqual([], BENCHMARK.excluded_source_paths(source, ".pyi"))

    def test_typeshed_mirror_preserves_stub_suffixes(self):
        with tempfile.TemporaryDirectory() as source_temp, tempfile.TemporaryDirectory() as output_temp:
            source = Path(source_temp)
            (source / "stdlib").mkdir()
            (source / "stdlib" / "builtins.pyi").write_text("class int: ...\n")
            self.assertEqual(1, BENCHMARK.create_typeshed_mirror(source, Path(output_temp)))
            self.assertTrue((Path(output_temp) / "stdlib" / "builtins.pyi").is_file())


if __name__ == "__main__":
    unittest.main()
