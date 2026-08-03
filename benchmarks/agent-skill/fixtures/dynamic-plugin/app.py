from plugins.loader import load_plugin


PLUGIN = "plugins.text:compact_slug"


def format_value(value):
    return load_plugin(PLUGIN)(value)
