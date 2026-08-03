from pricing import rules


def rank(orders):
    return sorted(orders, key=lambda order: rules.score_order(order, {"gold": 2}), reverse=True)
