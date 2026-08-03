def normalize(payload):
    return {key: value for key, value in payload.items() if key != "email"}
