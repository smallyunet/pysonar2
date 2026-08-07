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
