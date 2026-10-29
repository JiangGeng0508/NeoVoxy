"""Verify a supplied SeedGen JAR in a fresh client directory and disposable world."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--seedgen-jar', type=Path, required=True)
options = parser.parse_args()
assert options.seedgen_jar.is_file(), options.seedgen_jar
base = Path('build/reports/seedgen-smoke').resolve()
run = base / time.strftime('run-%Y%m%d-%H%M%S')
work = run / 'client'
(work / 'config').mkdir(parents=True)
(work / 'mods').mkdir()
for mod in (base / 'mods').glob('sodium-*.jar'):
    shutil.copy2(mod, work / 'mods' / mod.name)
shutil.copy2(options.seedgen_jar, work / 'mods' / options.seedgen_jar.name)
(work / 'config/voxy-config.json').write_text(json.dumps({
    'enabled': True, 'enable_rendering': True, 'ingest_enabled': True,
    'distant_gen_enabled': False, 'section_render_distance': 0.625, 'service_threads': 2,
}))
(work / 'config/voxyseedgen.json').write_text(json.dumps({
    'serverGeneration': False, 'realChunks': False, 'extendedDistance': False,
    'structures': False, 'trees': False, 'waterFeatures': False, 'foliage': False,
    'fillRadius': 16, 'threads': 1,
}))
(work / 'options.txt').write_text('renderDistance:2\nsimulationDistance:5\nmaxFps:30\nenableVsync:false\n'
    'pauseOnLostFocus:false\nfullscreen:false\noverrideWidth:640\noverrideHeight:360\njoinedFirstServer:true\n')
spec = json.loads((base / 'launch.json').read_text())
args = [a for a in spec['args'] if not a.startswith(('-Xmx', '-Xms'))]
args[0:0] = ['-Xmx3G', '-Dvoxy.seedgenSmoke=' + str(run)]
for flag, value in [('--gameDir', str(work)), ('--username', 'SeedGenProbe'),
                    ('--uuid', '00000000000000000000000000000005'), ('--width', '640'), ('--height', '360')]:
    if flag in args:
        args[args.index(flag) + 1] = value
    else:
        args += [flag, value]
argfile = run / 'client.args'
argfile.write_text('\n'.join('"' + str(a).replace('\\', '\\\\').replace('"', '\\"') + '"' for a in args), encoding='utf-8')
env = os.environ.copy()
if spec.get('modClasses'):
    env['MOD_CLASSES'] = spec['modClasses']
print('SEEDGEN_SMOKE artifacts: ' + str(run), flush=True)
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
assert (run / 'complete.pass').exists(), f'Probe did not complete; see {run / "client.log"}'
print((run / 'complete.pass').read_text().strip(), flush=True)
print('SEEDGEN_SMOKE PASS', flush=True)
