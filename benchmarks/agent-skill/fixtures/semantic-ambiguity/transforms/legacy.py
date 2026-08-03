def normalize(payload):
    payload = dict(payload)
    payload["legacy"] = True
    return payload
