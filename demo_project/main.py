"""Entry point for the PySonar2 code intelligence demo."""

from pysonar_demo import DemoApp


def run_demo():
    app = DemoApp("Prediction Lab")
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
    return report


REPORT = run_demo()
