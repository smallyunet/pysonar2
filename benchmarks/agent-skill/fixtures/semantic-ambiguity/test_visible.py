import unittest

from pipeline.service import process_partner


class PartnerTest(unittest.TestCase):
    def test_regular_field_is_preserved(self):
        self.assertEqual(process_partner({"name": "Ada"}), {"name": "Ada"})


if __name__ == "__main__":
    unittest.main()
