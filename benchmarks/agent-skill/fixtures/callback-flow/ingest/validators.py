def strict_external_id(value):
    if not value.startswith("EXT-"):
        raise ValueError("invalid external id")
    return value


def lenient_external_id(value):
    value = value.upper()
    return strict_external_id(value)
