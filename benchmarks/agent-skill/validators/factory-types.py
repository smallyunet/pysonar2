import sys

sys.path.insert(0, sys.argv[1])
from messages.service import deliver

assert deliver("email", {
    "to": "a@example.com", "subject": "Status", "body": "Long email body"
}) == "a@example.com: Status"
assert deliver("sms", {
    "to": "+123", "body": "abcdefghijklmnop"
}) == "+123: abcdefghijkl"
print("all unannotated factory result types implement preview")
