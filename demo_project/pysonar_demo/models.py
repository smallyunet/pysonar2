"""Domain models used by the demo application."""


class Market:
    def __init__(self, question: str, yes_price: float, volume: int):
        self.question = question
        self.yes_price = yes_price
        self.volume = volume

    @property
    def display_name(self) -> str:
        return self.question + " · " + self.liquidity_label()

    def liquidity_label(self) -> str:
        if self.volume >= 100000:
            return "deep"
        if self.volume >= 50000:
            return "active"
        return "emerging"


class Prediction:
    def __init__(self, market: Market, score: float, confidence: str):
        self.market = market
        self.score = score
        self.confidence = confidence

    def summary(self) -> str:
        direction = "YES" if self.score >= 0.5 else "NO"
        return direction + " · " + self.confidence + " · " + self.market.display_name


class Report:
    def __init__(self, title: str, predictions: list[Prediction]):
        self.title = title
        self.predictions = predictions

    def strongest(self) -> Prediction:
        best = self.predictions[0]
        for prediction in self.predictions[1:]:
            if prediction.score > best.score:
                best = prediction
        return best
