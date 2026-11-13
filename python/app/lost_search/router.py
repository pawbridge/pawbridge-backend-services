import asyncio
from datetime import date
from typing import Literal

import anyio
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field, ValidationError, field_validator
from starlette.datastructures import UploadFile
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.internal_auth import require_internal_key
from app.lost_search.service import MAX_IMAGE_BYTES, InvalidPhoto, search_photo

router = APIRouter()
# 검색은 한 번에 하나(GPU)다. 그동안 온 요청은 도착 순서대로 기다린다(asyncio.Lock은 먼저 기다린 쪽이 먼저 받는다).
# 기다리는 요청은 MAX_WAITING개까지, 기다리는 시간은 WAIT_SECONDS까지다. 백엔드 읽기 제한 45초
# (PythonLostSearchClient) 안에 끝나도록 검색 한 번(약 2초)과 업로드를 빼고 여유를 둔다.
_gate = asyncio.Lock()
MAX_WAITING = 10
WAIT_SECONDS = 30
_waiting = 0
MAX_REQUEST_BYTES = MAX_IMAGE_BYTES + 64 * 1024


class SearchConditions(BaseModel):
    species: Literal["DOG", "CAT"]
    lostDate: date | None = None
    region: str | None = Field(default=None, max_length=100)
    description: str | None = Field(default=None, max_length=500)
    includeAdoptedOrReturned: bool = False
    includeDeceased: bool = False

    @field_validator("region", "description", mode="before")
    @classmethod
    def normalize_optional_text(cls, value):
        return (value.strip() or None) if isinstance(value, str) else value


@router.post("/lost-candidates", dependencies=[Depends(require_internal_key)])
async def lost_candidates(request: Request):
    refresh = getattr(request.app.state, "gallery_refresh", None)
    if refresh is not None and not refresh.ready:
        raise HTTPException(503, "검색 자료를 준비하고 있습니다. 잠시 후 다시 시도해 주세요")
    await _wait_for_turn()
    try:
        # Limit total body before multipart parsing/spooling, even without Content-Length.
        body = bytearray()
        try:
            with anyio.fail_after(15):
                async for chunk in request.stream():
                    if len(body) + len(chunk) > MAX_REQUEST_BYTES:
                        raise HTTPException(413, "사진은 5MiB 이하이어야 합니다")
                    body.extend(chunk)
        except TimeoutError as exc:
            raise HTTPException(408, "사진 업로드 시간이 초과됐습니다") from exc
        async def receive():
            return {"type": "http.request", "body": bytes(body), "more_body": False}
        parsed = Request(request.scope, receive)
        try:
            async with parsed.form(max_files=1, max_fields=6) as form:
                if set(form.keys()) - {"image", "species", "lostDate", "region", "description",
                                       "includeAdoptedOrReturned", "includeDeceased"}:
                    raise HTTPException(422, "지원하지 않는 입력입니다")
                if len(form.multi_items()) != len(form):
                    raise HTTPException(422, "중복된 입력입니다")
                photo = form.get("image")
                if not isinstance(photo, UploadFile):
                    raise HTTPException(422, "사진을 선택해 주세요")
                conditions = SearchConditions(**{k: (v or None) for k, v in form.items() if k != "image"})
                data = await photo.read(MAX_IMAGE_BYTES + 1)
                if len(data) > MAX_IMAGE_BYTES:
                    raise HTTPException(413, "사진은 5MiB 이하이어야 합니다")
            # Do not abandon a running thread on cancellation: keep the gate until it exits.
            return await anyio.to_thread.run_sync(
                search_photo, data, conditions.species, conditions.lostDate,
                conditions.region, conditions.description, conditions.includeAdoptedOrReturned,
                conditions.includeDeceased)
        except ValidationError as exc:
            raise HTTPException(422, "종류·날짜·지역·특징 입력을 확인해 주세요") from exc
        except InvalidPhoto as exc:
            raise HTTPException(422, str(exc)) from exc
        except StarletteHTTPException:
            raise
        except Exception as exc:
            raise HTTPException(503, "검색을 완료하지 못했습니다. 다시 시도해 주세요") from exc
    finally:
        _gate.release()


async def _wait_for_turn():
    """차례가 오면 _gate를 잡고 돌아온다. 줄이 꽉 찼거나 오래 기다리면 503이다(잡지 않은 채로)."""
    global _waiting
    if _waiting >= MAX_WAITING:
        raise HTTPException(503, "검색 요청이 많습니다. 잠시 후 다시 시도해 주세요", headers={"Retry-After": "3"})
    _waiting += 1
    try:
        with anyio.fail_after(WAIT_SECONDS):
            await _gate.acquire()
    except TimeoutError as exc:
        raise HTTPException(503, "검색이 밀려 있습니다. 잠시 후 다시 시도해 주세요", headers={"Retry-After": "3"}) from exc
    finally:
        _waiting -= 1
