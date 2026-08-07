"""Public package API for the PySonar2 demo project."""

from .decorators import SymbolTools, build_command_registry
from .models import Market, Prediction, Report
from .service import DemoApp as PredictionEngine
from .strategies import AuditedMarketStrategy
from .syntax import classify_market, visible_market_names


__all__ = [
    "AuditedMarketStrategy",
    "Market",
    "Prediction",
    "PredictionEngine",
    "Report",
    "SymbolTools",
    "build_command_registry",
    "classify_market",
    "visible_market_names",
]
