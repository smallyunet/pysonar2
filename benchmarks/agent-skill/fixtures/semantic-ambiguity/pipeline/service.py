from pipeline.selector import partner_transform, public_transform


def process_partner(payload):
    transform = partner_transform()
    return transform(payload)


def process_public(payload):
    transform = public_transform()
    return transform(payload)
