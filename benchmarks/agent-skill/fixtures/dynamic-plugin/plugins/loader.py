from importlib import import_module


def load_plugin(path):
    module_name, function_name = path.rsplit(":", 1)
    module = import_module(module_name)
    return getattr(module, function_name)
