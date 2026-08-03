import re


def compact_slug(value):
    return value.lower().replace(" ", "-")


def display_title(value):
    return re.sub(r"\s+", " ", value).strip().title()
