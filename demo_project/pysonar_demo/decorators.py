"""Decorator factories and same-name descriptor aliases for semantic navigation."""


class DemoCommand:
    def __init__(self, name: str, handler):
        self.name = name
        self.handler = handler

    def run(self, symbol: str) -> str:
        return self.handler(symbol)


def command(name: str):
    """Return a decorator that replaces a function with a command object."""

    def decorate(handler):
        return DemoCommand(name, handler)

    return decorate


@command("inspect")
def inspect_symbol(symbol: str) -> str:
    return "semantic context for " + symbol


def normalize_symbol(symbol: str) -> str:
    return symbol.strip().replace("-", "_")


class SymbolTools:
    """The assignment keeps the original function in the same impact family."""

    normalize_symbol = staticmethod(normalize_symbol)


def build_command_registry() -> dict:
    return {inspect_symbol.name: inspect_symbol}
