#!/usr/bin/env python3
"""Validate registration, JVM member descriptors and the shipped mixin resources.

Adapted from neo-voxy-multiversion's validate_mixin_targets.py. Read compiled
annotations instead of source regexes/javap name matching, and use Gradle's exact
classpath. Unresolved targets fail unless explicitly allowed as absent mods.
This is a static gate, not a replacement for Mixin's runtime injector validation.
"""

import argparse
import fnmatch
import json
from pathlib import Path
import re
import tomllib
from zipfile import ZipFile

from mixin_classfile import ClassFile, ClassPath, descriptor_parts

MIXIN = 'Lorg/spongepowered/asm/mixin/Mixin;'
SHADOW = 'Lorg/spongepowered/asm/mixin/Shadow;'
ACCESSOR = 'Lorg/spongepowered/asm/mixin/gen/Accessor;'
INVOKER = 'Lorg/spongepowered/asm/mixin/gen/Invoker;'
OVERWRITE = 'Lorg/spongepowered/asm/mixin/Overwrite;'
INJECTORS = {'Inject', 'Redirect', 'ModifyVariable', 'ModifyArg', 'ModifyArgs', 'ModifyConstant',
             'ModifyExpressionValue', 'ModifyReturnValue', 'WrapOperation', 'WrapMethod', 'WrapWithCondition'}
CALLBACK = 'Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;'
RETURN_CALLBACK = 'Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;'
COERCE = 'Lorg/spongepowered/asm/mixin/injection/Coerce;'


def java_without_comments(source):
    tokens = r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(tokens, lambda m: m[0] if m[0][0] in '\"\'' else re.sub(r'[^\n]', ' ', m[0]), source)


def metadata_configs(source):
    # Source metadata contains Gradle placeholders, including in dependency table names.
    data = tomllib.loads(re.sub(r'\$\{[^}]+}', 'placeholder', source))
    return [entry['config'] for entry in data.get('mixins', [])]


def class_targets(mixin):
    ann = mixin.annotations[MIXIN]
    return [value[1:-1].replace('/', '.') for value in ann.get('value', [])] + ann.get('targets', [])


def plugin_body(text):
    match = re.search(r'\bgetMixins\s*\(\s*\)\s*\{', text)
    if match is None:
        raise ValueError('Mixin plugin must declare getMixins() for static registration checks')
    start, depth = match.end() - 1, 0
    for token in re.finditer(r'"(?:\\.|[^"\\])*"|[{}]', text[start:]):
        if token[0] == '{':
            depth += 1
        elif token[0] == '}':
            depth -= 1
            if depth == 0:
                return text[start:start + token.end()]
    raise ValueError('Unbalanced getMixins() body')


class Validator:
    def __init__(self, inputs):
        self.inputs = inputs
        self.root = Path(inputs['root'])
        self.policy = json.loads(Path(inputs['policy']).read_text(encoding='utf-8'))
        self.report = {'state': 'running', 'registeredMixins': [], 'inactiveMixins': [],
                       'checkedTargets': [], 'skippedTargets': [], 'optionalSelectors': [],
                       'checks': [], 'problems': []}
        self.local = {}
        self.class_bytes = {}
        for directory in inputs['classes']:
            for path in sorted(Path(directory).rglob('*.class')):
                data = path.read_bytes()
                info = ClassFile(data)
                self.local[info.name.replace('/', '.')] = info
                self.class_bytes[info.name + '.class'] = data
        if not self.local:
            raise ValueError('No compiled project classes; run the Gradle validateMixins task')
        self.cp = ClassPath(inputs['classes'] + inputs['classpath'])
        self.configs = {}

    def fail(self, message):
        self.report['problems'].append(message)

    def record(self, mixin, target, kind, selector):
        self.report['checks'].append({'mixin': mixin, 'target': target, 'kind': kind, 'selector': selector})

    def configuration(self):
        resources = self.root / 'src/main/resources'
        java = self.root / 'src/main/java'
        names = metadata_configs((resources / 'META-INF/neoforge.mods.toml').read_text(encoding='utf-8'))
        actual = {p.name for p in resources.glob('*.mixins.json')}
        if not names or len(names) != len(set(names)) or set(names) != actual:
            self.fail(f'Mixin metadata/config mismatch: metadata={names}, resources={sorted(actual)}')
        registered = {}
        for name in names:
            path = resources / name
            if not path.is_file():
                self.fail(f'Missing mixin config: {name}')
                continue
            config = json.loads(path.read_text(encoding='utf-8'))
            self.configs[name] = config
            package = config['package']
            entries = sum((config.get(side, []) for side in ('mixins', 'client', 'server')), [])
            plugin = config.get('plugin')
            if plugin:
                if plugin not in self.local:
                    self.fail(f'{name}: missing plugin class {plugin}')
                source = java / (plugin.replace('.', '/') + '.java')
                text = plugin_body(java_without_comments(source.read_text(encoding='utf-8')))
                # Current plugins use literal mixins.add(...). Refuse new dynamic list forms.
                for expression in re.findall(r'\breturn\s+([^;]+);', text):
                    if not re.fullmatch(r'\s*(?:mixins|null|List\.of\(\s*\))\s*', expression):
                        self.fail(f'{plugin}: unsupported dynamic mixin return: {expression}')
                additions = re.findall(r'\bmixins\s*\.\s*add\s*\(([^;]*)\)\s*;', text)
                if len(additions) != len(re.findall(r'\bmixins\s*\.\s*add\w*\s*\(', text)):
                    self.fail(f'{plugin}: unsupported dynamic mixin registration')
                for addition in additions:
                    literal = re.fullmatch(r'\s*"([\w.$]+)"\s*', addition)
                    if not literal:
                        self.fail(f'{plugin}: mixin registration must be a literal: {addition}')
                    else:
                        entries.append(literal[1])
            for entry in entries:
                full = package + '.' + entry
                if full in registered:
                    self.fail(f'Duplicate mixin registration: {full}')
                registered[full] = config.get('injectors', {}).get('defaultRequire', 0)
                if full not in self.local or MIXIN not in self.local[full].annotations:
                    self.fail(f'{name}: registered mixin has no compiled @Mixin class: {full}')
        annotated = {name for name, info in self.local.items() if MIXIN in info.annotations}
        for source in java.rglob('*.java'):
            text = java_without_comments(source.read_text(encoding='utf-8'))
            if re.search(r'@Mixin\s*\(', text):
                name = source.relative_to(java).with_suffix('').as_posix().replace('/', '.')
                if name not in annotated:
                    self.fail(f'Mixin source has no compiled annotation: {name}')
        inactive = self.policy.get('inactiveMixins', {})
        for name, reason in inactive.items():
            if name not in annotated or name in registered or not reason:
                self.fail(f'Invalid/stale inactive mixin entry: {name}')
        for name in sorted(annotated - registered.keys() - inactive.keys()):
            self.fail(f'Unregistered mixin without an explicit exclusion: {name}')
        targets = {target for name in registered if name in annotated for target in class_targets(self.local[name])}
        for target, entry in self.policy.get('optionalTargets', {}).items():
            if target not in targets or not entry.get('reason') or not target.startswith(entry.get('prefix', '!')):
                self.fail(f'Invalid/stale optional target entry: {target}')
        self.report['registeredMixins'] = sorted(registered)
        self.report['inactiveMixins'] = inactive
        return registered

    def optional_mod_absent(self, target):
        entry = self.policy.get('optionalTargets', {}).get(target)
        if entry is None:
            return False
        prefix = entry['prefix'].replace('.', '/')
        # A present mod with a removed/renamed target is an error, not an optional skip.
        for source in self.cp.entries:
            if isinstance(source, Path):
                if (source / prefix).exists():
                    return False
            elif any(name.startswith(prefix) and name.endswith('.class') for name in source.namelist()):
                return False
        return True

    def field_or_method(self, mixin, target, member, kind, names, descriptor, fields=False, factory=False):
        pool = target.fields if fields else target.methods
        found = [m for m in pool if m.name in names and m.descriptor == descriptor]
        label = '/'.join(names) + (':' if fields else '') + descriptor
        if not found or (not factory and any((m.access & 8) != (member.access & 8) for m in found)):
            self.fail(f'{mixin} -> {target.name}: {kind} missing or incompatible declared member {label}')
        else:
            self.record(mixin, target.name, kind, label)

    def handler(self, mixin, target, member, kind, selected):
        args, ret = descriptor_parts(member.descriptor)
        target_args, target_ret = descriptor_parts(selected.descriptor)
        error = None
        if kind == 'Inject':
            indices = [i for i, arg in enumerate(args) if arg in (CALLBACK, RETURN_CALLBACK)]
            if len(indices) != 1:
                error = 'expected one CallbackInfo parameter'
            else:
                index = indices[0]
                captured = args[:index]
                compatible = len(captured) == len(target_args) and all(
                    actual == expected or (COERCE in member.parameter_annotations.get(i, {})
                                           and actual == 'Ljava/lang/Object;'
                                           and expected.startswith(('L', '[')))
                    for i, (actual, expected) in enumerate(zip(captured, target_args)))
                if captured and not compatible:
                    error = f'callback arguments {args[:index]} do not match {target_args}'
                elif args[index] != (CALLBACK if target_ret == 'V' else RETURN_CALLBACK) or ret != 'V':
                    error = 'callback type/return does not match target return'
        elif kind == 'WrapMethod':
            operation = 'Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;'
            if operation not in args or args[:args.index(operation)] != target_args or ret != target_ret:
                error = 'wrapper arguments/return do not match target descriptor'
        elif kind == 'ModifyReturnValue':
            if not args or args[0] != target_ret or ret != target_ret:
                error = 'return modifier does not match target return'
        else:
            return  # Injection-point handlers need bytecode/local analysis at runtime.
        if (member.access & 8) != (selected.access & 8):
            error = 'handler static/instance mode does not match target'
        if error:
            self.fail(f'{mixin}.{member.name} -> {target.name}.{selected.name}{selected.descriptor}: {error}')
        else:
            self.record(mixin, target.name, kind + ' handler', member.name + member.descriptor)

    def members(self, mixin, target, default_require):
        name = mixin.name.replace('/', '.')
        for member in mixin.fields + mixin.methods:
            for kind, values in member.annotations.items():
                short = kind.rsplit('/', 1)[-1].rstrip(';')
                if kind == SHADOW or kind == OVERWRITE:
                    prefix = values.get('prefix', 'shadow$') if kind == SHADOW else ''
                    field = member in mixin.fields
                    base = member.name.removeprefix(prefix) if prefix else member.name
                    names = [base] + values.get('aliases', [])
                    descriptor = member.descriptor.replace('L' + mixin.name + ';', 'L' + target.name + ';')
                    self.field_or_method(name, target, member, short, names, descriptor, fields=field)
                elif kind == ACCESSOR or kind == INVOKER:
                    args, ret = descriptor_parts(member.descriptor)
                    inferred = re.sub(r'^(?:get|set|is|call|invoke|new|create)', '', member.name)
                    inferred = inferred[:1].lower() + inferred[1:]
                    target_name = values.get('value') or inferred
                    if kind == ACCESSOR:
                        if not ((not args and ret != 'V') or (len(args) == 1 and ret == 'V')):
                            self.fail(f'{name}.{member.name}: invalid accessor descriptor')
                            continue
                        descriptor = args[0] if args else ret
                        self.field_or_method(name, target, member, short, [target_name], descriptor, fields=True)
                    else:
                        factory = target_name == '<init>'
                        descriptor = '(' + ''.join(args) + ')V' if factory else member.descriptor
                        self.field_or_method(name, target, member, short, [target_name], descriptor, factory=factory)
                elif short in INJECTORS:
                    selectors = values.get('method', [])
                    if isinstance(selectors, str):
                        selectors = [selectors]
                    if not selectors:
                        self.fail(f'{name}.{member.name}: unsupported/empty injector selector')
                    for selector in selectors:
                        raw = re.sub(r'^L[^;]+;', '', selector)
                        method_name, sep, tail = raw.partition('(')
                        descriptor = '(' + tail if sep else None
                        matches = [m for m in target.methods if fnmatch.fnmatchcase(m.name, method_name)
                                   and (descriptor is None or m.descriptor == descriptor)]
                        if not matches:
                            # require=0 permits absence, never a same-name incompatible descriptor.
                            same_name = any(fnmatch.fnmatchcase(m.name, method_name) for m in target.methods)
                            if values.get('require', -1) == 0 or (values.get('require', -1) < 0 and default_require == 0):
                                if not same_name:
                                    self.report['optionalSelectors'].append({'mixin': name, 'target': target.name,
                                                                            'selector': selector, 'reason': 'require=0'})
                                    continue
                            self.fail(f'{name}.{member.name} -> {target.name}: missing injector method {selector}')
                        for selected in matches:
                            self.record(name, target.name, short + ' selector', selected.name + selected.descriptor)
                            self.handler(name, target, member, short, selected)
                elif (kind.startswith('Lorg/spongepowered/asm/mixin/injection/') or
                      kind.startswith('Lcom/llamalad7/mixinextras/injector/')):
                    self.fail(f'{name}.{member.name}: unsupported injector annotation {kind}')

    def targets(self, registered):
        for name, default_require in registered.items():
            mixin = self.local.get(name)
            if mixin is None or MIXIN not in mixin.annotations:
                continue
            targets = class_targets(mixin)
            if not targets:
                self.fail(f'{name}: no @Mixin targets')
            for target_name in targets:
                target = self.cp.resolve(target_name)
                if target is None:
                    if self.optional_mod_absent(target_name):
                        self.report['skippedTargets'].append({'mixin': name, 'target': target_name,
                                                             'reason': self.policy['optionalTargets'][target_name]['reason']})
                    else:
                        self.fail(f'{name}: required target is missing: {target_name}')
                    continue
                self.report['checkedTargets'].append({'mixin': name, 'target': target_name})
                self.members(mixin, target, default_require)

    def artifact(self):
        with ZipFile(self.inputs['jar']) as jar:
            entries = jar.namelist()
            if len(entries) != len(set(entries)):
                self.fail('Distributable JAR contains duplicate entries')
            meta_path = 'META-INF/neoforge.mods.toml'
            if meta_path not in entries:
                self.fail(f'Distributable JAR is missing {meta_path}')
            else:
                metadata = jar.read(meta_path).decode('utf-8')
                names = metadata_configs(metadata)
                if '${' in metadata or len(names) != len(set(names)) or set(names) != set(self.configs):
                    self.fail('Distributable JAR has unresolved or mismatched mixin metadata')
            packaged_configs = {name for name in entries if '/' not in name and name.endswith('.mixins.json')}
            if packaged_configs != set(self.configs):
                self.fail('Distributable JAR mixin config inventory differs from source metadata')
            for name, config in self.configs.items():
                if name not in entries:
                    self.fail(f'Distributable JAR is missing mixin config: {name}')
                elif jar.read(name) != (self.root / 'src/main/resources' / name).read_bytes():
                    self.fail(f'Distributable JAR contains a stale mixin config: {name}')
                if config.get('refmap') and config['refmap'] not in entries:
                    self.fail(f'Distributable JAR is missing refmap: {config["refmap"]}')
            class_names = set(self.report['registeredMixins']) | set(self.report['inactiveMixins'])
            class_names.update(c['plugin'] for c in self.configs.values() if c.get('plugin'))
            for name in sorted(class_names):
                path = name.replace('.', '/') + '.class'
                if path not in entries:
                    self.fail(f'Distributable JAR is missing mixin/plugin class: {name}')
                elif jar.read(path) != self.class_bytes.get(path):
                    self.fail(f'Distributable JAR contains stale mixin/plugin bytecode: {name}')

    def run(self):
        try:
            registered = self.configuration()
            self.targets(registered)
            self.artifact()
            self.report['state'] = 'failed' if self.report['problems'] else 'passed'
            return self.report
        finally:
            self.cp.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    report = {'state': 'running'}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report) + '\n', encoding='utf-8')
    try:
        validator = Validator(json.loads(args.inputs.read_text(encoding='utf-8')))
        report = validator.run()
    except Exception as exception:
        report = {'state': 'failed', 'problems': [f'{type(exception).__name__}: {exception}']}
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(f"Mixin validation {report['state']}: {len(report.get('registeredMixins', []))} registered, "
          f"{len(report.get('checkedTargets', []))} target checks, {len(report.get('checks', []))} member checks, "
          f"{len(report.get('skippedTargets', []))} absent optional targets")
    for skip in report.get('skippedTargets', []) + report.get('optionalSelectors', []):
        print('  SKIPPED ' + json.dumps(skip, ensure_ascii=False))
    for problem in report.get('problems', []):
        print('  ERROR ' + problem)
    return 0 if report['state'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
