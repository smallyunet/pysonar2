from app.routing import sanitizer_for_webhook


def process_webhook(payload):
    sanitizer = sanitizer_for_webhook()
    return sanitizer(payload)
