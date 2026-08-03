from transforms.partner import normalize as selected_partner
from transforms.public import normalize as selected_public


def partner_transform():
    return selected_partner


def public_transform():
    return selected_public
