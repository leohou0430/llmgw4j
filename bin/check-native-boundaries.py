#!/usr/bin/env python3
"""Enforce native foundation boundaries and named extension metadata for all owned Java sources."""
from collections import defaultdict
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
TRANSPORTS = ('llmgw-app/', 'llmgw-engine/llmgw-engine-webflux/', 'llmgw-engine/llmgw-engine-vt/')
FORBIDDEN = re.compile(r'\b(?:org\.springframework(?:\.|\b)|reactor\.|jakarta\.(?:annotation|inject|servlet))')


def java_code(text):
    text = re.sub(r'""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])\'', '', text, flags=re.S)
    return re.sub(r'/\*.*?\*/|//[^\n]*', '', text, flags=re.S)


def check(root):
    errors, modules, sources = [], [], []
    flat = defaultdict(list)
    def enforced(pom):
        xml = ET.parse(pom).getroot()
        own = xml.findtext('m:properties/m:llmgw.foundation.enforcer.skip', None, NS)
        if own is not None:
            return own.strip() == 'false'
        parent = xml.find('m:parent', NS)
        if parent is None:
            return False
        relative = parent.findtext('m:relativePath', '../pom.xml', NS)
        target = (pom.parent / relative).resolve() if relative else None
        return bool(target and target.is_file() and target != pom and target.is_relative_to(root.resolve()) and enforced(target))
    for pom in sorted(root.glob('llmgw-*/**/pom.xml')):
        if 'target' in pom.parts:
            continue
        xml = ET.parse(pom).getroot()
        module = pom.parent.relative_to(root).as_posix() + '/'
        modules.append((pom, xml, module))
        if pom.parent.parent == root and len(pom.parent.name.split('-')) > 2:
            flat['-'.join(pom.parent.name.split('-')[:2])].append(pom.parent.name)
        for dependency in xml.findall('m:dependencies/m:dependency', NS):
            if dependency.findtext('m:groupId', '', NS).startswith('org.apache.dubbo'):
                errors.append(f'{pom.relative_to(root)}: direct Dubbo dependency is forbidden')
        for path in (pom.parent / 'src/main/java').rglob('*.java'):
            if re.search(r'\borg\.apache\.dubbo\.', java_code(path.read_text())):
                errors.append(f'{path.relative_to(root)}: direct Dubbo type is forbidden')
        for path in (pom.parent / 'src/main/resources/META-INF/dubbo').rglob('*'):
            if path.is_file(): errors.append(f'{path.relative_to(root)}: legacy Dubbo metadata is forbidden')
        if module.startswith(TRANSPORTS):
            continue
        if xml.findtext('m:packaging', 'jar', NS) != 'pom' and not enforced(pom):
            errors.append(f'{pom.relative_to(root)}: foundation must enable transitive dependency enforcement')
        for dependency in xml.findall('m:dependencies/m:dependency', NS):
            group = dependency.findtext('m:groupId', '', NS)
            if group.startswith(('org.springframework', 'io.projectreactor')):
                errors.append(f'{pom.relative_to(root)}: foundation declares framework dependency {group}')
        for path in (pom.parent / 'src/main/java').rglob('*.java'):
            text = java_code(path.read_text())
            if FORBIDDEN.search(text):
                errors.append(f'{path.relative_to(root)}: framework type in foundation')
        for name in ('org.springframework.boot.autoconfigure.AutoConfiguration.imports', 'spring.factories'):
            for resource in (pom.parent / 'src/main/resources').rglob(name):
                if resource.read_text().strip():
                    errors.append(f'{resource.relative_to(root)}: foundation still uses Spring discovery')
    for domain, implementations in flat.items():
        if len(implementations) > 1 or (root / domain / 'pom.xml').exists():
            errors.append(f'{domain}: flat implementation directories must move under aggregator/api: {implementations}')
    for directory in sorted(root.glob('llmgw-*')):
        for path in directory.rglob('*.java'):
            if 'target' not in path.parts:
                sources.append(path)
    names = {}
    descriptors = 0
    for directory in root.glob('llmgw-*'):
        for resource in directory.glob('**/src/main/resources/META-INF/llmgw/extensions/*'):
            if not resource.is_file():
                continue
            descriptors += 1
            for line in resource.read_text().splitlines():
                line = line.split('#', 1)[0].strip()
                if not line:
                    continue
                if '=' not in line:
                    errors.append(f'{resource.relative_to(root)}: named extension mapping required')
                    continue
                aliases, implementation = [part.strip() for part in line.split('=', 1)]
                source_name = implementation.split('$')[0].replace('.', '/') + '.java'
                if not any(str(path).endswith(source_name) for path in sources):
                    errors.append(f'{resource.relative_to(root)}: implementation source missing: {implementation}')
                for alias in aliases.split(','):
                    key = (resource.name, alias.strip())
                    if key in names:
                        errors.append(f'{resource.relative_to(root)}: duplicate extension name {key}')
                    names[key] = implementation
    return errors, len(sources), len(modules), descriptors, len(names)


if __name__ == '__main__':
    errors, sources, modules, descriptors, names = check(ROOT)
    for error in errors:
        print('FAIL', error)
    if errors:
        raise SystemExit(1)
    print(f'PASS native boundaries: {sources} module Java files, {modules} modules, {descriptors} descriptors, {names} named extensions')
