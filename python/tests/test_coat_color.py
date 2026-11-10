import copy
import os
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image, ImageEnhance
from app.vision.coat_color import describe, mismatch, ranking_weight, valid, VERSION
from app.lost_search.service import rank_candidates


def swatch(color, background="blue"):
    image = Image.new("RGB", (120, 120), background)
    image.paste(color, (20, 20, 100, 100))
    mask = np.zeros((120, 120), dtype=bool)
    mask[20:100, 20:100] = True
    return image, mask


def descriptor(color):
    image, mask = swatch(color)
    with image:
        return describe(image, mask)


def synthetic_descriptor(points):
    from app.vision.coat_color import JOINT_BINS, MARGINAL_BINS
    joint = np.zeros((JOINT_BINS, JOINT_BINS, JOINT_BINS))
    for point in points:
        joint[point] += 1 / len(points)
    marginal = []
    # Put the same discrete joint mass into the nearest 32-bin positions. This
    # fixture can create equal marginals with deliberately different color
    # combinations, the information v1 could not retain.
    for axis in range(3):
        values = np.zeros(MARGINAL_BINS)
        for point in points:
            values[round(point[axis] * (MARGINAL_BINS - 1) / (JOINT_BINS - 1))] += 1 / len(points)
        marginal.extend(values.tolist())
    return {"version": VERSION, "source": "single_mask", "pixels": 1000,
            "marginal": marginal, "joint": joint.ravel().tolist()}


class CoatColorTest(unittest.TestCase):
    def test_nearly_identical_neutral_colors_do_not_receive_maximum_penalty(self):
        gray = descriptor((128, 128, 128))
        for neighbor in ((128, 129, 128), (128, 128, 129), (129, 128, 128)):
            with self.subTest(neighbor=neighbor):
                self.assertLess(mismatch(gray, descriptor(neighbor)), .01)

    def test_wrapped_chroma_v2_descriptor_is_not_compared_with_corrected_colors(self):
        corrected = descriptor("gray")
        previous = dict(corrected, version="foreground-lab32-joint8-v2")
        self.assertFalse(valid(previous))
        self.assertIsNone(mismatch(corrected, previous))
        self.assertIsNone(mismatch(previous, corrected))

    def test_background_and_small_boundary_changes_do_not_change_foreground(self):
        first, mask = swatch((110, 70, 35), "blue")
        second, _ = swatch((110, 70, 35), "green")
        with first, second:
            second.paste("red", (20, 20, 100, 21))
            a, b = describe(first, mask), describe(second, mask)
        self.assertTrue(valid(a))
        self.assertEqual(a, b)
        self.assertEqual(mismatch(a, b), 0)

    def test_dark_vs_tan_and_black_vs_white_differ_more_than_exposure_change(self):
        dark, mask = swatch((45, 40, 35))
        with dark, ImageEnhance.Brightness(dark).enhance(1.3) as brighter:
            a, b = describe(dark, mask), describe(brighter, mask)
        same = mismatch(a, b)
        self.assertLess(same, .08)
        self.assertGreater(mismatch(a, descriptor((165, 110, 60))), same + .25)
        self.assertGreater(mismatch(descriptor("black"), descriptor("white")), .5)

    def test_mixed_coat_is_not_collapsed_to_average_gray_and_small_mark_has_limited_effect(self):
        gray, mask = swatch((128, 128, 128))
        mixed, _ = swatch("white")
        mixed.paste("black", (20, 20, 60, 100))
        marked, _ = swatch((128, 128, 128))
        marked.paste("red", (50, 50, 60, 60))
        with gray, mixed, marked:
            a, b, c = [describe(image, mask) for image in (gray, mixed, marked)]
        self.assertGreater(mismatch(a, b), .15)
        self.assertLess(mismatch(a, c), .05)

    def test_joint_distribution_distinguishes_color_combinations_with_equal_marginals(self):
        correlated = synthetic_descriptor([(1, 1, 1), (6, 6, 6)])
        crossed = synthetic_descriptor([(1, 6, 1), (6, 1, 6)])
        self.assertTrue(valid(correlated))
        self.assertTrue(valid(crossed))
        self.assertGreater(mismatch(correlated, crossed), .6)

    def test_unavailable_wrong_version_or_invalid_features_are_not_a_mismatch(self):
        good = descriptor("black")
        corrupt = copy.deepcopy(good); corrupt["joint"][0] = float("nan")
        for bad in (None, {}, dict(good, version="old"), corrupt):
            self.assertIsNone(mismatch(good, bad))
        image, mask = swatch("black")
        with image:
            mask[:] = False
            self.assertIsNone(describe(image, mask))

    def test_color_reorders_close_candidates_without_hiding_them_or_changing_raw_image_score(self):
        black = descriptor((45, 40, 35)); tan = descriptor((165, 110, 60))
        hits = [{"_score": 1.88, "_source": {"id": 1, "coat_color": tan}},
                {"_score": 1.87, "_source": {"id": 2, "coat_color": black}}]
        base = rank_candidates(hits, coat_color=black)
        enabled = rank_candidates(hits, coat_color=black, color_weight=.12)
        self.assertEqual([r["animalId"] for r in base], [1, 2])
        self.assertEqual([r["animalId"] for r in enabled], [2, 1])
        self.assertAlmostEqual(enabled[1]["imageScore"], .88)
        self.assertEqual(rank_candidates(hits, color_weight=.12), base)
        hits[0]["_source"].pop("coat_color")
        self.assertEqual([r["animalId"] for r in rank_candidates(hits, coat_color=black, color_weight=.12)], [1, 2])

    def test_ranking_weight_must_be_stated_and_invalid_weight_is_not_silently_enabled(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "LOST_SEARCH_COAT_COLOR_WEIGHT"): ranking_weight()
        with patch.dict(os.environ, {"LOST_SEARCH_COAT_COLOR_WEIGHT": "0"}):
            self.assertEqual(ranking_weight(), 0)
        for value in ("nan", "inf", "-0.1", "0.3", "typo"):
            with self.subTest(value=value), patch.dict(os.environ, {"LOST_SEARCH_COAT_COLOR_WEIGHT": value}):
                with self.assertRaises(RuntimeError): ranking_weight()


def reference_rank_candidates(hits, lost_date=None, region=None, description=None, *, coat_color=None,
                              color_weight=0., limit=20):
    """털색 계산을 건너뛰기 전의 정렬(2026-11-10 dev 6dd42e16)을 그대로 옮긴 기준 구현이다."""
    from app.lost_search.service import AUXILIARY_BOOST, auxiliary_evidence
    import math
    candidates = []
    penalties = {}
    for hit in hits:
        src = hit["_source"]
        score = float(hit["_score"])
        if not math.isfinite(score):
            continue
        distance = mismatch(coat_color, src.get("coat_color")) if color_weight else None
        penalties[src["id"]] = color_weight * distance if distance is not None else 0.
        evidence = auxiliary_evidence(src, lost_date, region, description)
        candidates.append({"animalId": src["id"], "imageScore": score - 1.0, "matchedEvidence": evidence})
    candidates.sort(key=lambda c: (-(c["imageScore"] + AUXILIARY_BOOST * len(c["matchedEvidence"])
                                     - penalties[c["animalId"]]), -c["imageScore"], c["animalId"]))
    return candidates[:limit]


class CoatColorRankingShortcutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rng = np.random.default_rng(20261110)
        colors = [tuple(int(v) for v in rng.integers(0, 256, 3)) for _ in range(12)]
        cls.descriptors = [descriptor(color) for color in colors]

    def test_shared_source_comparison_equals_pairwise_mismatch(self):
        from app.vision.coat_color import mismatch_against
        good = self.descriptors[0]
        corrupt = copy.deepcopy(good); corrupt["joint"][0] = float("nan")
        for source in (good, self.descriptors[1], None, corrupt, dict(good, version="old")):
            distance_to = mismatch_against(source)
            for candidate in (*self.descriptors[:4], None, corrupt):
                self.assertEqual(distance_to(candidate), mismatch(source, candidate))

    def test_skipping_unreachable_candidates_keeps_the_same_ranking(self):
        # 점수 동률, 털색 없음, 잘못된 털색, 보조 근거 가산, 결과 수보다 적은 후보를 섞어 기준 구현과 순서까지 비교한다.
        rng = np.random.default_rng(7)
        corrupt = copy.deepcopy(self.descriptors[0]); corrupt["joint"][0] = float("nan")
        palette = [*self.descriptors, None, corrupt]
        for case in range(300):
            count = int(rng.integers(0, 60))
            scores = rng.choice([1.40, 1.55, 1.62, 1.62, 1.7, 1.71], count) + rng.normal(0, .05, count) * (case % 3 > 0)
            hits = [{"_score": float(score),
                     "_source": {"id": index + 1, "coat_color": palette[int(rng.integers(0, len(palette)))],
                                 "happen_place": "서울 마포구" if rng.random() < .2 else "부산",
                                 "color": "갈색" if rng.random() < .3 else "흰색"}}
                    for index, score in enumerate(scores)]
            source = palette[int(rng.integers(0, len(palette)))]
            options = {"region": "마포구", "description": "갈색 강아지"} if case % 2 else {}
            for weight in (0., .05, .1, .2):
                for limit in (1, 6, 20):
                    with self.subTest(case=case, weight=weight, limit=limit):
                        expected = reference_rank_candidates(hits, coat_color=source, color_weight=weight,
                                                             limit=limit, **options)
                        actual = rank_candidates(hits, coat_color=source, color_weight=weight, limit=limit, **options)
                        self.assertEqual(actual, expected)

    def test_candidates_that_cannot_reach_the_result_are_not_color_compared(self):
        import app.vision.coat_color as coat_color
        source = self.descriptors[0]
        # 상위 6마리는 0.9점, 나머지는 0.7점이라 털색 벌점(최대 0.1)으로 순위가 바뀔 수 없다.
        hits = [{"_score": 1.9, "_source": {"id": i, "coat_color": self.descriptors[i % 12]}} for i in range(1, 7)]
        hits += [{"_score": 1.7, "_source": {"id": i, "coat_color": self.descriptors[i % 12]}} for i in range(7, 200)]
        with patch.object(coat_color, "_distance", wraps=coat_color._distance) as distance:
            ranked = rank_candidates(hits, coat_color=source, color_weight=.1, limit=6)
        self.assertEqual(sorted(r["animalId"] for r in ranked), [1, 2, 3, 4, 5, 6])
        self.assertEqual(distance.call_count, 6)
