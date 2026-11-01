import io
import json
import os
import unittest
from datetime import date
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient
from PIL import Image
from app.lost_search import router as api
from app.lost_search.service import decode_photo, InvalidPhoto, rank_candidates, auxiliary_evidence


def photo(format="PNG"):
    buf = io.BytesIO()
    with Image.new("RGB", (16, 16), "white") as img:
        img.save(buf, format=format)
    return buf.getvalue()


def hit(id, score, **fields):
    return {"_score": score + 1, "_source": {"id": id, **fields}}


class PhotoTest(unittest.TestCase):
    def test_jpeg_and_png_decode_to_rgb(self):
        for fmt in ("PNG", "JPEG"):
            with self.subTest(fmt=fmt), decode_photo(photo(fmt)) as img:
                self.assertEqual(img.mode, "RGB")
                self.assertEqual(img.size, (16, 16))

    def test_mpo_uses_primary_still_image(self):
        buffer = io.BytesIO()
        with Image.new("RGB", (16, 12), "red") as primary, Image.new("RGB", (16, 12), "blue") as auxiliary:
            primary.save(buffer, format="MPO", save_all=True, append_images=[auxiliary])
        with decode_photo(buffer.getvalue()) as image:
            self.assertEqual(image.size, (16, 12))
            red, _, blue = image.getpixel((8, 6))
            self.assertGreater(red - blue, 200)

    def test_other_formats_and_corrupt_bytes_are_rejected(self):
        for data in (photo("GIF"), b"not an image", b"", photo()[:30]):
            with self.subTest(data_length=len(data)), self.assertRaises(InvalidPhoto):
                decode_photo(data)

    def test_exif_orientation_is_applied_and_metadata_removed(self):
        buffer = io.BytesIO()
        with Image.new("RGB", (12, 8), "white") as image:
            exif = Image.Exif()
            exif[274] = 6
            exif[315] = "test-owner"
            image.save(buffer, format="JPEG", exif=exif)
        with decode_photo(buffer.getvalue()) as image:
            self.assertEqual(image.size, (8, 12))
            self.assertEqual(image.info, {})
            self.assertEqual(dict(image.getexif()), {})

    def test_animated_png_is_rejected(self):
        buffer = io.BytesIO()
        with Image.new("RGB", (16, 16), "white") as first, Image.new("RGB", (16, 16), "black") as second:
            first.save(buffer, format="PNG", save_all=True, append_images=[second], duration=100, loop=0)
        with self.assertRaises(InvalidPhoto):
            decode_photo(buffer.getvalue())

    def test_pixel_limit_checked_before_decode(self):
        with patch("app.lost_search.service.MAX_PIXELS", 100):
            with self.assertRaises(InvalidPhoto):
                decode_photo(photo())


class RankingTest(unittest.TestCase):
    def test_no_conditions_preserves_visual_order(self):
        ranked = rank_candidates([hit(1, .9), hit(2, .8)])
        self.assertEqual([c["animalId"] for c in ranked], [1, 2])
        self.assertEqual(ranked[0]["matchedEvidence"], [])

    def test_auxiliary_match_promotes_close_candidate_without_excluding_others(self):
        ranked = rank_candidates([hit(1, .8), hit(2, .795, happen_place="상주시 동문동")], region="상주시")
        self.assertEqual([c["animalId"] for c in ranked], [2, 1])

    def test_auxiliary_matches_do_not_overturn_large_visual_gap(self):
        ranked = rank_candidates([hit(1, .9), hit(2, .5, happen_date="2026-09-10", happen_place="상주시", color="흰색")], date(2026, 9, 8), "상주시", "흰색")
        self.assertEqual([c["animalId"] for c in ranked], [1, 2])

    def test_unknown_and_mismatched_metadata_do_not_remove_candidates(self):
        ranked = rank_candidates([hit(1, .9, happen_date="unknown"), hit(2, .8, happen_date="2020-01-01", status="ADOPTED")], date(2026, 9, 8), "상주시", "흰색")
        self.assertEqual(len(ranked), 2)
        self.assertTrue(all(not c["matchedEvidence"] for c in ranked))

    def test_blank_region_is_not_matching_evidence(self):
        self.assertEqual(auxiliary_evidence({"happen_place": "상주시"}, region="   "), [])

    def test_shelter_address_is_not_discovery_evidence(self):
        self.assertEqual(auxiliary_evidence({"shelter_address": "상주시"}, region="상주시"), [])

    def test_result_size_is_bounded(self):
        self.assertEqual(len(rank_candidates([hit(i, .9) for i in range(30)])), 20)


class LostApiTest(unittest.TestCase):
    def setUp(self):
        app = FastAPI()
        app.include_router(api.router, prefix="/internal/animals")
        self.client = TestClient(app)
        self.env = patch.dict(os.environ, {"INTERNAL_API_KEY": "test-key"})
        self.env.start()
        self.addCleanup(self.env.stop)

    def post(self, fields=None, data=None, headers=None):
        return self.client.post("/internal/animals/lost-candidates", files={"image": ("photo.png", data or photo(), "image/png")}, data=fields or {"species": "DOG"}, headers=headers if headers is not None else {"X-Internal-Api-Key": "test-key"})

    def test_valid_request_passes_all_conditions_to_search(self):
        with patch.object(api, "search_photo", return_value={"candidates": []}) as search:
            response = self.post({"species": "DOG", "lostDate": "2026-09-08", "region": "상주시", "description": "갈색 귀",
                                  "includeAdoptedOrReturned": "true"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(search.call_args.args[1:], ("DOG", date(2026,9,8), "상주시", "갈색 귀", True))

    def test_unauthenticated_request_does_not_search(self):
        with patch.object(api, "search_photo") as search:
            self.assertEqual(self.post(headers={}).status_code, 401)
        search.assert_not_called()

    def test_invalid_species_date_and_long_description_do_not_search(self):
        for fields in ({"species":"ETC"}, {"species":"DOG","lostDate":"bad"}, {"species":"CAT","description":"x"*501},
                       {"species":"DOG","includeAdoptedOrReturned":"invalid"}):
            with self.subTest(fields=str(fields)[:40]), patch.object(api,"search_photo") as search:
                self.assertEqual(self.post(fields).status_code,422)
                search.assert_not_called()

    def test_empty_optional_fields_are_allowed(self):
        with patch.object(api, "search_photo", return_value={"candidates": []}):
            self.assertEqual(self.post({"species":"CAT","lostDate":"","region":"","description":""}).status_code,200)

    def test_whitespace_optional_text_is_normalized(self):
        with patch.object(api, "search_photo", return_value={"candidates": []}) as search:
            response = self.post({"species": "DOG", "region": "  ", "description": "  갈색 귀  "})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(search.call_args.args[3:], (None, "갈색 귀", False))

    def test_image_and_request_size_limits(self):
        with patch.object(api,"search_photo") as search:
            self.assertEqual(self.post(data=b"x"*(5*1024*1024+1)).status_code,413)
            self.assertEqual(self.post(data=b"x"*(6*1024*1024)).status_code,413)
        search.assert_not_called()

    def test_failure_is_not_empty_success(self):
        for error, expected in [(InvalidPhoto("bad photo"),422),(RuntimeError("upstream"),503)]:
            with patch.object(api,"search_photo", side_effect=error):
                response=self.post()
            self.assertEqual(response.status_code,expected)

    def test_busy_search_is_rejected(self):
        with patch.object(api._gate,"locked", return_value=True), patch.object(api,"search_photo") as search:
            self.assertEqual(self.post().status_code,503)
        search.assert_not_called()


def embedding(**changes):
    from app.vision.dinov3 import AnimalEmbedding
    values = dict(vector=[.1, .2], model_version="test-model", focus_status="original_multiple_animals",
                  animal_vector=None, coat_color=None)
    return AnimalEmbedding(**{**values, **changes})


def retrieve(query, hits, **options):
    """Run search_photo against a PostgreSQL store double; the encoder is never loaded."""
    from unittest.mock import create_autospec
    from app.lost_search.service import search_photo
    from app.gallery.pg_store import PostgresqlGalleryStore
    store = create_autospec(PostgresqlGalleryStore, instance=True)
    if isinstance(hits, Exception):
        store.search.side_effect = hits
    else:
        store.search.return_value = hits
    arguments = options.pop("arguments", ("DOG",))
    # The weight has no default; keep a caller's value and otherwise rank without color.
    weight = {"LOST_SEARCH_COAT_COLOR_WEIGHT": os.environ.get("LOST_SEARCH_COAT_COLOR_WEIGHT", "0")}
    with patch.dict(os.environ, weight), patch("app.vision.dinov3.get_encoder") as encoder, \
            patch("app.vision.dinov3.gallery_index", return_value="animals-lost-dinov3-large-v1"), \
            patch("app.gallery.storage.get_postgresql_store", return_value=store):
        encoder.return_value.encode_with_metadata.return_value = query
        return store, encoder, search_photo(photo(), *arguments, **options)


class RetrievalContractTest(unittest.TestCase):
    def test_default_query_filters_active_status_before_candidate_pool(self):
        query = embedding()
        store, _, result = retrieve(query, [hit(7, .8, status="ADOPTED")],
                                    arguments=("DOG", date(2026, 9, 8), "상주시", "흰색"))
        # The store applies species, model version and status before its top-200 cut.
        store.search.assert_called_once_with("animals-lost-dinov3-large-v1", query, "DOG", ("NOTICE", "PROTECT"), 200)
        self.assertEqual([call[0] for call in store.method_calls], ["search"])
        self.assertEqual(result["candidates"][0]["animalId"], 7)

    def test_resolved_option_adds_only_adopted_and_returned_to_status_filter(self):
        store, _, _ = retrieve(embedding(), [], include_adopted_or_returned=True)
        self.assertEqual(store.search.call_args.args[3], ("NOTICE", "PROTECT", "ADOPTED", "RETURNED"))

    def test_animal_channel_reaches_the_store_with_the_query_species_and_model(self):
        query = embedding(vector=[1., 0.], animal_vector=[0., 1.], model_version="dual-v2", focus_status="animal_mask")
        store, encoder, _ = retrieve(query, [])
        passed = store.search.call_args.args[1]
        self.assertEqual((passed.vector, passed.animal_vector, passed.model_version), ([1., 0.], [0., 1.], "dual-v2"))
        self.assertEqual(store.search.call_args.args[2], "DOG")
        self.assertEqual(encoder.return_value.encode_with_metadata.call_args.args[1], "DOG")

    def test_animal_channel_is_only_combined_when_the_gallery_document_has_it(self):
        from unittest.mock import MagicMock
        from types import SimpleNamespace
        from app.gallery.pg_store import ANIMAL_REGION_WEIGHT, PostgresqlGalleryStore
        connection = MagicMock()
        connection.execute.return_value.fetchone.return_value = ({"model_version": "dual-v2"}, True)
        connection.execute.return_value.fetchall.return_value = []
        pool = MagicMock()
        pool.connection.return_value.__enter__.return_value = connection
        vector, animal = [1.] + [0.] * 1023, [0.] * 1023 + [1.]
        store = PostgresqlGalleryStore(pool)
        for query_animal in (animal, None):
            connection.execute.reset_mock()
            with self.subTest(query_animal=query_animal is not None):
                store.search("animals-lost-dinov3-sam3-test",
                             SimpleNamespace(vector=vector, animal_vector=query_animal, model_version="dual-v2"),
                             "DOG", ("NOTICE", "PROTECT"), 200)
                sql, parameters = connection.execute.call_args.args
                # Whole-image score is the fallback unless both the query and the document have an animal vector.
                self.assertIn("q.animal IS NOT NULL AND d.animal_vector IS NOT NULL", sql)
                self.assertIn("ELSE 2.0-(d.image_vector <=> q.whole)", sql)
                self.assertEqual(parameters[:4], (json.dumps(vector), json.dumps(query_animal) if query_animal else None, ANIMAL_REGION_WEIGHT, ANIMAL_REGION_WEIGHT))
                self.assertEqual(parameters[4:], ("animals-lost-dinov3-sam3-test", "dual-v2", "DOG", ["NOTICE", "PROTECT"], 200))
                # Legacy documents without a status stay searchable during a staged rollout.
                self.assertIn("(d.status=ANY(%s) OR d.status IS NULL)", sql)

    def test_unavailable_gallery_is_failure_not_empty_candidates(self):
        from app.gallery.pg_store import GalleryUnavailable
        with self.assertRaises(GalleryUnavailable):
            retrieve(embedding(), GalleryUnavailable("Matching published PostgreSQL gallery required"),
                     arguments=("CAT",))

    def test_query_and_gallery_color_features_drive_returned_order(self):
        from tests.test_coat_color import descriptor
        dark, tan = descriptor((45, 40, 35)), descriptor((165, 110, 60))
        query = embedding(vector=[1., 0.], model_version="test", focus_status="animal_mask", animal_vector=[1., 0.], coat_color=dark)
        with patch.dict(os.environ, {"LOST_SEARCH_COAT_COLOR_WEIGHT": ".12"}):
            _, _, result = retrieve(query, [hit(1, .88, coat_color=tan), hit(2, .87, coat_color=dark)])
        self.assertEqual([c["animalId"] for c in result["candidates"]], [2, 1])
