def sanitize_public_preview(payload):
    return {key: value for key, value in payload.items() if key != "email"}


def sanitize_internal(payload):
    return dict(payload)
