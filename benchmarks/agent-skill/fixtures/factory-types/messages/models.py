class EmailMessage:
    def __init__(self, destination, subject, body):
        self.destination = destination
        self.subject = subject
        self.body = body


class SmsMessage:
    def __init__(self, destination, body):
        self.destination = destination
        self.body = body
