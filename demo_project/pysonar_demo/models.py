"""Domain models used by the demo application."""


class Market:
    def __init__(self, question, yes_price, volume):
        self.question = question
        self.yes_price = yes_price
        self.volume = volume

    def liquidity_label(self):
        if self.volume >= 100000:
            return "deep"
        if self.volume >= 50000:
            return "active"
        return "emerging"


class Prediction:
    def __init__(self, market, score, confidence):
        self.market = market
        self.score = score
        self.confidence = confidence

    def summary(self):
        direction = "YES" if self.score >= 0.5 else "NO"
        return direction + " · " + self.confidence + " · " + self.market.question


class Report:
    def __init__(self, title, predictions):
        self.title = title
        self.predictions = predictions

    def strongest(self):
        best = self.predictions[0]
        for prediction in self.predictions[1:]:
            if prediction.score > best.score:
                best = prediction
        return best
