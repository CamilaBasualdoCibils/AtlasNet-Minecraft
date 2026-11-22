#!/usr/bin/env python3
"""Run two real Fabric clients across the example.xsplit2d boundary in superflat."""
import argparse
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--seconds', type=int, default=90)
parser.add_argument('--label', default='run')
parser.add_argument('--allow-failure', action='store_true')
parser.add_argument('--skip-build', action='store_true', help='Use already prepared test classes and native libraries')
parser.add_argument('--verify-settled', action='store_true', help='Omit resting updates and verify reliable recovery')
args = parser.parse_args()
directory = ROOT / 'build/migration-test' / args.label
directory.mkdir(parents=True, exist_ok=False)
env = dict(os.environ, JAVA_HOME='/usr/lib/jvm/java-21-openjdk',
           GRADLE_USER_HOME=str(ROOT/'.tools/gradle'),
           JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx1800m',
           MINECRAFT_TERRAIN_PRESET='flat', MINECRAFT_VALKEY_PORT='17472',
           MINECRAFT_VALKEY_DIR=str(directory/'terrain'),
           MINECRAFT_TERRAIN_SESSION=str(uuid.uuid4()),
           ATLASNET_MINECRAFT_VALKEY_TERRAIN='partitioned',
           ATLASNET_MINECRAFT_CLIENT_LIBRARY=str(ROOT/'build/native/libAtlasNetMinecraftClient.so'))
processes = []
def interrupted(signum, frame):
    raise SystemExit(128+signum)
signal.signal(signal.SIGTERM, interrupted)
signal.signal(signal.SIGINT, interrupted)

def launch(command, cwd, name, stdin=False):
    path = directory / (name+'.log')
    with path.open('w') as output:
        process = subprocess.Popen(list(map(str,command)), cwd=cwd, env=env, stdout=output,
            stderr=subprocess.STDOUT, stdin=subprocess.PIPE if stdin else subprocess.DEVNULL,
            start_new_session=True)
    processes.append(process)
    (directory/(name+'.pid')).write_text(str(process.pid)+'\n')
    return process,path

def read(path):
    return path.read_text(errors='replace') if path.exists() else ''

def wait(test, description, timeout=120):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        if value:=test():
            return value
        for process in processes:
            if process.poll() is not None:
                raise RuntimeError(f'{description}: process {process.pid} exited {process.returncode}')
        time.sleep(.2)
    raise RuntimeError(description)

def samples(i):
    path=directory/f'client-{i}/migration-samples.jsonl'
    lines=read(path).splitlines()
    result=[]
    for line in lines:
        try: result.append(json.loads(line))
        except json.JSONDecodeError: pass
    return result

def command(i, text):
    path=directory/f'client-{i}/migration-command'
    temporary=path.with_suffix('.tmp')
    temporary.write_text(text)
    temporary.replace(path)

def analyze():
    walking=[r for r in samples(0) if r['active']]
    observing=[r for r in samples(1) if r['active']]
    assert walking and observing
    backwards=sum(b['step'] * b['direction'] < -.1
                  for a,b in zip(walking[5:],walking[6:]) if a['direction']==b['direction'])
    remote_stalls=[]
    handoff_stalls=[]
    current=0.
    observer_backwards=0
    max_handoff_step=0.
    walker_index=0
    for a,b in zip(observing,observing[1:]):
        while walker_index+1 < len(walking) and walking[walker_index+1]['time'] <= b['time']:
            walker_index+=1
        step=b['remoteX']-a['remoteX']
        if abs(a['remoteX']) < 8 and abs(b['remoteX']) < 8:
            observer_backwards += step * walking[walker_index]['direction'] < -.05
            max_handoff_step=max(max_handoff_step,abs(step))
        if abs(b['remoteX']-a['remoteX']) < .002:
            current += min(.5,(b['time']-a['time'])/1000)
        else:
            if current:
                remote_stalls.append(current)
                if abs(a['remoteX']) < 8:
                    handoff_stalls.append(current)
            current=0.
    if current:
        remote_stalls.append(current)
        if abs(observing[-1]['remoteX']) < 8:
            handoff_stalls.append(current)
    shard_changes=[]
    for a,b in zip(walking,walking[1:]):
        if a['shard']!=b['shard']: shard_changes.append(dict(time=b['time'],x=b['x'],shard=b['shard']))
    ticks={}
    gaps={}
    for i in range(2):
        log=read(directory/f'server-{i}.log')
        def timed_values(kind):
            return [float(value) for value,timestamp in re.findall(
                rf'MIGRATION_TEST {kind}=([\d.]+) time=(\d+)', log)
                if walking[0]['time'] <= int(timestamp) <= walking[-1]['time']]
        ticks[str(i)]=timed_values('tick_ms')
        gaps[str(i)]=timed_values('tick_gap_ms')
    report=dict(seconds=(walking[-1]['time']-walking[0]['time'])/1000,
        shardChanges=shard_changes, handoffs=walking[-1]['handoffs'],
        positionPackets=walking[-1]['positionPackets'],
        handoffCorrections=walking[-1].get('handoffCorrections',0),
        backwardsSteps=backwards,
        maxClientTickGapMs=max(r['tickGapMs'] for r in walking),
        maxObserverClientTickGapMs=max(r['tickGapMs'] for r in observing),
        observerMissing=sum(r['remoteCount']!=1 for r in observing),
        observerIds=sorted(set(r['remoteId'] for r in observing)),
        observerBackwardsSteps=observer_backwards,
        maxObserverHandoffStep=max_handoff_step,
        maxObserverHandoffStallSeconds=max(handoff_stalls,default=0),
        maxObserverStallSeconds=max(remote_stalls,default=0), slowServerTicksMs=ticks, serverTickGapsMs=gaps,
        loadingScreens=sum(r['loading'] for r in walking),
        worldResets=sum(not r['sameWorld'] or not r['samePlayer'] for r in walking))
    (directory/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2),flush=True)
    passed=(len(shard_changes)>=2 and report['backwardsSteps']==0 and report['handoffCorrections']==0 and
        all(change['shard'] and abs(change['x'])<8 for change in shard_changes) and
        report['observerMissing']==0 and len(report['observerIds'])==1 and
        report['maxObserverHandoffStallSeconds']<.15 and report['observerBackwardsSteps']==0
        and report['maxObserverHandoffStep']<.4
        and report['maxClientTickGapMs']<150
        and report['maxObserverClientTickGapMs']<150
        and report['loadingScreens']==0 and report['worldResets']==0
        and all(max(times,default=0)<200 for times in ticks.values())
        and all(max(times,default=0)<200 for times in gaps.values()))
    print('Migration acceptance: '+('PASS' if passed else 'FAIL'), flush=True)
    if not passed and not args.allow_failure:
        raise RuntimeError('Migration acceptance checks failed; inspect report.json and logs')

try:
    if not args.skip_build:
        build_env = dict(env)
        # javac -version is parsed by CMake; JAVA_TOOL_OPTIONS prepends a banner.
        build_env.pop('JAVA_TOOL_OPTIONS',None)
        with (directory/'build.log').open('w') as output:
            for build in (['cmake','--preset','minecraft'],
                          ['cmake','--build','build','--target','BuildServer','BuildClient','--parallel','2'],
                          ['./gradlew','--offline','-PatlasnetMigrationTest',
                           ':fabric:migrationTestClasses',':fabric:prepareShardServer']):
                subprocess.run(build,cwd=ROOT,env=build_env,stdout=output,stderr=subprocess.STDOUT,check=True)
    subprocess.run(['node','tests/integration/prepare-migration.cjs',str(directory)],cwd=ROOT,env=env,check=True)
    subprocess.run(['python3','scripts/minecraft-valkey.py','start'],cwd=ROOT,env=env,check=True)
    database,*nodes=json.loads((directory/'launch.json').read_text())
    cluster=launch([database['program'],*database['args']],database['cwd'],'cluster')
    wait(lambda:'Ready to accept' in read(cluster[1]),'cluster database ready',10)
    servers=[]
    for i,node in enumerate(nodes):
        servers.append(launch([node['program'],*node['args']],node['cwd'],f'server-{i}',True))
        time.sleep(.5)
    for server in servers:
        wait(lambda:'Done (' in read(server[1]),'Minecraft shard simulation ready',160)
    for process,_ in servers:
        for text in ('difficulty peaceful','gamerule doMobSpawning false','gamerule doDaylightCycle false'):
            process.stdin.write((text+'\n').encode())
        process.stdin.flush()
    for i in range(2):
        game=directory/f'client-{i}'
        game.mkdir()
        (game/'options.txt').write_text('pauseOnLostFocus:false\nmaxFps:45\nrenderDistance:3\nsimulationDistance:3\n')
        launch(['./gradlew','--offline','--no-daemon','-PatlasnetMigrationTest',':fabric:runClient',
                f'--args=--quickPlayMultiplayer 127.0.0.1:{5971+i} --gameDir {game}'],
               ROOT,f'client-{i}')
        wait(lambda:samples(i) and samples(i)[-1]['shard'] and
             samples(i)[-1]['screen']=='',f'Fabric client {i} ready',120)
    wait(lambda:all(samples(i)[-1]['remoteCount']==1 and samples(i)[-1]['grounded'] for i in range(2)),
         'both real clients see each other and stand on flat terrain',60)
    time.sleep(4)
    command(1,'start-observer')
    command(0,'start-walker')
    print('Real walking started across x=0; measuring both clients.',flush=True)
    end=time.monotonic()+args.seconds
    captured=False
    while time.monotonic()<end:
        wait(lambda:all(p.poll() is None for p in processes),'process health',1)
        time.sleep(.5)
        if not captured and samples(0)[-1]['handoffs'] > 0:
            command(0,'capture')
            command(1,'capture')
            captured=True
    analyze()
    if args.verify_settled:
        command(1,'drop-settled')
        wait(lambda:(directory/'client-1/migration-drop-ready').exists(),'loss probe ready',5)
        command(0,'stop')
        recovered=directory/'client-1/migration-settled.json'
        wait(lambda:recovered.exists(),'reliable resting pose recovers omitted update',8)
        report=json.loads((directory/'report.json').read_text())
        report['settledPoseRecovery']=json.loads(recovered.read_text())
        (directory/'report.json').write_text(json.dumps(report,indent=2)+'\n')
        print('Reliable resting pose recovered the deliberately omitted update.',flush=True)
    command(0,'stop')
    command(1,'stop')
finally:
    for process in reversed(processes):
        if process.poll() is None:
            try: os.killpg(process.pid,signal.SIGKILL)
            except ProcessLookupError: pass
            process.wait(timeout=10)
    subprocess.run(['python3','scripts/minecraft-valkey.py','stop'],cwd=ROOT,env=env)
    with (directory/'restore-launch.log').open('w') as output:
        subprocess.run(['./gradlew','--offline',':fabric:prepareShardServer'],cwd=ROOT,env=env,
                       stdout=output,stderr=subprocess.STDOUT,check=True)
