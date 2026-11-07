"""Run a fresh disposable world with distant generation enabled through normal exit."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--world', type=Path, default=Path('.agents/work/verification/server/smoke-world'))
parser.add_argument('--shaders', action='store_true')
parser.add_argument('--without-voxy', action='store_true', help='Run an independent control mod with Voxy absent')
parser.add_argument('--force-local-close-race', action='store_true', help='Queue a vanilla packet across peer close in the control')
parser.add_argument('--force-unload-retry', action='store_true')
parser.add_argument('--phase', choices=['cold', 'warm'], default='warm')
parser.add_argument('--exit-mode', choices=['direct', 'disconnect'], default='disconnect')
parser.add_argument('--timeout', type=int, default=300)
args = parser.parse_args()
assert not (args.without_voxy and args.force_unload_retry), 'Unload fault injection requires Voxy'
assert not args.force_local_close_race or args.without_voxy, 'Peer-close race control requires --without-voxy'
source = args.world.resolve()
assert (source / 'level.dat').is_file(), source
base = Path('build/reports/distant-gen-shutdown').resolve()
run = base / (time.strftime('run-%Y%m%d-%H%M%S') + ('-iris-' if args.shaders else '-sodium-')
              + args.phase + ('-control' if args.without_voxy else ''))
work = run / 'client'
(work / 'config').mkdir(parents=True)
(work / 'mods').mkdir()
shutil.copytree(source, work / 'saves/fixture', ignore=shutil.ignore_patterns('voxy', 'session.lock'))
for mod in (base / 'mods').glob('*.jar'):
    if 'sodium' in mod.name or (args.shaders and 'iris' in mod.name):
        shutil.copy2(mod, work / 'mods' / mod.name)
if args.shaders:
    pack = Path('run/client/shaderpacks/ComplementaryReimagined_r5.8.1.zip')
    (work / 'shaderpacks').mkdir()
    shutil.copy2(pack, work / 'shaderpacks' / pack.name)
    (work / 'config/iris.properties').write_text('enableShaders=true\nshaderPack=' + pack.name + '\n')
(work / 'config/voxy-config.json').write_text(json.dumps({
    'enabled': True, 'enable_rendering': True, 'ingest_enabled': True,
    'distant_gen_enabled': True, 'section_render_distance': 0.625,
}))
(work / 'options.txt').write_text('renderDistance:4\nsimulationDistance:5\nmaxFps:60\nenableVsync:false\n'
    'pauseOnLostFocus:false\nfullscreen:false\noverrideWidth:960\noverrideHeight:540\njoinedFirstServer:true\n'
    'soundCategory_master:0.0\n')
spec = json.loads((base / 'launch.json').read_text())
launch = [a for a in spec['controlArgs' if args.without_voxy else 'args']
          if not a.startswith(('-Xmx', '-Xms', '-Dforge.logging.console.level='))]
launch[0:0] = ['-Xmx3G', '-Dforge.logging.console.level=info', '-Dvoxy.distantGenSmoke=' + str(run),
               '-Dvoxy.distantGenPhase=' + args.phase, '-Dvoxy.distantGenRetry=' + str(args.force_unload_retry).lower(),
               '-Dvoxy.distantGenExit=' + args.exit_mode,
               '-Dvoxy.forceLocalCloseRace=' + str(args.force_local_close_race).lower()]
for flag, value in [('--gameDir', str(work)), ('--username', 'DistantGenProbe'),
                    ('--uuid', '00000000000000000000000000000006'), ('--width', '960'), ('--height', '540'),
                    ('--quickPlaySingleplayer', 'fixture')]:
    if flag in launch:
        launch[launch.index(flag) + 1] = value
    else:
        launch += [flag, value]
argfile = run / 'client.args'
argfile.write_text('\n'.join('"' + str(a).replace('\\', '\\\\').replace('"', '\\"') + '"' for a in launch), encoding='utf-8')
env = os.environ.copy()
mod_classes = spec['controlModClasses' if args.without_voxy else 'modClasses']
if mod_classes:
    env['MOD_CLASSES'] = mod_classes
result = {'source': str(source), 'levelDatSha256': hashlib.sha256((source / 'level.dat').read_bytes()).hexdigest(),
          'shaders': args.shaders, 'phase': args.phase, 'forcedUnloadRetry': args.force_unload_retry,
          'exitMode': args.exit_mode, 'distantGeneration': not args.without_voxy, 'withoutVoxy': args.without_voxy,
          'forcedLocalCloseRace': args.force_local_close_race,
          'muted': True, 'status': 'running'}
class_root = Path('build/classes/java/disconnectControl' if args.without_voxy else 'build/classes/java/main')
result['classSha256'] = {str(p.relative_to(class_root)): hashlib.sha256(p.read_bytes()).hexdigest()
                        for p in class_root.rglob('*.class')}
(run / 'result.json').write_text(json.dumps(result, indent=2))
print('DISTANT_GEN_SMOKE artifacts: ' + str(run), flush=True)
start = time.monotonic()
with (run / 'client.log').open('w', encoding='utf-8') as log:
    proc = subprocess.Popen([spec['executable'], '@' + str(argfile)], cwd=work, env=env,
        stdout=log, stderr=subprocess.STDOUT, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    try:
        code = proc.wait(timeout=args.timeout)
    except subprocess.TimeoutExpired:
        try:
            with (run / 'timeout-threads.txt').open('w') as threads:
                subprocess.run([str(Path(spec['executable']).with_name('jcmd.exe' if os.name == 'nt' else 'jcmd')),
                                str(proc.pid), 'Thread.print'], stdout=threads, stderr=subprocess.STDOUT, timeout=15)
        except (OSError, subprocess.TimeoutExpired) as error:
            result['threadDumpError'] = str(error)
        finally:
            proc.terminate()
            proc.wait(timeout=15)
        code = 'timeout'
    except BaseException:
        proc.terminate()
        proc.wait(timeout=15)
        raise
result.update(exitCode=code, elapsedSeconds=round(time.monotonic() - start, 2))
console = (run / 'client.log').read_text(encoding='utf-8', errors='replace')
errors = [line for line in console.splitlines() if '/ERROR]' in line]
result['errors'] = errors
complete = json.loads((run / 'complete.json').read_text()) if (run / 'complete.json').is_file() else {}
expected_pipeline = 'IrisVoxyRenderPipeline' if args.shaders else 'NormalRenderPipeline'
result['checks'] = {
    'processExited': code == 0,
    'serverStopped': complete.get('serverStopped', False),
    'generatorsCleared': complete.get('generatorsCleared', False),
    'ticketsReleased': complete.get('ticketsReleased', False),
    'rendererReleased': 'Render shutdown completed' in console,
    'instanceReleased': 'Instance shutdown' in console,
    'expectedPipeline': complete.get('pipeline') == expected_pipeline,
    'generationWasPending': complete.get('pendingBeforeExit', 0) > 0,
    'retryYielded': not args.force_unload_retry or complete.get('unloadRetryYielded', False),
    'noErrors': not errors,
}
if args.without_voxy:
    result['checks'] = {
        'processExited': code == 0,
        'serverStopped': complete.get('serverStopped', False),
        'voxyAbsent': complete.get('voxyAbsent', False),
        'worldExercised': complete.get('worldTicks', 0) >= 180,
        'localCloseRaceInjected': not args.force_local_close_race or complete.get('forcedLocalCloseRace', False),
        'noErrors': not errors,
    }
result['status'] = 'passed' if all(result['checks'].values()) else 'failed'
(run / 'result.json').write_text(json.dumps(result, indent=2))
print(json.dumps({k: v for k, v in result.items() if k != 'classSha256'}), flush=True)
assert result['status'] == 'passed', f'Client verification failed; see {run / "client.log"}'
