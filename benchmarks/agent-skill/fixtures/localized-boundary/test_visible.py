import unittest

from retry import should_retry


class RetryTest(unittest.TestCase):
    def test_below_limit_retries(self):
        self.assertTrue(should_retry(2, 3))


if __name__ == "__main__":
    unittest.main()
