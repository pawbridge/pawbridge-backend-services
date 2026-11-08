-- 유사 동물 추천 후보용 HNSW 인덱스(pgvector 0.8). vector는 V3처럼 DB 소유자가 public에 설치해 둔다.
-- 추천 후보는 언제나 "입양 가능한 같은 종"이라 종별로 그 조건만 담는 부분 인덱스로 둔다.
-- 종과 상태 구분 없는 전체 인덱스(약 1GB)는 조건에 맞는 문서가 적어(입양 가능 고양이는 전체의 약 4%)
-- 전수 비교보다 느렸다. 부분 인덱스 4개는 합계 약 179MB, 추천 6개가 전수 비교와 같았다(2026-11-08 격리 시험).
-- status는 갤러리 동기화가 animals.status로 맞춘다. 추천 쿼리는 animals.status를 다시 확인한다.
-- 조건(species, status 목록)은 python/app/gallery/pg_store.py의 추천 SQL과 같아야 플래너가 인덱스를 쓴다.
CREATE INDEX idx_lost_gallery_dog_available_animal_hnsw ON lost_gallery_documents
    USING hnsw (animal_vector public.vector_cosine_ops)
    WHERE species = 'DOG' AND status IN ('NOTICE', 'PROTECT');
CREATE INDEX idx_lost_gallery_dog_available_image_hnsw ON lost_gallery_documents
    USING hnsw (image_vector public.vector_cosine_ops)
    WHERE species = 'DOG' AND status IN ('NOTICE', 'PROTECT');
CREATE INDEX idx_lost_gallery_cat_available_animal_hnsw ON lost_gallery_documents
    USING hnsw (animal_vector public.vector_cosine_ops)
    WHERE species = 'CAT' AND status IN ('NOTICE', 'PROTECT');
CREATE INDEX idx_lost_gallery_cat_available_image_hnsw ON lost_gallery_documents
    USING hnsw (image_vector public.vector_cosine_ops)
    WHERE species = 'CAT' AND status IN ('NOTICE', 'PROTECT');
