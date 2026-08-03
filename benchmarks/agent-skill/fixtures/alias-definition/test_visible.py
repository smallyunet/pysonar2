import unittest

from app.webhook import process_webhook


class WebhookTest(unittest.TestCase):
    def test_regular_field_is_preserved(self):
        self.assertEqual(process_webhook({"name": "Ada"}), {"name": "Ada"})


if __name__ == "__main__":
    unittest.main()
