from app.sanitizers import sanitize_internal as active_sanitizer


def sanitizer_for_webhook():
    return active_sanitizer
