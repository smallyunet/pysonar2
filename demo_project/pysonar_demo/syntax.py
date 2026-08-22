"""Modern syntax examples that preserve bindings and inferred values."""

from .models import Market


def classify_market(market: Market, /, *, threshold: float = 0.5) -> str:
    if (label := market.liquidity_label()) and market.yes_price >= threshold:
        match {"label": label, "active": True}:
            case {"label": captured, "active": True}:
                return captured.upper()
    return "WATCH"


def visible_market_names(markets: list[Market]) -> list[str]:
    return [
        market.display_name
        for market in markets
        if market.volume > 0
    ]


def iter_prices(markets: list[Market]):
    for market in markets:
        yield market.yes_price


def unpack_prices(prices: list[float]) -> tuple[float, list[float]]:
    match prices:
        case [first, *remaining]:
            return first, remaining
    return 0.0, []


class LabelContext:
    def __enter__(self) -> str:
        return "ACTIVE"

    def __exit__(self, exc_type, exc, traceback) -> bool:
        return False


def context_label() -> str:
    with LabelContext() as label:
        return label
