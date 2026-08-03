import unittest

from pricing.service import quote


class QuoteTest(unittest.TestCase):
    def test_quote(self):
        self.assertEqual(quote({"id": "a", "amount": 4, "tier": "gold"})["score"], 8)


if __name__ == "__main__":
    unittest.main()
