import unittest

from app import format_value


class PluginTest(unittest.TestCase):
    def test_single_space(self):
        self.assertEqual(format_value("Hello World"), "hello-world")


if __name__ == "__main__":
    unittest.main()
