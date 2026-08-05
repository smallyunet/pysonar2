"""Application service connecting models and scoring functions."""

from .models import Market, Prediction, Report
from .scoring import confidence_band, score_market
from .strategies import AuditedMarketStrategy


class DemoApp:
    def __init__(self, name: str):
        self.name = name
        self.strategy = AuditedMarketStrategy()

    @staticmethod
    def build_market(payload: dict) -> Market:
        return Market(
            payload["question"],
            payload["yes_price"],
            payload["volume"],
        )

    def predict(self, market: Market) -> Prediction:
        score = self.strategy.adjust(score_market(market))
        confidence = confidence_band(score)
        return Prediction(market, score, confidence)

    def build_report(self, payloads: list[dict]) -> Report:
        predictions: list[Prediction] = []
        for payload in payloads:
            market = self.build_market(payload)
            predictions.append(self.predict(market))

        title = self.name + " · " + str(len(predictions)) + " analyzed markets"
        return Report(title, predictions)
