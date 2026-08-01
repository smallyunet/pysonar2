"""Application service connecting models and scoring functions."""

from .models import Market, Prediction, Report
from .scoring import confidence_band, score_market


class DemoApp:
    def __init__(self, name):
        self.name = name

    def build_market(self, payload):
        return Market(
            payload["question"],
            payload["yes_price"],
            payload["volume"],
        )

    def predict(self, market):
        score = score_market(market)
        confidence = confidence_band(score)
        return Prediction(market, score, confidence)

    def build_report(self, payloads):
        predictions = []
        for payload in payloads:
            market = self.build_market(payload)
            predictions.append(self.predict(market))

        title = self.name + " · " + str(len(predictions)) + " analyzed markets"
        return Report(title, predictions)
