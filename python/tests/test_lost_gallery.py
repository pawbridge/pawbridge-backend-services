import os
from contextlib import contextmanager
import hashlib
import io
from types import SimpleNamespace
from unittest.mock import Mock, patch
from PIL import Image
import json
import tempfile
import unittest
from pathlib import Path
from app.gallery.builder import read_manifest, reusable_document, build_gallery, gallery_mapping
from app.vision.sam3_focus import FOCUS_VERSION
from tests.fake_gallery_store import FakeGalleryStore

OLD_BUILD = 'animals-lost-dinov3-sam3-build-' + 'a' * 24


class LostGalleryTest(unittest.TestCase):
    def test_incomplete_empty_duplicate_or_invalid_snapshot_is_rejected(self):
        row = {"id": 1, "species": "DOG", "source_sha256": "a" * 64, "status": "PROTECT"}
        invalid = [{"complete": False, "records": [row]}, {"complete": True, "records": []},
                   {"complete": True, "records": [row, row]},
                   {"complete": True, "records": [dict(row, species="ETC")]},
                   {"complete": True, "records": [dict(row, status="INVALID")]},
                   {"complete": True, "records": [dict(row, source_sha256="../bad")]}]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            for payload in invalid:
                with self.subTest(payload=payload):
                    path.write_text(json.dumps(payload))
                    with self.assertRaises(ValueError):
                        read_manifest(path)

    def test_snapshot_hash_is_order_independent_but_includes_metadata_changes(self):
        rows = [{"id": i, "species": "DOG", "source_sha256": "a" * 64, "status": "PROTECT"} for i in [1, 2]]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            hashes = []
            for records in [rows, list(reversed(rows)), [dict(rows[0], color="white"), rows[1]]]:
                path.write_text(json.dumps({"complete": True, "records": records}))
                hashes.append(read_manifest(path)[1])
            self.assertEqual(hashes[0], hashes[1])
            self.assertNotEqual(hashes[0], hashes[2])

    def test_only_same_model_species_and_photo_can_reuse_a_valid_vector(self):
        row = {"id": 1, "species": "DOG", "source_sha256": "a" * 64, "status": "PROTECT"}
        cached = dict(row, model_version=FOCUS_VERSION, image_vector=[1.] + [0.] * 1023,
                      focus_status="original_no_confident_animal")
        self.assertTrue(reusable_document(cached, dict(row, color="updated metadata")))
        for changed in [dict(row, species="CAT"), dict(row, source_sha256="b" * 64)]:
            self.assertFalse(reusable_document(cached, changed))
        for changed in [dict(cached, model_version="old"), dict(cached, image_vector=[0.] * 1024),
                        dict(cached, animal_vector=[float("nan")] * 1024)]:
            self.assertFalse(reusable_document(changed, row))

    def test_builder_fetches_only_stale_vectors_and_preserves_alias_on_failures(self):
        data = io.BytesIO()
        Image.new('RGB', (8, 8), 'red').save(data, format='PNG')
        image_bytes = data.getvalue()
        sha = hashlib.sha256(image_bytes).hexdigest()
        vector = [1.] + [0.] * 1023
        for failure in [None, 'inference', 'bulk', 'download']:
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                rows = [dict(id=i, species='DOG', source_sha256=sha, status='PROTECT', color='updated') for i in [1, 2]]
                manifest = root / 'manifest.json'
                manifest.write_text(json.dumps(dict(complete=True, records=rows)))
                alias = 'animals-lost-dinov3-sam3-test'
                cached = dict(rows[0], color='old metadata', model_version=FOCUS_VERSION,
                              image_vector=vector, focus_status='original_no_confident_animal')
                stale = dict(rows[1], model_version='old', image_vector=vector)
                store = FakeGalleryStore()
                store.seed(OLD_BUILD, [cached, stale], alias=alias)
                store.publish = Mock(wraps=store.publish)
                if failure == 'bulk':
                    store.write_page = Mock(side_effect=RuntimeError('bulk write failed'))
                encoder = Mock(model_version=FOCUS_VERSION)
                encoder.encode_with_metadata.return_value = SimpleNamespace(model_version=FOCUS_VERSION,
                    vector=vector, animal_vector=None, focus_status='original_no_confident_animal', coat_color=None)
                if failure == 'inference': encoder.encode_with_metadata.side_effect = RuntimeError('inference failed')
                requested = []; paths = []
                @contextmanager
                def photo(row):
                    requested.append(row['id'])
                    with tempfile.TemporaryDirectory(dir=root) as temporary:
                        path = Path(temporary)/'photo.png'; path.write_bytes(image_bytes); paths.append(path)
                        if failure == 'download':
                            raise RuntimeError('download failed')
                        yield path
                def build():
                    return build_gallery(store, lambda: encoder, manifest, root, alias, root, photo_provider=photo)
                if failure:
                    with self.assertRaises(RuntimeError): build()
                    self.assertEqual(store.heads[alias], OLD_BUILD)
                    self.assertEqual(sorted(store.builds[OLD_BUILD]['documents']), [1, 2])
                    store.publish.assert_not_called()
                else:
                    result = build()
                    self.assertEqual((result['reused'], result['encoded']), (1, 1))
                    self.assertEqual(store.heads[alias], result['index'])
                    self.assertNotEqual(result['index'], OLD_BUILD)
                    published = store.builds[result['index']]['documents']
                    self.assertEqual(sorted(published), [1, 2])
                    self.assertEqual(published[1]['color'], 'updated')
                self.assertEqual(requested, [2])
                self.assertEqual(encoder.encode_with_metadata.call_count, 0 if failure == 'download' else 1)
                self.assertTrue(paths and all(not path.exists() for path in paths))

    def test_color_backfill_reuses_vectors_and_completed_colors_but_never_publishes_failure(self):
        from app.vision.coat_color import VERSION
        from tests.test_coat_color import descriptor
        color = descriptor("black")
        buffer = io.BytesIO()
        with Image.new("RGB", (120, 120), "black") as image:
            image.save(buffer, format="PNG")
        data = buffer.getvalue(); sha = hashlib.sha256(data).hexdigest()
        vector = [1.] + [0.] * 1023
        previous_version = "foreground-lab32-joint8-v2"
        for old_version, failure in ((None, False), (None, True),
                                     (previous_version, False), (previous_version, True)):
            with self.subTest(old_version=old_version, failure=failure), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                rows = [dict(id=i, species="DOG", source_sha256=sha, status="PROTECT") for i in (1, 2)]
                manifest = root / "manifest.json"
                manifest.write_text(json.dumps(dict(complete=True, records=rows)))
                (root / (sha + ".image")).write_bytes(data)
                alias = "animals-lost-dinov3-sam3-color-test"
                old_metadata = gallery_mapping("a" * 64)["_meta"]
                if old_version is None:
                    old_metadata.pop("coat_color_version")
                else:
                    old_metadata["coat_color_version"] = old_version
                cached = [dict(row, model_version=FOCUS_VERSION, image_vector=vector,
                               animal_vector=vector, focus_status="original_multiple_animals") for row in rows]
                if old_version is not None:
                    cached[0].update(coat_color_version=old_version,
                                     coat_color=dict(color, version=old_version))
                cached[1].update(coat_color_version=VERSION, coat_color=color)
                store = FakeGalleryStore()
                store.seed(OLD_BUILD, cached, metadata=old_metadata, alias=alias)
                store.publish = Mock(wraps=store.publish)
                encoder = Mock(model_version=FOCUS_VERSION)
                encoder.describe_coat_color.return_value = color
                if failure:
                    encoder.describe_coat_color.side_effect = RuntimeError("SAM failed")
                    with self.assertRaisesRegex(RuntimeError, "SAM failed"):
                        build_gallery(store, lambda: encoder, manifest, root, alias, root)
                    self.assertEqual(store.heads[alias], OLD_BUILD)
                    self.assertEqual(store.builds[OLD_BUILD]["metadata"], old_metadata)
                    store.publish.assert_not_called()
                else:
                    result = build_gallery(store, lambda: encoder, manifest, root, alias, root)
                    self.assertEqual((result["encoded"], result["reused"], result["color_processed"], result["color_available"]), (0, 2, 1, 2))
                    self.assertEqual(store.heads[alias], result["index"])
                    published = store.builds[result["index"]]
                    self.assertEqual(published["metadata"]["coat_color_version"], VERSION)
                    self.assertEqual(sorted(published["documents"]), [1, 2])
                    for document in published["documents"].values():
                        self.assertEqual(document["image_vector"], vector)
                        self.assertEqual(document["animal_vector"], vector)
                        self.assertEqual(document["coat_color"], color)
                        self.assertEqual(document["coat_color_version"], VERSION)
                encoder.encode_with_metadata.assert_not_called()
                encoder.describe_coat_color.assert_called_once()

    def test_cls_pooling_has_its_own_gallery_version_target_and_never_reuses_avg_vectors(self):
        from app.gallery.builder import gallery_target
        from app.vision.sam3_focus import FOCUS_VERSION_CLS, focus_version
        digest = "c" * 64
        row = {"id": 1, "species": "DOG", "source_sha256": "a" * 64}
        avg_document = dict(row, model_version=FOCUS_VERSION, image_vector=[1.] + [0.] * 1023, focus_status="animal_mask")
        avg_target, avg_meta = gallery_target(digest), gallery_mapping(digest)["_meta"]
        with patch.dict(os.environ, {"LOST_SEARCH_POOLING": "token"}):
            self.assertEqual(focus_version(), FOCUS_VERSION_CLS)
            self.assertNotEqual(gallery_target(digest), avg_target)
            self.assertEqual(gallery_mapping(digest)["_meta"]["model_version"], FOCUS_VERSION_CLS)
            self.assertNotEqual(gallery_mapping(digest)["_meta"], avg_meta)
            self.assertFalse(reusable_document(avg_document, row))
            self.assertTrue(reusable_document(dict(avg_document, model_version=FOCUS_VERSION_CLS), row))
        self.assertEqual(focus_version(), FOCUS_VERSION)
        self.assertTrue(reusable_document(avg_document, row))

    def test_color_contract_creates_a_new_target_even_for_identical_source_snapshot(self):
        from app.gallery.builder import gallery_target
        digest = "a" * 64
        legacy = "animals-lost-dinov3-sam3-build-" + hashlib.sha256((FOCUS_VERSION + digest).encode()).hexdigest()[:24]
        self.assertNotEqual(gallery_target(digest), legacy)

    def test_corrected_color_version_does_not_overwrite_previous_gallery_target(self):
        from app.gallery.builder import gallery_target, METADATA_VERSION
        digest = "c" * 64
        previous = "animals-lost-dinov3-sam3-build-" + hashlib.sha256(
            (FOCUS_VERSION + "foreground-lab32-joint8-v2" + METADATA_VERSION + digest).encode()).hexdigest()[:24]
        self.assertNotEqual(gallery_target(digest), previous)

    def test_status_is_indexed_and_metadata_contract_changes_the_target(self):
        from app.vision.coat_color import VERSION as COLOR_VERSION
        from app.gallery.builder import METADATA_VERSION, gallery_target
        digest = "b" * 64
        mapping = gallery_mapping(digest)
        self.assertEqual(mapping["properties"]["status"], {"type": "keyword"})
        self.assertEqual(mapping["_meta"]["metadata_version"], METADATA_VERSION)
        previous = "animals-lost-dinov3-sam3-build-" + hashlib.sha256(
            (FOCUS_VERSION + COLOR_VERSION + digest).encode()).hexdigest()[:24]
        self.assertNotEqual(gallery_target(digest), previous)
