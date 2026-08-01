"""Scoring functions with branching and recursive type flow."""


def clamp(value, lower=0.0, upper=1.0):
    if value < lower:
        return lower
    if value > upper:
        return upper
    return value


def confidence_band(score):
    distance = abs(score - 0.5)
    if distance >= 0.35:
        return "high conviction"
    if distance >= 0.18:
        return "moderate conviction"
    return "watchlist"


def weighted_signal(values):
    """Collapse a list recursively so the demo exposes recursive references."""
    if len(values) == 1:
        return values[0]
    head = values[0]
    tail = weighted_signal(values[1:])
    return head * 0.6 + tail * 0.4


def score_market(market):
    price_signal = clamp(market.yes_price)
    volume_signal = clamp(market.volume / 150000.0)
    return weighted_signal([price_signal, volume_signal])
