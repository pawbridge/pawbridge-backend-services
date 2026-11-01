import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import mac_background_jobs as jobs
import mac_backup_job as backup


class ScheduledJobsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = dict(name='backup-test', command=[sys.executable, '-c', 'print("ok")'],
                           cwd=str(self.root), state=str(self.root/'state'), timeout=.2, interval=1, stale=2)

    def tearDown(self):
        self.temp.cleanup()

    def test_success_is_durable_and_not_repeated_before_due(self):
        self.assertEqual(jobs.run_due(self.config), 'success')
        before = jobs.read(self.root/'state/status.json')
        self.assertEqual(jobs.run_due(self.config), 'not-due')
        self.assertEqual(jobs.read(self.root/'state/status.json'), before)
        self.assertEqual(jobs.job_health(self.config), 'ok')
        self.assertEqual(jobs.job_health(self.config, now=before['lastSuccess']+3), 'stale')

    def test_failure_preserves_previous_success_and_is_unhealthy(self):
        jobs.run_due(self.config)
        path=self.root/'state/status.json'; before=jobs.read(path)
        self.config['command']=[sys.executable,'-c','raise SystemExit(7)']
        self.assertEqual(jobs.run_due(self.config, now=before['lastAttempt']+1), 'failed')
        after=jobs.read(path)
        self.assertEqual(after['lastSuccess'],before['lastSuccess'])
        self.assertEqual(after['exitCode'],7)
        self.assertEqual(jobs.job_health(self.config,now=after['lastAttempt']+1),'failed')

    def test_timeout_stops_owned_child_and_records_failure(self):
        self.config['command']=[sys.executable,'-c','import time;time.sleep(20)']
        self.assertEqual(jobs.run_due(self.config),'failed')
        status=jobs.read(self.root/'state/status.json')
        self.assertEqual(status['errorType'],'TimeoutExpired')
        self.assertIsNotNone(status['exitCode'])

    def test_parallel_owner_is_skipped_without_overwriting_status(self):
        import fcntl
        root=jobs.private_directory(self.config['state'])
        with (root/'owner.lock').open('a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            self.assertEqual(jobs.run_due(self.config),'busy')
            self.assertFalse((root/'status.json').exists())

    def test_missing_malformed_and_overdue_status_fail_closed(self):
        self.assertEqual(jobs.job_health(self.config),'missing-or-invalid')
        root=jobs.private_directory(self.config['state'])
        jobs.atomic(root/'status.json',dict(name='backup-test',state='running',lastAttempt=1))
        self.assertEqual(jobs.job_health(self.config,now=20),'overdue')
        (root/'status.json').write_text('{')
        self.assertEqual(jobs.job_health(self.config),'missing-or-invalid')

    def test_gallery_unchanged_feed_is_fresh_but_failure_stopped_and_stale_are_not(self):
        p=self.root/'gallery.json'
        state=dict(state='idle',lastSuccess='2026-10-01T00:00:00+00:00',lastChecked='2026-10-16T00:00:00+00:00')
        from datetime import datetime
        now=datetime.fromisoformat(state['lastChecked']).timestamp()+10
        jobs.atomic(p,state)
        self.assertEqual(jobs.gallery_health(p,now=now),'ok')
        self.assertEqual(jobs.gallery_health(p,now=now+4000),'stale')
        for value in ('failed','stopped'):
            jobs.atomic(p,dict(state,state=value));self.assertEqual(jobs.gallery_health(p,now=now),value)

    def test_wrong_owner_and_backward_clock_refused(self):
        jobs.run_due(self.config)
        with self.assertRaises(ValueError): jobs.run_due(self.config,now=1)
        self.config['name']='other'
        with self.assertRaises(ValueError): jobs.run_due(self.config)


class CreateOnlyBackupTest(unittest.TestCase):
    def test_complete_marker_follows_verified_ciphertext_only(self):
        self.exercise(False)

    def test_corruption_prevents_completion(self):
        self.exercise(True)

    def exercise(self,corrupt):
        objects={};calls=[]
        class Client:
            def put_object(self,**kw):
                assert kw['IfNoneMatch']=='*'
                assert kw['Key'] not in objects
                objects[kw['Key']]=kw['Body'].read();calls.append(kw['Key'])
            def get_object(self,**kw):
                data=objects[kw['Key']]
                return {'ContentLength':len(data),'Body':io.BytesIO(b'x'*len(data) if corrupt else data)}
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            for name in backup.FILES:(root/name).write_bytes(b'encrypted-fixture')
            cfg=SimpleNamespace(prefix='postgresql/v1/',bucket='pawbridge-backups')
            if corrupt:
                with self.assertRaises(ValueError):backup.upload_new(Client(),cfg,'20261016T000000Z-'+'a'*32,root)
                self.assertFalse(any(k.endswith('complete.json') for k in calls))
            else:
                result=backup.upload_new(Client(),cfg,'20261016T000000Z-'+'a'*32,root)
                self.assertEqual(len(calls),4);self.assertTrue(calls[-1].endswith('complete.json'))
                self.assertFalse(result['productionSnapshot'])
                from backup import validate_marker
                validate_marker('postgresql/v1/'+result['run']+'/complete.json',json.dumps(result).encode())



class NativeLauncherTest(unittest.TestCase):
    def test_native_sync_requires_local_feed_and_pg_and_persistent_state(self):
        from mac_native_service import validate
        with tempfile.TemporaryDirectory() as d:
            model=Path(d)/'model';model.write_bytes(b'test')
            env=dict(DINOV3_CHECKPOINT=str(model),SAM3_CHECKPOINT=str(model),INTERNAL_API_KEY='fixture',LOST_SEARCH_INDEX='animals-lost-dinov3-sam3-test',LOST_PG_DSN='postgresql://pawbridge_vector:fixture@127.0.0.1:35436/pawbridge',LOST_GALLERY_STATE_DIR=d,LOST_GALLERY_PHOTO_ROOT=d+'/photos',LOST_STORAGE_BACKEND='postgresql',LOST_SEARCH_VISUAL_PROFILE='sam3-animal-focus',LOST_GALLERY_SYNC_ENABLED='true',LOST_GALLERY_PROTOCOL='v2',LOST_GALLERY_SOURCE_URL='http://127.0.0.1:35437/internal/animals/lost-gallery',LOST_GALLERY_SOURCE_KEY='fixture',LOST_GALLERY_R2_HOST='test.r2.cloudflarestorage.com',LOST_GALLERY_INTERVAL_SECONDS='900')
            validate(env)
            for key,value in [('LOST_GALLERY_SOURCE_URL','http://production/feed'),('LOST_PG_DSN',env['LOST_PG_DSN']+'?host=unexpected'),('LOST_PG_DSN','postgresql://postgres:x@127.0.0.1:5432/pawbridge'),('LOST_GALLERY_PHOTO_ROOT','/tmp/shared'),('LOST_GALLERY_PROTOCOL','v1'),('LOST_GALLERY_SYNC_ENABLED','yes')]:
                with self.subTest(key=key),self.assertRaises(ValueError):validate(dict(env,**{key:value}))

    def test_changed_source_is_refused_before_loading_models(self):
        from mac_native_service import source_check
        import hashlib
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);source=root/'source';source.mkdir();file=source/'app.py';file.write_text('original')
            receipt=root/'receipt.json';receipt.write_text(json.dumps({'python_files':{'app.py':hashlib.sha256(file.read_bytes()).hexdigest()}}))
            source_check(source,receipt)
            file.write_text('changed')
            with self.assertRaises(ValueError):source_check(source,receipt)

    def test_receipt_covers_the_sam3_manifest_and_ignores_bytecode(self):
        from mac_native_service import source_check
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);source=root/'source';(source/'deploy/macos').mkdir(parents=True);(source/'app.py').write_text('code')
            manifest=source/'deploy/macos/sam3-manifest.json';manifest.write_text('{"patched_files": {}}')
            script=Path(__file__).with_name('mac_native_service.py')
            written=subprocess.run([sys.executable,'-B',str(script),'--receipt',str(source)],capture_output=True,text=True,check=True).stdout
            receipt=root/'receipt.json';receipt.write_text(written)
            self.assertEqual(set(json.loads(written)['files']),{'app.py','deploy/macos/sam3-manifest.json'})
            (source/'__pycache__').mkdir();(source/'__pycache__/app.cpython-312.pyc').write_bytes(b'cache')
            source_check(source,receipt)
            manifest.write_text('{"patched_files": {"changed": "0"}}')
            with self.assertRaises(ValueError):source_check(source,receipt)

class MonitorTest(unittest.TestCase):
    def test_monitor_reports_missing_job_and_persists_its_own_heartbeat(self):
        with tempfile.TemporaryDirectory() as d:
            config=dict(name='backup',command=[sys.executable,'-c','pass'],cwd=d,state=d+'/job',interval=10,timeout=1,stale=20)
            path=Path(d)/'monitor/status.json'
            result=jobs.monitor(dict(jobs={'backup':config},galleries={},output=str(path)))
            self.assertFalse(result['healthy']);self.assertEqual(result['jobs']['backup'],'missing-or-invalid')
            self.assertGreater(jobs.read(path)['checkedAt'],0)

class MonitorHeartbeatTest(unittest.TestCase):
    def test_old_healthy_report_is_not_current_health(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'status.json';jobs.atomic(path,{'checkedAt':100,'healthy':True})
            self.assertEqual(jobs.monitor_health(path,now=110),'ok')
            self.assertEqual(jobs.monitor_health(path,now=281),'monitor-stale')
            self.assertEqual(jobs.monitor_health(path,now=99),'monitor-stale')


class BackupDiskGuardTest(unittest.TestCase):
    def test_insufficient_space_refuses_capture_before_creating_run(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as d:
            cfg=Mock(workdir=d);cfg.validate.return_value='public-recipient';capture=Mock()
            with patch('mac_backup_job.shutil.disk_usage',return_value=SimpleNamespace(free=0)),self.assertRaises(ValueError):
                backup.run(cfg,client=Mock(),capture_fn=capture)
            capture.assert_not_called();self.assertEqual(list(Path(d).iterdir()),[])


class BackupLocalCopyTest(unittest.TestCase):
    def run_backup(self, workdir, upload):
        from unittest.mock import Mock
        cfg = Mock(workdir=workdir)
        cfg.validate.return_value = 'public-recipient'

        def capture(cfg, recipient, directory, run_id):
            for name in backup.FILES:
                (directory / name).write_bytes(b'encrypted-fixture')

        with patch('mac_backup_job.upload_new', side_effect=upload):
            return backup.run(cfg, client=Mock(), capture_fn=capture)

    def test_verified_run_removes_local_copy(self):
        with tempfile.TemporaryDirectory() as d:
            self.run_backup(d, lambda *args, **kwargs: {'run': 'verified'})
            self.assertEqual(list(Path(d).iterdir()), [])

    def test_failed_upload_keeps_local_copy(self):
        def fail(*args, **kwargs):
            raise ValueError('Backup integrity mismatch')

        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):
                self.run_backup(d, fail)
            runs = list(Path(d).iterdir())
            self.assertEqual(len(runs), 1)
            self.assertEqual(sorted(p.name for p in runs[0].iterdir()), sorted(backup.FILES))

if __name__=='__main__':unittest.main()
