import unittest

from ingest.service import ingest_partner


class PartnerTest(unittest.TestCase):
    def test_partner_accepts_canonical_id(self):
        self.assertEqual(ingest_partner("EXT-7"), {"external_id": "EXT-7"})


if __name__ == "__main__":
    unittest.main()
