from messages.models import EmailMessage, SmsMessage


def build_message(channel, payload):
    if channel == "email":
        return EmailMessage(payload["to"], payload["subject"], payload["body"])
    return SmsMessage(payload["to"], payload["body"])
