"""Photo-first retrieval. Auxiliary evidence changes rank, never eligibility."""
import io
import logging
import math
import re
import warnings
from datetime import date
from PIL import Image, ImageOps, UnidentifiedImageError
from app.vision.coat_color import mismatch_against, ranking_weight

MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_PIXELS = 16_000_000
CANDIDATE_POOL = 200
MAX_RESULTS = 20
# 사망 기록은 살아 있는 후보와 섞지 않고 따로 이만큼만 보여준다.
MAX_DECEASED_RESULTS = 10
AUXILIARY_BOOST = 0.01
ACTIVE_STATUSES = ("NOTICE", "PROTECT")
# 살아서 보호소를 떠난 동물: 입양, 반환, 기증, 방사. 입력 이름 includeAdoptedOrReturned는 호환을 위해 그대로 둔다.
RESOLVED_STATUSES = ("ADOPTED", "RETURNED", "DONATED", "RELEASED")
DECEASED_STATUSES = ("NATURAL_DEATH", "EUTHANIZED")


class InvalidPhoto(ValueError):
    pass


def decode_photo(data: bytes) -> Image.Image:
    if not data or len(data) > MAX_IMAGE_BYTES:
        raise InvalidPhoto("사진은 5MiB 이하이어야 합니다")
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as image:
                if (image.format not in {"JPEG", "PNG", "MPO"}
                        or (getattr(image, "n_frames", 1) != 1 and image.format != "MPO")):
                    raise InvalidPhoto("JPG 또는 PNG 정지 사진을 선택해 주세요")
                if image.width * image.height > MAX_PIXELS:
                    raise InvalidPhoto("사진 해상도는 1600만 픽셀 이하이어야 합니다")
                image.verify()
            with Image.open(io.BytesIO(data)) as image:
                # Match the gallery: MPO is a primary JPEG plus auxiliary still images.
                image.seek(0)
                oriented = ImageOps.exif_transpose(image)
                try:
                    rgb = oriented.convert("RGB")
                    rgb.info.clear()
                    return rgb
                finally:
                    if oriented is not image:
                        oriented.close()
    except (UnidentifiedImageError, OSError, SyntaxError, Image.DecompressionBombError,
            Image.DecompressionBombWarning) as exc:
        raise InvalidPhoto("사진을 읽을 수 없습니다") from exc


def auxiliary_evidence(source, lost_date=None, region=None, description=None):
    evidence = []
    if lost_date:
        try:
            found = date.fromisoformat(str(source.get("happen_date", ""))[:10])
            if found >= lost_date:
                evidence.append("FOUND_ON_OR_AFTER_LOST_DATE")
        except ValueError:
            pass
    # Only the reported discovery place, never the shelter address.
    region = (region or "").strip()
    if region and region in str(source.get("happen_place") or ""):
        evidence.append("DISCOVERY_PLACE_TEXT_MATCH")
    tokens = set(re.findall(r"[가-힣A-Za-z0-9]+", (description or "").casefold()))
    registered = " ".join(str(source.get(k) or "") for k in ("color", "special_mark", "description")).casefold()
    if any(len(t) >= 2 and t in registered for t in tokens):
        evidence.append("REGISTERED_DESCRIPTION_TEXT_MATCH")
    return evidence


def rank_candidates(hits, lost_date=None, region=None, description=None, *, coat_color=None, color_weight=0.,
                    limit=MAX_RESULTS):
    candidates = []
    colors = {}
    for hit in hits:
        src = hit["_source"]
        score = float(hit["_score"])
        if not math.isfinite(score):
            continue
        evidence = auxiliary_evidence(src, lost_date, region, description)
        colors[src["id"]] = src.get("coat_color")
        candidates.append({"animalId": src["id"], "imageScore": score - 1.0,
                           "matchedEvidence": evidence})
    penalties = dict.fromkeys(colors, 0.)
    if color_weight:
        # 털색 벌점은 color_weight × 거리(0~1)라서 0과 color_weight 사이다.
        # 벌점을 최대로 받아도 남는 점수가 cutoff 이상인 후보가 limit개 있다. 벌점 없이도 cutoff에 못 미치는 후보는
        # 그 limit개보다 뒤라 결과에 들 수 없으므로 털색 계산을 건너뛴다(벌점 0으로 두어도 순위 밖이다).
        # 1e-9는 소수 계산 오차로 경계 후보를 잘못 건너뛰지 않게 둔 여유다.
        def base(candidate):
            return candidate["imageScore"] + AUXILIARY_BOOST * len(candidate["matchedEvidence"])
        lowest = sorted((base(candidate) - color_weight for candidate in candidates), reverse=True)
        cutoff = lowest[limit - 1] - 1e-9 if 0 < limit <= len(lowest) else -math.inf
        distance_to = mismatch_against(coat_color)
        for candidate in candidates:
            if base(candidate) < cutoff:
                continue
            distance = distance_to(colors[candidate["animalId"]])
            if distance is not None:
                penalties[candidate["animalId"]] = color_weight * distance
    # Provisional bounded boost: <= .03, so metadata cannot overturn large visual gaps.
    candidates.sort(key=lambda c: (-(c["imageScore"] + AUXILIARY_BOOST * len(c["matchedEvidence"]) - penalties[c["animalId"]]),
                                   -c["imageScore"], c["animalId"]))
    return candidates[:limit]


def search_photo(data, species, lost_date=None, region=None, description=None, include_adopted_or_returned=False,
                 include_deceased=False):
    from app.vision.dinov3 import get_encoder, gallery_index
    color_weight = ranking_weight()
    with decode_photo(data) as image:
        embedding = get_encoder().encode_with_metadata(image, species)
    logging.getLogger(__name__).info("Lost-search image processing: %s", embedding.focus_status)
    from app.gallery.storage import get_postgresql_store
    store, alias = get_postgresql_store(), gallery_index()
    statuses = ACTIVE_STATUSES + (RESOLVED_STATUSES if include_adopted_or_returned else ())
    hits = store.search(alias, embedding, species, statuses, CANDIDATE_POOL)
    ranking = dict(lost_date=lost_date, region=region, description=description,
                   coat_color=embedding.coat_color, color_weight=color_weight)
    result = {"candidates": rank_candidates(hits, **ranking)}
    if include_deceased:
        # 사망 기록은 별도 검색으로 뽑아 살아 있는 후보의 상위 자리를 차지하지 않게 한다. 사진 벡터는 위에서 한 번만 계산한다.
        # 끈 요청의 응답에는 이 키를 넣지 않아, 이 필드를 모르는 이전 호출자와도 응답 모양이 같다.
        deceased = store.search(alias, embedding, species, DECEASED_STATUSES, CANDIDATE_POOL)
        result["deceasedCandidates"] = rank_candidates(deceased, **ranking, limit=MAX_DECEASED_RESULTS)
    return result
