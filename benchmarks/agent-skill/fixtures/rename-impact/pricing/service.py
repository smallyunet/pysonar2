from pricing.rules import score_order as compute_score


def quote(order):
    return {"id": order["id"], "score": compute_score(order, {"gold": 2})}
