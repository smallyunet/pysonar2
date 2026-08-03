import unittest

from messages.service import deliver


class DeliveryTest(unittest.TestCase):
    def test_destination_is_in_audit_record(self):
        result = deliver("email", {"to": "a@example.com", "subject": "Hi", "body": "Body"})
        self.assertTrue(result.startswith("a@example.com"))


if __name__ == "__main__":
    unittest.main()
