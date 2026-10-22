"""Run an isolated server and two real clients using launch specs exported by Gradle."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--fixture', type=Path, required=True, help='Existing development test world containing level.dat')
parser.add_argument('--port', type=int, default=54187)
args = parser.parse_args()
fixture = args.fixture.resolve()
base = ROOT / 'build/reports/network-stress'
run = base / datetime.datetime.now().strftime('run-%Y%m%d-%H%M%S')
run.mkdir(parents=True)
processes = []
readers = []
results = {'state': 'running', 'run': str(run), 'port': args.port,
           'worldSha256': hashlib.sha256((fixture / 'level.dat').read_bytes()).hexdigest()}
(run / 'result.json').write_text(json.dumps(results, indent=2))
server_dir = run / 'server'
server_dir.mkdir()
shutil.copytree(fixture, server_dir / 'world', ignore=shutil.ignore_patterns('session.lock', 'voxy'))
(server_dir / 'server.properties').write_text(
    f'server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nview-distance=2\n'
    'simulation-distance=2\nmax-players=4\nspawn-protection=0\nmax-tick-time=120000\n'
    'level-name=world\nnetwork-compression-threshold=-1\nmotd=Voxy isolated network regression\n', encoding='utf-8')

def start(name, server=False):
    spec = json.loads((base / ('runServer.json' if server else 'runClient.json')).read_text())
    work = server_dir if server else run / name
    if not server:
        (work / 'config').mkdir(parents=True)
        shutil.copytree(base / 'client/mods', work / 'mods')
        (work / 'config/voxy-config.json').write_text(json.dumps({
            'enabled': True, 'enable_rendering': False, 'section_render_distance': 0.625,
            'distant_gen_enabled': False, 'service_threads': 2,
        }))
        (work / 'options.txt').write_text('renderDistance:2\nsimulationDistance:5\nmaxFps:30\n'
            'enableVsync:false\npauseOnLostFocus:false\nfullscreen:false\n'
            'overrideWidth:640\noverrideHeight:360\njoinedFirstServer:true\n')
    launch = []
    for arg in spec['args']:
        if arg.startswith('-Xmx') or arg.startswith('-Xms'):
            continue
        if arg.startswith('-Dvoxy.networkStress='):
            arg = '-Dvoxy.networkStress=' + str(run)
        launch.append(arg)
    launch.insert(0, '-Xmx3G')
    if not server:
        for flag, value in [('--username', name), ('--uuid', format(1 if name == 'Fast' else 2, '032x')),
                            ('--quickPlayMultiplayer', '127.0.0.1:' + str(args.port)),
                            ('--gameDir', str(work)), ('--width', '640'), ('--height', '360')]:
            if flag in launch:
                launch[launch.index(flag) + 1] = value
            else:
                launch.extend([flag, value])
    argfile = run / (name + '.args')
    argfile.write_text('\n'.join('"' + str(a).replace('\\', '\\\\').replace('"', '\\"') + '"' for a in launch), encoding='utf-8')
    env = os.environ.copy()
    if spec.get('modClasses') is not None:
        env['MOD_CLASSES'] = spec['modClasses']
    assert spec['executable'], 'Export a launch spec with its Java toolchain executable'
    proc = subprocess.Popen([spec['executable'], '@' + str(argfile)], cwd=work, env=env,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding='utf-8', errors='replace', creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    processes.append(proc)
    def read():
        with (run / (name + '.log')).open('w', encoding='utf-8') as log:
            for line in proc.stdout:
                log.write(line); log.flush()
                if 'NETWORK_STRESS' in line or 'Done (' in line:
                    print(name + ': ' + line.strip(), flush=True)
    thread = threading.Thread(target=read, daemon=True); thread.start(); readers.append(thread)
    return proc

try:
    print('Artifacts: ' + str(run), flush=True)
    server = start('Server', True)
    deadline = time.monotonic() + 180
    while True:
        if server.poll() is not None:
            raise RuntimeError('server exited before startup, exit=' + str(server.returncode))
        if (run / 'Server.log').exists() and 'Done (' in (run / 'Server.log').read_text(encoding='utf-8'):
            break
        if time.monotonic() > deadline:
            raise TimeoutError('server startup')
        time.sleep(0.5)
    fast = start('Fast'); slow = start('Slow')
    deadline = time.monotonic() + 360
    while any(p.poll() is None for p in (fast, slow)):
        if server.poll() is not None:
            raise RuntimeError('server exited during load')
        for name, process in [('Fast', fast), ('Slow', slow)]:
            if process.poll() is not None and (process.returncode != 0 or not (run / (name + '.pass')).exists()):
                raise RuntimeError(name + ' failed, exit=' + str(process.returncode))
        if time.monotonic() > deadline:
            raise TimeoutError('clients')
        time.sleep(0.5)
    for name, proc in [('Fast', fast), ('Slow', slow)]:
        assert proc.returncode == 0 and (run / (name + '.pass')).exists(), name
    assert (run / 'server.pass').exists(), 'server did not finish recovery'
    server.stdin.write('stop\n'); server.stdin.flush(); server.wait(timeout=60)
    assert server.returncode == 0, server.returncode
    for thread in readers:
        thread.join(timeout=5)
    for name in ['Server', 'Fast', 'Slow']:
        log = (run / (name + '.log')).read_text(encoding='utf-8')
        assert re.search(r'/(ERROR|FATAL)\]', log) is None, name + ' logged an error'
        if name != 'Server':
            assert 'NETWORK_STRESS FINAL_STORE PASS sections=2304' in log, name + ' did not verify its final store'
    results['state'] = 'passed'
    results['evidence'] = {name: (run / name).read_text() for name in ['Fast.pass', 'Slow.pass', 'server.pass']}
    print('NETWORK_STRESS all three processes exited normally.', flush=True)
except BaseException as exc:
    results['state'] = 'failed'; results['error'] = repr(exc)
    raise
finally:
    for proc in reversed(processes):
        if proc.poll() is None:
            if proc is processes[0]:
                try:
                    proc.stdin.write('stop\n'); proc.stdin.flush(); proc.wait(timeout=15)
                except (OSError, subprocess.TimeoutExpired):
                    proc.kill(); proc.wait(timeout=15)
            else:
                proc.kill(); proc.wait(timeout=15)
    (run / 'result.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
