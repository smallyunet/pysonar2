def score_order(order, weights):
    return order["amount"] * weights.get(order["tier"], 1)
