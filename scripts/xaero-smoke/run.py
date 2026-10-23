"""Isolated real-client write / shutdown / restart probe. Never reads user saves."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import argparse

parser = argparse.ArgumentParser()
parser.add_argument('--bare', action='store_true', help='Launch spec exported with -PxaeroBare')
parser.add_argument('--reload-run', type=Path, help='Repeat only the reload phase of an existing isolated run')
options = parser.parse_args()

base = Path('build/reports/xaero-smoke').resolve()
run = options.reload_run.resolve() if options.reload_run else base / time.strftime('run-%Y%m%d-%H%M%S')
assert run.parent == base and run.name.startswith('run-'), 'Reload must stay within the isolated smoke output directory'
work = run / 'client'
assert not options.reload_run or (work / 'saves/fixture/level.dat').is_file(), 'Existing isolated fixture required'
(work / 'config').mkdir(parents=True, exist_ok=bool(options.reload_run))
(work / 'mods').mkdir(exist_ok=bool(options.reload_run))
for mod in (base / 'mods').glob('*.jar'):
    if not options.bare or mod.name.startswith('sodium-'):
        shutil.copy2(mod, work / 'mods' / mod.name)
(work / 'config/voxy-config.json').write_text(json.dumps({
    'enabled': True, 'enable_rendering': False, 'ingest_enabled': False,
    'distant_gen_enabled': False,
    'section_render_distance': 0.625, 'service_threads': 2, 'xaero_map_coarse': False,
    'xaero_map_tiles_per_second': 128,
}))
(work / 'options.txt').write_text('renderDistance:2\nsimulationDistance:5\nmaxFps:30\n'
    'enableVsync:false\npauseOnLostFocus:false\nfullscreen:false\n'
    'overrideWidth:640\noverrideHeight:360\njoinedFirstServer:true\n')
spec = json.loads((base / 'launch.json').read_text())
print('XAERO_SMOKE artifacts: ' + str(run), flush=True)
for reload in ((True,) if options.reload_run else (False,) if options.bare else (False, True)):
    args = [a for a in spec['args'] if not a.startswith(('-Xmx', '-Xms', '-Dvoxy.xaeroSmoke='))]
    args[0:0] = ['-Xmx3G', '-Dvoxy.xaeroSmoke=' + str(run), '-Dvoxy.xaeroReload=' + str(reload).lower()]
    for flag, value in [('--gameDir', str(work)), ('--username', 'XaeroProbe'), ('--uuid', '00000000000000000000000000000003'),
                        ('--width', '640'), ('--height', '360')]:
        if flag in args:
            args[args.index(flag) + 1] = value
        else:
            args += [flag, value]
    argfile = run / ('reload.args' if reload else 'write.args')
    argfile.write_text('\n'.join('"' + str(a).replace('\\', '\\\\').replace('"', '\\"') + '"' for a in args), encoding='utf-8')
    env = os.environ.copy()
    if spec.get('modClasses'):
        env['MOD_CLASSES'] = spec['modClasses']
    log = run / ('reload.log' if reload else 'write.log')
    with log.open('w', encoding='utf-8') as output:
        proc = subprocess.Popen([spec['executable'], '@' + str(argfile)], cwd=work, env=env,
                                stdout=output, stderr=subprocess.STDOUT, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        try:
            code = proc.wait(timeout=420)
        except BaseException:
            proc.terminate()
            proc.wait(timeout=15)
            raise
    assert code == 0, f'Client failed ({code}); see {log}'
    marker = run / ('reload.pass' if reload else 'write.pass')
    assert marker.exists(), f'Probe did not pass; see {log}'
    print(marker.read_text(), flush=True)
if not options.bare:
    for name in ['precision', 'real-map', 'real-handoff', 'cache', 'import', 'receive', 'coarse-reload', 'eviction', 'subworld', 'native-zip', 'dimension']:
        assert (run / (name + '.pass')).exists(), 'Missing verification: ' + name
print('XAERO_SMOKE PASS', flush=True)
