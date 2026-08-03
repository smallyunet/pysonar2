from messages.factory import build_message


def deliver(channel, payload):
    message = build_message(channel, payload)
    return message.destination
