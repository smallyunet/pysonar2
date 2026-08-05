"""Multiple-inheritance and override examples for semantic navigation."""


class BaseStrategy:
    def adjust(self, score: float) -> float:
        return score

    def audit_label(self) -> str:
        return "base"


class AuditedStrategy(BaseStrategy):
    """Keeps the base implementation while contributing audit behavior."""

    def audit(self, score: float) -> str:
        return self.audit_label() + ":" + str(score)


class WeightedStrategy(BaseStrategy):
    def adjust(self, score: float) -> float:
        return min(1.0, score * 1.02)

    def audit_label(self) -> str:
        return "weighted"


class AuditedMarketStrategy(AuditedStrategy, WeightedStrategy):
    """C3 resolves inherited methods through WeightedStrategy before BaseStrategy."""

    @classmethod
    def strategy_name(cls) -> str:
        return cls.__name__

    @staticmethod
    def supports_live_markets() -> bool:
        return True
