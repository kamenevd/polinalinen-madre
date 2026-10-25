"""Subprocess fake-gh contracts plus real filesystem/lock/ref admission tests."""
import contextlib
import io
import fcntl
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]

def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / (name + '.py'))
    if spec is None or spec.loader is None:
        raise RuntimeError('missing fixture module')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module

ci = load('ci_wait')
ops = load('madre_ci')
SHA = 'a' * 40

FAKE_GH = '''#!/usr/bin/env python3
import json,os,sys,time
args=sys.argv[1:]; mode=os.environ['FAKE_MODE']; sha='a'*40
if mode=='transport':sys.exit(9)
if args[0]=='api':
 assert '--repo' not in args and '--event' not in args and '--commit' not in args
 assert 'head_sha='+sha in args[1]
 print(json.dumps({'workflow_runs':[] if mode in ['no-run','discover-timeout'] else [{'id':99,'head_sha':'b'*40,'event':'workflow_dispatch'},{'id':42,'head_sha':sha,'event':'pull_request','status':'completed','conclusion':'success'}]}))
elif args[:2]==['run','watch']:
 if mode=='timeout':time.sleep(3)
 sys.exit(7 if mode=='watch-error' else 0)
elif '--log-failed' in args:
 print('job\\told step\\tDO-NOT-PRINT')
 for i in range(100):print('job\\tfailed step\\tline-'+str(i))
else:
 jobs=[{'databaseId':1,'name':name,'status':'completed','conclusion':'success'} for name in ['Workflow integrity','Android quality']]
 if mode=='missing':jobs.pop()
 if mode=='job-fail':jobs[1]['conclusion']='failure'
 if mode=='failure':jobs[1]['conclusion']='failure'
 print(json.dumps({'databaseId':42,'headSha':'b'*40 if mode=='mismatch' else sha,'status':'in_progress' if mode=='partial' else 'completed','conclusion':{'failure':'failure','cancelled':'cancelled','remote-timeout':'timed_out'}.get(mode,'success'),'jobs':jobs}))
'''

class WaitTests(unittest.TestCase):
    def run_mode(self, mode, code):
        with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as tmp:
            p=Path(tmp); gh=p/'gh';gh.write_text(FAKE_GH);gh.chmod(0o755)
            with patch.dict(os.environ, {'PATH':str(p)+os.pathsep+os.environ['PATH'],'FAKE_MODE':mode}):
                waiter=ci.Waiter(SHA,'owner/repo',p/'evidence',seconds=.25 if mode in ['no-run','timeout'] else 5, gh=str(gh))
                started=time.monotonic()
                output=io.StringIO()
                with contextlib.redirect_stdout(output):result=waiter.wait()
                if mode=='failure':
                    lines=[line for line in output.getvalue().splitlines() if '\tfailed step\t' in line]
                    self.assertEqual(len(lines),60)
                    self.assertTrue(lines[0].endswith('line-40'))
                    self.assertNotIn('DO-NOT-PRINT',output.getvalue())
                self.assertEqual(result,code)
                receipt=json.loads((p/'evidence/receipt.json').read_text())
                self.assertEqual(receipt['exit_code'],code)
                self.assertLess(time.monotonic()-started,6)
                self.assertTrue(list((p/'evidence').glob('*.log')))
    def test_success(self):self.run_mode('success',0)
    def test_failure(self):self.run_mode('failure',1)
    def test_cancel(self):self.run_mode('cancelled',2)
    def test_timeout(self):self.run_mode('timeout',3)
    def test_remote_timeout(self):self.run_mode('remote-timeout',3)
    def test_no_run(self):self.run_mode('no-run',4)
    def test_transport(self):self.run_mode('transport',5)
    def test_mismatch(self):self.run_mode('mismatch',6)
    def test_watch_error(self):self.run_mode('watch-error',5)
    def test_exit_zero_partial(self):self.run_mode('partial',5)
    def test_missing_required(self):self.run_mode('missing',1)
    def test_failed_required(self):self.run_mode('job-fail',1)
    def test_reject_short_sha(self):
        p=subprocess.run([sys.executable,str(SCRIPTS/'ci_wait.py'),'abc'],capture_output=True)
        self.assertNotEqual(p.returncode,0)

class AdmissionTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR'));self.addCleanup(self.tmp.cleanup)
        # Fixtures must not acquire the production root-owned lock on GitHub's
        # non-root runner. Keep the real flock implementation with a private path.
        original_locked = ops.locked
        fixture_lock = Path(self.tmp.name) / 'admission.lock'
        lock_patch = patch.object(ops, 'locked', side_effect=lambda path=None, nonblock=False: original_locked(path if path is not None else fixture_lock, nonblock))
        lock_patch.start(); self.addCleanup(lock_patch.stop)
        self.repo=Path(self.tmp.name).resolve()/'madre-fixture';self.repo.mkdir()
        subprocess.run(['git','init','-q',str(self.repo)],check=True)
        for key,value in [('user.email','fixture@example.test'),('user.name','Fixture')]:subprocess.run(['git','config',key,value],cwd=self.repo,check=True)
        (self.repo/'tracked').write_text('source')
        subprocess.run(['git','add','.'],cwd=self.repo,check=True);subprocess.run(['git','commit','-qm','fixture'],cwd=self.repo,check=True)
        self.head=ops.git('rev-parse','HEAD',cwd=self.repo)
    def ref(self,remote='refs/heads/ci/test',sha=None):return 'refs/heads/ci/test '+(sha or self.head)+' '+remote+' '+'0'*40+'\n'
    def test_worker_ref(self):ops.refs_allowed(self.repo,self.ref(),self.head)
    def test_feature_denied_before_build(self):
        with patch.object(ops,'gradle') as build:
            with self.assertRaises(RuntimeError):ops.gate(self.repo,self.ref(ops.FEATURE))
            build.assert_not_called()
    def test_main_denied(self):
        with self.assertRaises(RuntimeError):ops.refs_allowed(self.repo,self.ref('refs/heads/main'),self.head)
    def test_ref_sha_and_delete_denied(self):
        for sha in ['b'*40,'0'*40]:
            with self.assertRaises(RuntimeError):ops.refs_allowed(self.repo,self.ref(sha=sha),self.head)
    def test_dirty(self):
        (self.repo/'tracked').write_text('dirty')
        with self.assertRaises(RuntimeError):ops.clean_head(self.repo,self.head)
    def test_untracked(self):
        (self.repo/'untracked').write_text('dirty')
        with self.assertRaises(RuntimeError):ops.clean_head(self.repo,self.head)
    def test_stale(self):
        with self.assertRaises(RuntimeError):ops.clean_head(self.repo,'b'*40)
    def test_inflight(self):
        (self.repo/'.git/CHERRY_PICK_HEAD').write_text(self.head)
        with self.assertRaises(RuntimeError):ops.clean_head(self.repo,self.head)
    def test_failed_build_blocks(self):
        with patch.object(ops,'_run_gradle',return_value=7):
            with self.assertRaises(RuntimeError):ops.gate(self.repo,self.ref())
        receipt=list((self.repo/'.git/madre-ci').glob('gate-*/receipt.json'))
        self.assertEqual(json.loads(receipt[0].read_text())['gradle_exit'],7)
    def test_cached_no_junit_blocks(self):
        with patch.object(ops,'_run_gradle',return_value=0):
            with self.assertRaises(RuntimeError):ops.gate(self.repo,self.ref())
    def test_shared_flock_serializes(self):
        lock=Path(self.tmp.name)/'global.lock'
        code="import fcntl,sys;f=open(sys.argv[1],'a');fcntl.flock(f,fcntl.LOCK_EX);print('acquired',flush=True)"
        with ops.locked(lock):
            p=subprocess.Popen([sys.executable,'-c',code,str(lock)],stdout=subprocess.PIPE,text=True)
            time.sleep(.1);self.assertIsNone(p.poll())
            with self.assertRaises(BlockingIOError):
                with ops.locked(lock,nonblock=True):pass
        self.assertEqual(p.communicate(timeout=2)[0].strip(),'acquired');self.assertEqual(p.returncode,0)
    def test_resource_policy(self):
        for flag in ['--no-daemon','--no-build-cache','--max-workers=9','-Dorg.gradle.jvmargs=override']:
            with self.assertRaises(RuntimeError):ops.gradle(self.repo,[flag])
    def test_config_install_preserves_dirty_and_real_wrapper_location(self):
        # Real subprocess wrapper emulates APP_HOME: relocated backup cannot work.
        (self.repo/'app').mkdir(); (self.repo/'app/marker').write_text('android'); (self.repo/'gradle').mkdir()
        (self.repo/'gradle/marker').write_text('here')
        (self.repo/'gradlew').write_text('#!/bin/sh\ntest -f "$(dirname "$0")/gradle/marker" || exit 19\n')
        (self.repo/'scripts').mkdir()
        for name in ['madre_ci.py','pre-push','madre-gradle.sh']:
            (self.repo/'scripts'/name).write_bytes((SCRIPTS/name).read_bytes())
        subprocess.run(['git','add','.'],cwd=self.repo,check=True)
        subprocess.run(['git','commit','-qm','wrapper fixture'],cwd=self.repo,check=True)
        head=ops.git('rev-parse','HEAD',cwd=self.repo)
        dirty=Path(self.tmp.name).resolve()/'madre-dirty'
        subprocess.run(['git','clone','-q',str(self.repo),str(dirty)],check=True)
        (dirty/'tracked').write_text('dirty bytes')
        (dirty/'untracked').write_bytes(b'keep\x00exact')
        before=ops.source_snapshot(dirty)
        inventory=Path(self.tmp.name)/'inventory.json';inventory.write_text('[]')
        with patch.object(ops,'INTEGRATOR',dirty), patch.object(ops,'WORK',dirty.parent), patch.object(ops,'INSTALL_ROOT',Path(self.tmp.name)/'install'), patch.object(ops,'build_quiescent'):
            ops.install(self.repo,head,inventory)
        self.assertEqual(ops.source_snapshot(dirty),before)
        self.assertEqual(ops.git('config','--get','madre.rawGradlew',cwd=dirty),str(dirty/'gradlew'))
        self.assertEqual(ops._run_gradle(dirty,['help']),0)
        self.assertNotEqual(subprocess.run(['bash',str(dirty/'.git/madre-ci/gradlew.original')],cwd=dirty).returncode,0)
        self.assertEqual((dirty/'.git/madre-ci/source.before.json').read_bytes(),(dirty/'.git/madre-ci/source.after.json').read_bytes())
        with self.assertRaises(RuntimeError):ops.clean_head(dirty,head)
    def test_backend_exclusion_even_with_android_tree(self):
        inventory=Path(self.tmp.name)/'inventory.json'
        backend=[]
        for prefix in ops.BACKEND_PREFIXES:
            p=Path(self.tmp.name)/(prefix+'fixture');p.mkdir();(p/'app').mkdir()
            backend.append({'path':str(p)})
        (self.repo/'app').mkdir();(self.repo/'gradlew').write_text('')
        inventory.write_text(json.dumps(backend))
        with patch.object(ops,'INTEGRATOR',self.repo), patch.object(ops,'WORK',self.repo.parent):
            targets,excluded=ops.installation_targets(inventory)
        self.assertEqual(targets,[self.repo]);self.assertEqual(len(excluded),5)
    def test_dirty_manual_build_denied_before_gradle(self):
        (self.repo/'tracked').write_text('dirty')
        with patch.object(ops,'_run_gradle') as build:
            with self.assertRaises(RuntimeError):ops.gradle(self.repo,['help'])
            build.assert_not_called()
    def test_existing_hook_configuration_denied(self):
        subprocess.run(['git','config','core.hooksPath','/custom/hooks'],cwd=self.repo,check=True)
        with self.assertRaises(RuntimeError):ops.install_preflight(self.repo)
    def test_install_rejects_unknown_hooks_and_inflight(self):
        hook=self.repo/'.git/hooks/pre-push';hook.write_text('custom')
        with self.assertRaises(RuntimeError):ops.install_preflight(self.repo)
        hook.unlink();(self.repo/'.git/MERGE_HEAD').write_text(self.head)
        with self.assertRaises(RuntimeError):ops.install_preflight(self.repo)
    def test_idle_daemon_allowed_busy_refused(self):
        # /proc inspection remains real; fake only Gradle's status subprocess.
        live=[]
        for proc in Path('/proc').iterdir():
            try:
                if proc.name.isdigit() and b'org.gradle.launcher.daemon.bootstrap.GradleDaemon' in (proc/'cmdline').read_bytes(): live.append(proc.name)
            except (FileNotFoundError,PermissionError,ProcessLookupError):pass
        status='\n'.join(pid+' IDLE 9.6.1' for pid in live)
        with patch.object(ops.subprocess,'run',return_value=subprocess.CompletedProcess([],0,stdout=status)):
            ops.build_quiescent([self.repo])
        with patch.object(ops.subprocess,'run',return_value=subprocess.CompletedProcess([],0,stdout='123 BUSY 9.6.1')):
            with self.assertRaisesRegex(RuntimeError,'BUSY'):ops.build_quiescent([self.repo])

    def test_integrate_wrong_workspace(self):
        with self.assertRaises(RuntimeError):ops.integrate(self.repo,self.head,'ff',[self.head])

if __name__=='__main__':unittest.main()
