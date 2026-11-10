"""Versioned foreground Lab distributions; not an animal identity classifier."""
from functools import lru_cache
from itertools import product
import math

from app.settings import CoatColorSettings

VERSION = "foreground-lab32-joint8-v3"
MARGINAL_BINS = 32
JOINT_BINS = 8
JOINT_TOLERANCE = .08
MIN_PIXELS = 128
MAX_SAMPLES = 8192
SOURCES = frozenset(("single_mask", "primary_mask"))


@lru_cache(maxsize=1)
def _transform():
    from PIL import ImageCms
    return ImageCms.buildTransformFromOpenProfiles(
        ImageCms.createProfile("sRGB"), ImageCms.createProfile("LAB"), "RGB", "LAB")


def describe(image, foreground, *, source="single_mask"):
    """Ignore fill/background; sample a bounded number of confident inner pixels."""
    import numpy as np
    from PIL import Image, ImageCms, ImageFilter
    if source not in SOURCES:
        raise ValueError("Unknown coat-color source")
    mask = np.asarray(foreground, dtype=bool)
    if mask.shape != (image.height, image.width):
        raise ValueError("Color mask does not match image")
    with Image.fromarray(mask.astype("uint8") * 255) as alpha:
        # Drop one boundary pixel; this does not guarantee fur-only segmentation.
        with alpha.filter(ImageFilter.MinFilter(3)) as inner:
            selected = np.flatnonzero(np.asarray(inner).ravel())
    count = len(selected)
    if count < MIN_PIXELS:
        return None
    if count > MAX_SAMPLES:
        selected = selected[np.linspace(0, count - 1, MAX_SAMPLES, dtype=int)]
    pixels = np.asarray(image).reshape(-1, 3)[selected]
    with Image.fromarray(pixels.reshape(1, -1, 3), "RGB") as sample:
        with ImageCms.applyTransform(sample, _transform()) as converted:
            # LAB raw array export wraps negative a/b values around zero.
            # Read individual bands to keep neutral chroma centered at 128.
            bands = converted.split()
            try:
                lab = np.stack([np.asarray(band) for band in bands], axis=-1)
                lab = lab.reshape(-1, 3).astype("float64")
            finally:
                for band in bands:
                    band.close()
    # Soft bins avoid a sudden mismatch across an arbitrary histogram boundary.
    coordinates = lab / 255 * (MARGINAL_BINS - 1)
    lower = coordinates.astype(int)
    fractions = coordinates - lower
    marginal = []
    for channel in range(3):
        h = np.bincount(lower[:, channel], weights=1 - fractions[:, channel],
                        minlength=MARGINAL_BINS)
        h += np.bincount(np.minimum(lower[:, channel] + 1, MARGINAL_BINS - 1),
                         weights=fractions[:, channel], minlength=MARGINAL_BINS)
        marginal.extend((h / len(pixels)).tolist())

    # Marginal histograms cannot distinguish color combinations with identical
    # per-channel totals. A softly binned joint Lab distribution keeps that
    # correlation while remaining bounded and deterministic.
    joint_lab = lab.copy()
    # Preserve relative light/dark pattern in the joint distribution without
    # charging twice for a uniform exposure shift. Absolute lightness remains
    # available in the marginal distribution with its established tolerance.
    joint_lab[:, 0] = np.clip(lab[:, 0] - np.median(lab[:, 0]) + 127.5, 0, 255)
    joint_coordinates = joint_lab / 255 * (JOINT_BINS - 1)
    joint_lower = joint_coordinates.astype(int)
    joint_fractions = joint_coordinates - joint_lower
    joint = np.zeros((JOINT_BINS, JOINT_BINS, JOINT_BINS), dtype="float64")
    for offsets in product((0, 1), repeat=3):
        indices = [np.minimum(joint_lower[:, channel] + offsets[channel], JOINT_BINS - 1)
                   for channel in range(3)]
        weights = np.ones(len(lab), dtype="float64")
        for channel, offset in enumerate(offsets):
            weights *= (joint_fractions[:, channel] if offset
                        else 1 - joint_fractions[:, channel])
        np.add.at(joint, tuple(indices), weights)
    joint /= len(pixels)
    return {"version": VERSION, "source": source, "pixels": count,
            "marginal": marginal, "joint": joint.ravel().tolist()}


def valid(value):
    if not isinstance(value, dict) or value.get("version") != VERSION:
        return False
    pixels, marginal, joint = value.get("pixels"), value.get("marginal"), value.get("joint")
    if type(pixels) is not int or not MIN_PIXELS <= pixels <= 1024 * 1024:
        return False
    if value.get("source") not in SOURCES:
        return False
    if not isinstance(marginal, list) or len(marginal) != 3 * MARGINAL_BINS:
        return False
    if not isinstance(joint, list) or len(joint) != JOINT_BINS ** 3:
        return False
    if any(type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 1
           for v in marginal + joint):
        return False
    return (all(abs(sum(marginal[i:i+MARGINAL_BINS]) - 1) < 1e-5
                for i in range(0, 3*MARGINAL_BINS, MARGINAL_BINS))
            and abs(sum(joint) - 1) < 1e-5)


def _marginal_distance(first, second):
    distances = []
    for channel, scale, tolerance, normalizer in ((0, 100, 12, 60),
                                                   (1, 255, 4, 40),
                                                   (2, 255, 4, 40)):
        cumulative = distance = 0.
        for i in range(channel * MARGINAL_BINS, (channel + 1) * MARGINAL_BINS):
            cumulative += first["marginal"][i] - second["marginal"][i]
            distance += abs(cumulative)
        distances.append(max(0., distance * scale / (MARGINAL_BINS - 1) - tolerance)
                         / normalizer)
    return min(1., math.sqrt(sum(d*d for d in distances) / 3))


def _joint_distance(first, second):
    import numpy as np
    a = np.asarray(first["joint"], dtype="float64").reshape(
        JOINT_BINS, JOINT_BINS, JOINT_BINS)
    b = np.asarray(second["joint"], dtype="float64").reshape(
        JOINT_BINS, JOINT_BINS, JOINT_BINS)

    def shift_lightness(histogram, offset):
        if offset == 0:
            return histogram
        shifted = np.zeros_like(histogram)
        if offset > 0:
            shifted[offset:] = histogram[:-offset]
            shifted[-1] += histogram[-offset:].sum(axis=0)
        else:
            amount = -offset
            shifted[:-amount] = histogram[amount:]
            shifted[0] += histogram[:amount].sum(axis=0)
        return shifted

    # One coarse lightness bin is the v1 12 L* exposure tolerance expressed in
    # the joint space. Chroma is not shifted because coat hue is useful evidence.
    distances = []
    for offset in (-1, 0, 1):
        coefficient = np.sqrt(a * shift_lightness(b, offset)).sum()
        distances.append(math.sqrt(max(0., 1 - min(1., float(coefficient)))))
    distance = min(distances)
    return max(0., distance - JOINT_TOLERANCE) / (1 - JOINT_TOLERANCE)


def mismatch(first, second):
    """Bounded distance, or None when color comparison is unavailable.

    Marginal earth-mover distance retains the v1 exposure tolerance. Joint Lab
    distance additionally distinguishes different color combinations that have
    the same per-channel totals. These remain ranking evidence, not an identity
    threshold.
    """
    if not valid(first) or not valid(second):
        return None
    return _distance(first, second)


def mismatch_against(source):
    """기준 털색 하나를 갤러리 후보 여러 개와 비교하는 함수를 돌려준다.

    기준 털색은 여기서 한 번만 전체 검사(valid)한다. 후보는 갤러리에 저장된 털색이라 저장할 때
    (gallery builder, PostgresqlGalleryStore.write_page) 이미 전체 검사를 통과했다. 그래서 비교할 때는
    값 608개를 다시 훑지 않고 형식만 확인한다(stored_descriptor). 저장 때 검사를 통과한 후보라면
    값은 mismatch(source, 후보)와 같다.
    """
    if not valid(source):
        return lambda candidate: None

    def distance_to(candidate):
        if not stored_descriptor(candidate):
            return None
        return _distance(source, candidate)

    return distance_to


def stored_descriptor(value):
    """저장할 때 valid()를 통과한 털색인지 형식만 본다. 빈 값, 다른 버전, 개수가 다른 값은 비교하지 않는다."""
    return (isinstance(value, dict) and value.get("version") == VERSION
            and value.get("source") in SOURCES
            and type(value.get("pixels")) is int and MIN_PIXELS <= value["pixels"] <= 1024 * 1024
            and isinstance(value.get("marginal"), list) and len(value["marginal"]) == 3 * MARGINAL_BINS
            and isinstance(value.get("joint"), list) and len(value["joint"]) == JOINT_BINS ** 3)


def _distance(first, second):
    # 두 거리 모두 0~1이라 결과도 0~1이다. 정렬에서 털색 벌점의 상한을 이 범위로 잡는다.
    marginal = _marginal_distance(first, second)
    joint = _joint_distance(first, second)
    # v2 adds evidence; it must not dilute a difference that the established
    # marginal comparison already detected.
    return max(marginal, joint)


def ranking_weight():
    # Every vision process states it explicitly; 0 keeps ranking color-blind.
    return CoatColorSettings.load().ranking_weight
