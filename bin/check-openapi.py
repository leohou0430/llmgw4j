#!/usr/bin/env python3
"""Validate YAML uniqueness and the operations consumed by API clients (PyYAML 6)."""
import re
import sys
from pathlib import Path

import yaml


class UniqueLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise ValueError(f"duplicate key {key!r} at line {key_node.start_mark.line + 1}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def validate(path):
    document = yaml.load(path.read_text(), Loader=UniqueLoader)
    if path.name != "openapi.yaml":
        return
    if not isinstance(document, dict) or document.get("openapi") != "3.1.0":
        raise ValueError("expected an OpenAPI 3.1.0 document")
    paths = document.get("paths", {})
    if not paths:
        raise ValueError("paths must not be empty")
    for name, item in paths.items():
        if not isinstance(item, dict):
            raise ValueError(f"{name}: path item must contain operations")
        operations = {k: v for k, v in item.items()
                      if k in {"get", "post", "put", "patch", "delete", "head", "options", "trace"}}
        if not operations:
            raise ValueError(f"{name}: no operation")
        for method, operation in operations.items():
            if not isinstance(operation, dict) or not operation.get("responses"):
                raise ValueError(f"{method} {name}: missing responses")
            parameters = item.get("parameters", []) + operation.get("parameters", [])
            declared = {p.get("name") for p in parameters
                        if p.get("in") == "path" and p.get("required") is True}
            if set(re.findall(r"\{([^}]+)\}", name)) != declared:
                raise ValueError(f"{method} {name}: path parameters must be declared and required")


if __name__ == "__main__":
    try:
        for filename in sys.argv[1:] or ["docs/openapi.yaml"]:
            validate(Path(filename))
            print(f"PASS {filename}")
    except (ValueError, yaml.YAMLError, OSError) as error:
        sys.exit(str(error))
