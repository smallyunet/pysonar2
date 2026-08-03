import sys

sys.path.insert(0, sys.argv[1])
from retry import should_retry

assert should_retry(2, 3)
assert not should_retry(3, 3)
assert not should_retry(4, 3)
print("retry boundary validated")
