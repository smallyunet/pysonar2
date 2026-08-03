import sys

sys.path.insert(0, sys.argv[1])
from app import format_value
from plugins.text import display_title

assert format_value("A  B") == "a-b"
assert format_value(" A   B ") == "-a-b-"
assert display_title("  hello   world ") == "Hello World"
print("dynamic plugin fixed without changing loader or sibling plugin")
