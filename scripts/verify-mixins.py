#!/usr/bin/env python3
"""Compile isolated JVM fixtures and verify the mixin gate's failure boundaries."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / 'build/reports/mixins'
TARGET = '''package fixture;
public class Target {
    public int state;
    public Target() {}
    public void update(int number) {}
    public int value(int number) { return number; }
}
'''
MIXIN = '''package fixture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Target.class)
public abstract class Probe {
    @Shadow public int state;
    @Shadow public abstract int value(int number);
    @Accessor("state") public abstract int getState();
    @Inject(method="update(I)V", at=@At("HEAD"))
    private void update(int number, CallbackInfo ci) {}
    @Inject(method="<init>()V", at=@At("RETURN"))
    private void init(CallbackInfo ci) {}
}
'''
OPTIONAL = '''package fixture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="optional.Integration", remap=false)
public class OptionalProbe {
    @Inject(method="update(I)V", at=@At("HEAD"))
    private void update(int number, CallbackInfo ci) {}
}
'''
PLUGIN = '''package fixture;
import java.util.*;
import org.spongepowered.asm.mixin.extensibility.*;
import org.objectweb.asm.tree.ClassNode;
public class Plugin implements IMixinConfigPlugin {
    public void onLoad(String name) {}
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String target, String mixin) { return true; }
    public void acceptTargets(Set<String> own, Set<String> other) {}
    public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        // mixins.add("CommentOnly");
        mixins.add("Probe");
        return mixins;
    }
    public void preApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
    public void postApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
}
'''


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def main():
    inputs = json.loads((REPORTS / 'inputs.json').read_text(encoding='utf-8'))
    sandbox = Path(tempfile.mkdtemp(prefix='fixtures-', dir=REPORTS))
    java_home = os.environ.get('JAVA_HOME')
    javac = str(Path(java_home) / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')) if java_home else shutil.which('javac')
    if not javac:
        raise RuntimeError('Set JAVA_HOME or put JDK 21 javac on PATH before running fixture verification')
    results = []

    def compile_java(project, sources, destination, classpath):
        destination.mkdir(parents=True, exist_ok=True)
        # An argument file avoids Windows command-line limits with the real Gradle classpath.
        args = ['--release', '21', '-proc:none', '-encoding', 'UTF-8']
        if classpath:
            args += ['-classpath', os.pathsep.join(str(p) for p in classpath)]
        args += ['-d', str(destination)] + [str(p) for p in sources]
        argfile = project / 'javac.args'
        argfile.write_text('\n'.join('"' + arg.replace('\\', '/') + '"' for arg in args), encoding='utf-8')
        result = subprocess.run([javac, '@' + str(argfile)], capture_output=True, timeout=120)
        if result.returncode:
            raise RuntimeError(f'javac exited {result.returncode}: ' +
                               (result.stdout + result.stderr).decode('utf-8', errors='replace'))

    def target(project, sources):
        java = project / 'targets-src'
        paths = []
        for name, source in sources.items():
            path = java / (name.replace('.', '/') + '.java')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding='utf-8')
            paths.append(path)
        compile_java(project, paths, project / 'targets', [])

    def mixin(project, source=MIXIN, optional=False):
        java = project / 'src/main/java/fixture'
        (java / 'Probe.java').write_text(source, encoding='utf-8')
        if optional:
            (java / 'OptionalProbe.java').write_text(OPTIONAL, encoding='utf-8')
        compile_java(project, sorted(java.glob('*.java')), project / 'classes',
                     [base / 'targets'] + inputs['classpath'])

    def artifact(project, omit=()):
        with ZipFile(project / 'mod.jar', 'w') as jar:
            for folder in [project / 'src/main/resources', project / 'classes']:
                for path in folder.rglob('*'):
                    if path.is_file() and path.relative_to(folder).as_posix() not in omit:
                        jar.write(path, path.relative_to(folder).as_posix())

    def config(project, **changes):
        path = project / 'src/main/resources/probe.mixins.json'
        data = json.loads(path.read_text())
        data.update(changes)
        write_json(path, data)

    base = sandbox / 'base'
    (base / 'src/main/java/fixture').mkdir(parents=True)
    resources = base / 'src/main/resources'
    (resources / 'META-INF').mkdir(parents=True)
    (resources / 'META-INF/neoforge.mods.toml').write_text('[[mixins]]\nconfig="probe.mixins.json"\n')
    write_json(resources / 'probe.mixins.json', {'package': 'fixture', 'mixins': ['Probe'],
                                                'injectors': {'defaultRequire': 1}})
    write_json(base / 'policy.json', {'inactiveMixins': {}, 'optionalTargets': {}})
    target(base, {'fixture.Target': TARGET, 'fixture.Second': TARGET.replace('Target', 'Second')})
    mixin(base)
    artifact(base)

    def case(name, mutate=None, error=None, skipped=0, optional_selectors=0, omit=(), rebuild=True):
        project = sandbox / name
        shutil.copytree(base, project)
        data = {'root': str(project), 'classes': [str(project / 'classes')],
                'classpath': [str(project / 'targets')] + inputs['classpath'],
                'jar': str(project / 'mod.jar'), 'policy': str(project / 'policy.json')}
        if mutate:
            mutate(project, data)
        if rebuild:
            artifact(project, omit)
        write_json(project / 'inputs.json', data)
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/validate_mixin_targets.py'),
                                 '--inputs', str(project / 'inputs.json'), '--report', str(project / 'report.json')],
                                capture_output=True, timeout=120)
        (project / 'validator.log').write_bytes(result.stdout + result.stderr)
        report = json.loads((project / 'report.json').read_text(encoding='utf-8'))
        if error:
            passed = result.returncode != 0 and report['state'] == 'failed' and error in '\n'.join(report['problems'])
        else:
            passed = (result.returncode == 0 and report['state'] == 'passed'
                      and len(report['skippedTargets']) == skipped
                      and len(report['optionalSelectors']) == optional_selectors)
        results.append({'name': name, 'passed': passed, 'exitCode': result.returncode,
                        'expectedError': error, 'report': str(project / 'report.json')})
        print(f"{'PASS' if passed else 'FAIL'} {name}", flush=True)

    def optional(project, data, present=None):
        mixin(project, optional=True)
        config(project, mixins=['Probe', 'OptionalProbe'])
        write_json(project / 'policy.json', {'optionalTargets': {'optional.Integration': {
            'prefix': 'optional.', 'reason': 'Absent fixture integration'}}})
        if present:
            target(project, present)

    def plugin(project, data, source=PLUGIN, registrations=()):
        (project / 'src/main/java/fixture/Plugin.java').write_text(source, encoding='utf-8')
        mixin(project)
        config(project, mixins=list(registrations), plugin='fixture.Plugin')

    case('valid')
    case('missing-required-target', lambda p, d: d.update(classpath=inputs['classpath']), error='required target is missing')
    case('method-descriptor', lambda p, d: target(p, {'fixture.Target': TARGET.replace('update(int', 'update(long')}),
         error='missing injector method update(I)V')
    case('bare-selector-handler', lambda p, d: mixin(p, MIXIN.replace('method="update(I)V"', 'method="update"')
                                                    .replace('update(int number, CallbackInfo', 'update(long number, CallbackInfo')),
         error='callback arguments')
    case('shadow-field-descriptor', lambda p, d: target(p, {'fixture.Target': TARGET.replace('int state', 'long state')}),
         error='Shadow missing or incompatible declared member state:I')
    case('shadow-method-descriptor', lambda p, d: target(p, {'fixture.Target': TARGET.replace('int value', 'long value')}),
         error='Shadow missing or incompatible declared member value(I)I')
    case('accessor-descriptor', lambda p, d: mixin(p, MIXIN.replace('int getState()', 'long getState()')),
         error='Accessor missing or incompatible declared member state:J')
    case('static-mode', lambda p, d: target(p, {'fixture.Target': TARGET.replace('int state', 'static int state')}),
         error='Shadow missing or incompatible')
    case('inherited-shadow', lambda p, d: target(p, {
        'fixture.Target': TARGET.replace('class Target {', 'class Target extends Parent {').replace('public int state;', ''),
        'fixture.Parent': 'package fixture; public class Parent { public int state; }'}), error='Shadow missing or incompatible')
    case('missing-constructor', lambda p, d: target(p, {'fixture.Target': TARGET.replace('Target()', 'Target(int number)')}),
         error='missing injector method <init>()V')
    case('missing-class-initializer', lambda p, d: mixin(p, MIXIN.replace('<init>()V', '<clinit>()V').replace(
        'private void init', 'private static void init')), error='missing injector method <clinit>()V')

    def multi(project, data):
        mixin(project, MIXIN.replace('@Mixin(Target.class)', '@Mixin({Target.class, Second.class})'))
        target(project, {'fixture.Second': TARGET.replace('Target', 'Second').replace('update(int', 'update(long')})
    case('every-multiple-target', multi, error='fixture/Second: missing injector method update(I)V')
    case('optional-absent', optional, skipped=1)
    case('optional-present-renamed', lambda p, d: optional(p, d, {
        'optional.Marker': 'package optional; public class Marker {}'}), error='required target is missing: optional.Integration')
    case('optional-present-bad-signature', lambda p, d: optional(p, d, {
        'optional.Integration': 'package optional; public class Integration { public void update(long n) {} }'}),
         error='missing injector method update(I)V')
    case('require-zero-absent', lambda p, d: mixin(p, MIXIN.replace('method="update(I)V"',
         'method="absent(I)V", require=0')), optional_selectors=1)
    case('require-zero-wrong-signature', lambda p, d: mixin(p, MIXIN.replace('method="update(I)V"',
         'method="update(J)V", require=0')), error='missing injector method update(J)V')
    case('unregistered-mixin', lambda p, d: config(p, mixins=[]), error='Unregistered mixin')
    case('missing-compiled-registration', lambda p, d: config(p, mixins=['Probe', 'Missing']),
         error='registered mixin has no compiled @Mixin class')
    case('missing-config-jar', omit=('probe.mixins.json',), error='missing mixin config')
    case('missing-class-jar', omit=('fixture/Probe.class',), error='missing mixin/plugin class')
    case('missing-metadata-jar', omit=('META-INF/neoforge.mods.toml',), error='missing META-INF/neoforge.mods.toml')
    case('missing-refmap-jar', lambda p, d: config(p, refmap='probe.refmap.json'), error='missing refmap')
    case('stale-config-jar', lambda p, d: config(p, required=True), rebuild=False, error='stale mixin config')
    case('missing-classpath-file', lambda p, d: d['classpath'].append(str(p / 'missing.jar')),
         error='Missing classpath entry')
    case('plugin-registration', plugin)
    case('missing-plugin-jar', plugin, omit=('fixture/Plugin.class',), error='missing mixin/plugin class: fixture.Plugin')
    case('plugin-registers-missing-class', lambda p, d: plugin(p, d, PLUGIN.replace('mixins.add("Probe");',
         'mixins.add("Probe"); mixins.add("Missing");')), error='registered mixin has no compiled @Mixin class: fixture.Missing')
    case('plugin-variable-registration', lambda p, d: plugin(p, d, PLUGIN.replace('mixins.add("Probe");',
         'String chosen = "Probe"; mixins.add(chosen);')), error='mixin registration must be a literal')
    case('plugin-unsupported-return', lambda p, d: plugin(p, d, PLUGIN.replace('mixins.add("Probe");', '')
         .replace('return mixins;', 'return List.of("Missing");'), registrations=['Probe']),
         error='unsupported dynamic mixin return')

    def coerce(project, data, annotated=True, reference=True):
        source = MIXIN.replace('update(int number, CallbackInfo',
            'update(' + ('@org.spongepowered.asm.mixin.injection.Coerce ' if annotated else '') + 'Object number, CallbackInfo')
        if reference:
            target(project, {'fixture.Target': TARGET.replace('update(int number)', 'update(String number)')})
            source = source.replace('update(I)V', 'update(Ljava/lang/String;)V')
        mixin(project, source)
    case('coerce-reference', coerce)
    case('object-without-coerce', lambda p, d: coerce(p, d, annotated=False), error='callback arguments')
    case('coerce-primitive-rejected', lambda p, d: coerce(p, d, reference=False), error='callback arguments')
    summary = {'state': 'passed' if all(r['passed'] for r in results) else 'failed', 'cases': results}
    write_json(REPORTS / 'fixture-verification.json', summary)
    print(f"{sum(r['passed'] for r in results)}/{len(results)} checks passed; fixtures: {sandbox}")
    return 0 if summary['state'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
