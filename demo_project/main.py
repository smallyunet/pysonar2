"""Entry point for the PySonar2 code intelligence demo."""

from pysonar_demo import PredictionEngine, SymbolTools, build_command_registry
from pysonar_demo import classify_market, visible_market_names


def run_demo():
    app = PredictionEngine("Prediction Lab")
    markets = [
        {
            "question": "Will Python remain the most popular AI language?",
            "yes_price": 0.72,
            "volume": 125000,
        },
        {
            "question": "Will a static analyzer find this reference?",
            "yes_price": 0.91,
            "volume": 42000,
        },
        {
            "question": "Will type inference improve developer tools?",
            "yes_price": 0.64,
            "volume": 88000,
        },
    ]

    report = app.build_report(markets)
    print(report.title)
    for prediction in report.predictions:
        print(prediction.summary())

    commands = build_command_registry()
    print(commands["inspect"].run(SymbolTools.normalize_symbol("market-score")))
    print(classify_market(report.strongest().market, threshold=0.6))
    print(visible_market_names([prediction.market for prediction in report.predictions]))
    return report


REPORT = run_demo()
