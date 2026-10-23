"""Real client probes, using only newly created worlds/configs under build/reports."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--bare', action='store_true')
parser.add_argument('--iris', action='store_true', help='Include Iris, with shaders disabled')
options = parser.parse_args()
base = Path('build/reports/pingwheel-smoke').resolve()
run = base / (time.strftime('run-%Y%m%d-%H%M%S') + ('-bare' if options.bare else '-iris' if options.iris else '-sodium'))
work = run / 'client'
(work / 'config').mkdir(parents=True)
(work / 'mods').mkdir()
for mod in (base / 'mods').glob('*.jar'):
    if mod.name.startswith('sodium-') or (not options.bare and mod.name.startswith('ping-wheel-')) or (options.iris and mod.name.startswith('iris-')):
        shutil.copy2(mod, work / 'mods' / mod.name)
(work / 'config/voxy-config.json').write_text(json.dumps({
    'enabled': True, 'enable_rendering': True, 'ingest_enabled': False, 'distant_gen_enabled': False,
    'section_render_distance': 0.625, 'service_threads': 2, 'pingwheel_lod_picking': True,
}))
(work / 'options.txt').write_text('renderDistance:2\nsimulationDistance:5\nmaxFps:30\nenableVsync:false\n'
    'pauseOnLostFocus:false\nfullscreen:false\noverrideWidth:640\noverrideHeight:360\njoinedFirstServer:true\n')
spec = json.loads((base / 'launch.json').read_text())
args = [a for a in spec['args'] if not a.startswith(('-Xmx', '-Xms'))]
args[0:0] = ['-Xmx3G', '-Dvoxy.pingwheelSmoke=' + str(run), '-Dvoxy.pingwheelBare=' + str(options.bare).lower()]
for flag, value in [('--gameDir', str(work)), ('--username', 'PingWheelProbe'), ('--uuid', '00000000000000000000000000000004'), ('--width', '640'), ('--height', '360')]:
    if flag in args:
        args[args.index(flag) + 1] = value
    else:
        args += [flag, value]
argfile = run / 'client.args'
argfile.write_text('\n'.join('"' + str(a).replace('\\', '\\\\').replace('"', '\\"') + '"' for a in args), encoding='utf-8')
env = os.environ.copy()
if spec.get('modClasses'):
    env['MOD_CLASSES'] = spec['modClasses']
print('PINGWHEEL_SMOKE artifacts: ' + str(run), flush=True)
with (run / 'client.log').open('w', encoding='utf-8') as log:
    proc = subprocess.Popen([spec['executable'], '@' + str(argfile)], cwd=work, env=env,
        stdout=log, stderr=subprocess.STDOUT, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    try:
        code = proc.wait(timeout=420)
    except BaseException:
        proc.terminate()
        proc.wait(timeout=15)
        raise
assert code == 0, f'Client failed ({code}); see {run / "client.log"}'
for name in (['bare'] if options.bare else ['lod', 'disabled', 'curve', 'cancel', 'near', 'complete']):
    assert (run / (name + '.pass')).exists(), f'Missing {name}; see {run / "client.log"}'
    print((run / (name + '.pass')).read_text().strip(), flush=True)
print('PINGWHEEL_SMOKE PASS', flush=True)
