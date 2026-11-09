"""Image recommendations reuse published SAM3/DINOv3 features without inference."""
from app.vision.coat_color import VERSION as COLOR_VERSION, ranking_weight
from app.vision.dinov3 import gallery_index
from app.lost_search.service import rank_candidates
from app.gallery.storage import get_postgresql_store
from app.vision.sam3_focus import CLS_VERSIONS, focus_version

MAX_RECOMMENDATIONS = 6
# Compatibility floor; not an identity probability or a calibrated DINOv3 threshold.
MIN_VISUAL_SCORE = 0.6
# CLS similarities spread wider (other animals ~0.27 vs ~0.73 for avg), so 0.6 would drop real neighbours:
# on the 2026-11-01 offline sample it left 12 % of animals with no recommendation; 0.4 kept ~6 like avg.
MIN_VISUAL_SCORE_CLS = 0.4


def recommend_animals(animal_id, species):
    weight, version = ranking_weight(), focus_version()
    # CLS 계열이면 입력 크기와 관계없이 CLS 하한을 쓴다. 384의 추천 0건 비율은 전환 전에 다시 잰다.
    floor = MIN_VISUAL_SCORE_CLS if version in CLS_VERSIONS else MIN_VISUAL_SCORE
    source, hits = get_postgresql_store().recommendation_candidates(
        gallery_index(), animal_id, species, version, COLOR_VERSION if weight else None)
    # Connection returned before color reranking. No remote image, GPU or full-gallery cache.
    eligible = [hit for hit in hits if float(hit['_score']) - 1.0 >= floor]
    ranked = rank_candidates(eligible, coat_color=source.get('coat_color'), color_weight=weight)
    return [candidate['animalId'] for candidate in ranked][:MAX_RECOMMENDATIONS]
