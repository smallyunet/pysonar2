def score_order(rows):
    """An unrelated report helper with the same name."""
    return sum(row["score"] for row in rows)
