"""Separate CPU process: uvicorn app.entrypoints.photo_api:app --workers 1."""

import asyncio

import anyio
from fastapi import Depends, FastAPI, HTTPException, Request, Response

from app.internal_auth import require_internal_key, require_key_at_startup
from app.photo.optimizer import InvalidPhoto, MAX_INPUT_BYTES, PhotoTooLarge, optimize_photo

BODY_TIMEOUT_SECONDS = 15


def create_app() -> FastAPI:
    app = FastAPI(title="PawBridge Photo Optimizer", lifespan=require_key_at_startup,
                  docs_url=None, redoc_url=None, openapi_url=None)
    gate = anyio.Semaphore(1)

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    @app.post("/internal/photos/optimize", dependencies=[Depends(require_internal_key)])
    async def optimize(request: Request):
        try:
            gate.acquire_nowait()
        except anyio.WouldBlock as exc:
            raise HTTPException(503, "Photo service is busy", headers={"Retry-After": "3"}) from exc
        release_here = True
        try:
            if request.headers.get("content-encoding", "identity") != "identity":
                raise HTTPException(415, "Encoded request bodies are not supported")
            body = bytearray()
            try:
                with anyio.fail_after(BODY_TIMEOUT_SECONDS):
                    async for chunk in request.stream():
                        if len(body) + len(chunk) > MAX_INPUT_BYTES:
                            raise HTTPException(413, "Photo exceeds 10 MiB")
                        body.extend(chunk)
            except TimeoutError as exc:
                raise HTTPException(408, "Photo upload timed out") from exc
            # A cancelled HTTP task must not free capacity while its CPU thread runs.
            # The shielded worker owns the permit after scheduling, even on disconnect.
            worker = asyncio.create_task(anyio.to_thread.run_sync(optimize_photo, bytes(body)))

            def finished(task):
                gate.release()
                if not task.cancelled():
                    task.exception()  # Retrieve failures even when the client has left.

            worker.add_done_callback(finished)
            release_here = False
            result = await asyncio.shield(worker)
            return Response(result.data, media_type=result.content_type, headers={
                "Cache-Control": "no-store",
                "X-Source-Sha256": result.source_sha256,
                "X-Stored-Sha256": result.stored_sha256,
                "X-Photo-Width": str(result.width),
                "X-Photo-Height": str(result.height),
                "X-Photo-Recipe": result.recipe,
            })
        except PhotoTooLarge as exc:
            raise HTTPException(413, str(exc)) from exc
        except InvalidPhoto as exc:
            raise HTTPException(422, str(exc)) from exc
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(503, "Photo optimization failed", headers={"Retry-After": "3"}) from exc
        finally:
            if release_here:
                gate.release()

    return app


app = create_app()
