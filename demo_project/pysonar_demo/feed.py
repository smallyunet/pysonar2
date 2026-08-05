"""Async examples for modern Python AST navigation."""

from .models import Market


class MarketFeed:
    async def fetch(self) -> list[dict]:
        return [
            {"question": "Example market", "yes_price": 0.58, "volume": 32000},
        ]


async def refresh(feed: MarketFeed) -> list[Market]:
    payloads = await feed.fetch()
    markets: list[Market] = []
    for payload in payloads:
        markets.append(
            Market(payload["question"], payload["yes_price"], payload["volume"])
        )
    return markets
