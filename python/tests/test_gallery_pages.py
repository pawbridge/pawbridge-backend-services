import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import tracemalloc
import unittest
from pathlib import Path
from unittest.mock import Mock

import httpx

from app.gallery.pages import PagedGallerySource, PROTOCOL, INITIAL_CHAIN, advance, PAGE_BYTES
from app.gallery.builder import build_gallery
from app.vision.sam3_focus import FOCUS_VERSION
from tests.fake_gallery_store import FakeGalleryStore

HOST = 'test.r2.cloudflarestorage.com'
STOPPED = lambda: False


def item(identifier):
    sha = hashlib.sha256(str(identifier).encode()).hexdigest()
    return {'record': {'id': identifier, 'species': 'DOG', 'source_sha256': sha, 'status': 'PROTECT', 'description': '갈색 강아지'},
            'photo': {'sha256': sha, 'key': 'apms/photos/' + sha + '.jpg', 'bytes': 123,
                      'mime': 'image/jpeg', 'url': 'https://' + HOST + '/pawbridge-animal-originals/apms/photos/' + sha + '.jpg?signature=private'}}


class Feed:
    """Generates only the requested page, never an all-record fixture."""
    def __init__(self, count):
        self.count = count
        chain = INITIAL_CHAIN
        for identifier in range(1, count + 1):
            entry = item(identifier)
            chain = advance(chain, entry['record'], entry['photo'])
        self.descriptor = dict(protocol=PROTOCOL, snapshotId='a' * 32, snapshotSha256=chain,
                               count=count, cursor='0', expiresAt=9999999999999)
        self.requests = []
        self.expired = False
        self.mutate = lambda page: page

    def handle(self, request):
        self.requests.append((request.method, request.url.path, request.url.params.get('cursor')))
        assert request.headers['X-Internal-Api-Key'] == 'test-key'
        if request.method == 'DELETE':
            return httpx.Response(204)
        if request.method == 'POST':
            self.expired = False
            return httpx.Response(200, json=self.descriptor)
        if self.expired:
            return httpx.Response(410)
        if request.url.path.endswith('/pages'):
            start = int(request.url.params['cursor'])
            assert int(request.url.params['limit']) == 100
            end = min(start + 100, self.count)
            page = dict(protocol=PROTOCOL, snapshotId=self.descriptor['snapshotId'],
                        snapshotSha256=self.descriptor['snapshotSha256'], total=self.count, start=start,
                        items=[item(i) for i in range(start + 1, end + 1)],
                        nextCursor=str(end) if end < self.count else None, complete=end == self.count)
            return httpx.Response(200, json=self.mutate(page))
        return httpx.Response(200, json=self.descriptor)

    def source(self, root):
        return PagedGallerySource('http://127.0.0.1:18082/internal/animals/lost-gallery', 'test-key', HOST,
                                  root, Path(root) / 'photos', httpx.Client(transport=httpx.MockTransport(self.handle)))


class GalleryPagesTest(unittest.TestCase):
    def test_fingerprint_matches_java_protocol_fixture_including_unicode_and_nulls(self):
        row = dict(id=7, species='DOG', source_sha256='a'*64, status='PROTECT', happen_date='2026-09-15',
                   happen_place='서울', color='갈색', special_mark=None, description='')
        photo = dict(key='apms/photos/'+'a'*64+'.jpg', bytes=123, mime='image/jpeg')
        self.assertEqual(advance(INITIAL_CHAIN, row, photo), '25f0beaba9ab422cfe17d3be38b2555abd41182ce0885c67debd4e71d43d5dc3')

    def test_lost_creation_response_reuses_request_id_across_process_restart(self):
        with tempfile.TemporaryDirectory() as root:
            seen = []
            def unavailable(request):
                seen.append(request.headers['X-Gallery-Request-Id'])
                return httpx.Response(400)
            source = PagedGallerySource('https://feed.example/gallery', 'test-key', HOST, root, Path(root)/'photos',
                httpx.Client(transport=httpx.MockTransport(unavailable)))
            with self.assertRaises(httpx.HTTPStatusError): source.fetch(None, STOPPED)
            source.close()
            source = PagedGallerySource('https://feed.example/gallery', 'test-key', HOST, root, Path(root)/'photos',
                httpx.Client(transport=httpx.MockTransport(unavailable)))
            with self.assertRaises(httpx.HTTPStatusError): source.fetch(None, STOPPED)
            self.assertEqual(len(seen), 2); self.assertEqual(seen[0], seen[1]); source.close()

    def test_next_page_waits_for_ack_and_restart_resumes_only_acknowledged_page(self):
        feed = Feed(203)
        with tempfile.TemporaryDirectory() as root:
            source = feed.source(root); source.fetch(None, STOPPED)
            pages = source.pages(STOPPED)
            self.assertEqual(len(next(pages)), 100)
            self.assertEqual(len(feed.requests), 2)
            self.assertEqual(source.load_checkpoint()['processed'], 0)
            source.acknowledge(100)
            self.assertEqual([r['id'] for r in next(pages)], list(range(101, 201)))
            # Simulated failure before the second bulk acknowledgment: restart at row 101.
            pages.close(); source.close()
            feed.descriptor['expiresAt'] += 1000
            resumed = feed.source(root); resumed.fetch(None, STOPPED)
            resumed_pages = resumed.pages(STOPPED)
            self.assertEqual(next(resumed_pages)[0]['id'], 101)
            resumed.acknowledge(200)
            self.assertEqual([r['id'] for r in next(resumed_pages)], [201, 202, 203])
            resumed.acknowledge(203)
            self.assertEqual(list(resumed_pages), [])
            persisted = resumed.checkpoint_path.read_text()
            self.assertNotIn('signature', persisted); self.assertNotIn('test-key', persisted)
            self.assertNotIn('https:', persisted)
            resumed.finish(STOPPED)
            self.assertFalse(resumed.checkpoint_path.exists()); resumed.close()

    def test_expired_snapshot_restarts_from_zero_instead_of_mixing_snapshots(self):
        feed = Feed(101)
        with tempfile.TemporaryDirectory() as root:
            source = feed.source(root); source.fetch(None, STOPPED)
            pages = source.pages(STOPPED); next(pages); source.acknowledge(100); pages.close(); source.close()
            feed.expired = True; feed.descriptor['snapshotId'] = 'b' * 32
            resumed = feed.source(root); resumed.fetch(None, STOPPED)
            self.assertEqual(resumed.processed, 0)
            self.assertEqual(resumed.checkpoint['descriptor']['snapshotId'], 'b' * 32)
            resumed.close()

    def test_invalid_page_never_advances_checkpoint(self):
        def wrong_id(page): page['snapshotId'] = 'b' * 32
        def duplicate(page): page['items'][1] = copy.deepcopy(page['items'][0])
        def changed_color(page): page['items'][0]['record']['color'] = 'changed'
        def premature_end(page): page['complete'] = False
        def untrusted_url(page): page['items'][0]['photo']['url'] = 'https://evil.example/photo'
        for corrupt in (wrong_id, duplicate, changed_color, premature_end, untrusted_url):
            with self.subTest(corrupt=corrupt.__name__), tempfile.TemporaryDirectory() as root:
                feed = Feed(2)
                def mutate(page): corrupt(page); return page
                feed.mutate = mutate
                source = feed.source(root); source.fetch(None, STOPPED)
                with self.assertRaises(ValueError): next(source.pages(STOPPED))
                self.assertEqual(source.load_checkpoint()['processed'], 0)
                source.close()

    def test_url_refresh_reads_only_current_page_and_rejects_changed_metadata(self):
        feed = Feed(101)
        with tempfile.TemporaryDirectory() as root:
            source = feed.source(root); source.fetch(None, STOPPED)
            pages = source.pages(STOPPED); next(pages)
            source.renew_urls(STOPPED)
            self.assertEqual(feed.requests[-1][2], '0')
            self.assertEqual(sum(method == 'POST' for method, _, _ in feed.requests), 1)
            self.assertEqual(len(source.inventory), 100)
            def changed(page): page['items'][0]['record']['description'] = 'changed'; return page
            feed.mutate = changed
            with self.assertRaises(ValueError): source.renew_urls(STOPPED)
            pages.close(); source.close()

    def test_download_limit_rejects_oversized_response_before_json_decode(self):
        with tempfile.TemporaryDirectory() as root:
            source = PagedGallerySource('https://feed.example/gallery', 'test-key', HOST, root, Path(root)/'photos',
                httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(200, content=b'x'*(PAGE_BYTES+1)))))
            with self.assertRaisesRegex(ValueError, 'limit'): source.request('GET', '', STOPPED)
            source.close()

    def test_builder_bulk_failure_keeps_alias_and_acknowledged_cursor_then_retry_publishes(self):
        feed = Feed(101)
        with tempfile.TemporaryDirectory() as root:
            source = feed.source(root); source.fetch(None, STOPPED)
            old = 'animals-lost-dinov3-sam3-build-' + 'a' * 24
            alias = 'animals-lost-dinov3-sam3-test'
            vector = [1.] + [0.] * 1023
            store = FakeGalleryStore()
            store.seed(old, [dict(item(i)['record'], model_version=FOCUS_VERSION, image_vector=vector,
                                  focus_status='original_no_confident_animal') for i in range(1, 102)],
                       alias=alias)
            calls = []
            write_page = store.write_page
            def flaky_write(target, documents):
                calls.append(len(documents))
                if len(calls) == 2:
                    raise RuntimeError('bulk write failed')
                write_page(target, documents)
            store.write_page = flaky_write
            store.publish = Mock(wraps=store.publish)
            encoder = Mock(model_version=FOCUS_VERSION)
            with self.assertRaisesRegex(RuntimeError, 'bulk'):
                build_gallery(store, lambda: encoder, None, source.photo_root, alias, root, stream=source)
            self.assertEqual(source.load_checkpoint()['processed'], 100)
            self.assertEqual(store.heads[alias], old)
            store.publish.assert_not_called()
            source.close(); source = feed.source(root); source.fetch(None, STOPPED)
            result = build_gallery(store, lambda: encoder, None, source.photo_root, alias, root, stream=source)
            self.assertEqual(calls, [100, 1, 1])
            self.assertEqual(result['records'], 101); self.assertEqual(store.heads[alias], result['index'])
            self.assertEqual(len(store.builds[result['index']]['documents']), 101)
            encoder.encode_with_metadata.assert_not_called(); source.close()

    def test_generated_large_feeds_keep_page_memory_bounded(self):
        # 측정은 새 파이썬 프로세스에서 한다. 같은 프로세스에서 앞선 시험이 해석기 전역 표(문자열 intern 표 등)를
        # 채워 두면, 그 표가 커지는 순간(한 번에 약 2MB)이 측정 구간에 걸려 갤러리 코드와 무관하게 실패한다.
        code = ('import json; from tests.test_gallery_pages import measure_page_memory; '
                f'print(json.dumps([measure_page_memory(count) for count in {PAGED_MEMORY_COUNTS!r}]))')
        result = subprocess.run([sys.executable, '-c', code], cwd=Path(__file__).resolve().parents[1],
                                capture_output=True, text=True, timeout=300)
        self.assertEqual(result.returncode, 0, result.stderr)
        measurements = json.loads(result.stdout.strip().splitlines()[-1])
        for measured in measurements:
            with self.subTest(count=measured['count']):
                self.assertEqual(measured['processed'], measured['count'])
                self.assertLessEqual(measured['max_inventory'], 100)
                self.assertLess(measured['peak'], 8 * 1024 * 1024)
                print(f"PAGED_MEMORY count={measured['count']} peak_python_bytes={measured['peak']} "
                      f"max_inventory={measured['max_inventory']}")
        peaks = [measured['peak'] for measured in measurements]
        self.assertLess(max(peaks)-min(peaks), 2*1024*1024)


PAGED_MEMORY_COUNTS = (5000, 37605, 100000)


def measure_page_memory(count):
    """count건 목록을 페이지로 받아 처리하는 동안 파이썬 할당 최대값을 잰다. 시험이 새 프로세스에서 부른다."""
    with tempfile.TemporaryDirectory() as root:
        feed = Feed(count)
        source = feed.source(root); source.fetch(None, STOPPED)
        tracemalloc.start()
        max_inventory = 0
        for rows in source.pages(STOPPED):
            max_inventory = max(max_inventory, len(source.inventory))
            source.acknowledge(source.processed + len(rows))
            # The fixture request audit must not become the full-run memory accumulation.
            feed.requests.clear()
        _, peak = tracemalloc.get_traced_memory(); tracemalloc.stop()
        processed = source.processed
        source.close()
    return {'count': count, 'processed': processed, 'max_inventory': max_inventory, 'peak': peak}
