"""Async examples for modern Python AST navigation."""

from .models import Market


class MarketFeed:
    async def fetch(self):
        return [
            {"question": "Example market", "yes_price": 0.58, "volume": 32000},
        ]


async def refresh(feed):
    payloads = await feed.fetch()
    markets = []
    for payload in payloads:
        markets.append(
            Market(payload["question"], payload["yes_price"], payload["volume"])
        )
    return markets
