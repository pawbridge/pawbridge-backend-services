"""GPU search entry point for the native Mac MPS process."""
from contextlib import asynccontextmanager, nullcontext
import anyio
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from app.internal_auth import require_configured_key
from app.lost_search.router import router
from app.recommendation.router import router as recommendation_router
from app.settings import GallerySyncSettings, PostgresSettings
from app.vision.dinov3 import get_encoder, gallery_index, visual_profile


def validate_focus_gallery(encoder):
    from app.gallery.storage import get_postgresql_store
    from app.vision.coat_color import VERSION as COLOR_VERSION, ranking_weight
    get_postgresql_store().validate(gallery_index(), encoder.model_version,
                                    COLOR_VERSION if ranking_weight() else None)


@asynccontextmanager
async def lifespan(app):
    # Validate every setting before loading weights, so a missing value stops startup.
    require_configured_key()
    gallery_index()
    PostgresSettings.load()
    sync = GallerySyncSettings.load()
    from app.vision.coat_color import ranking_weight
    if ranking_weight() and visual_profile() != "sam3-animal-focus":
        raise RuntimeError("Coat-color ranking requires the SAM 3 gallery")
    enabled, state_dir = sync.enabled, sync.state_dir
    if enabled and (visual_profile() != "sam3-animal-focus" or not state_dir):
        raise RuntimeError("Automatic gallery refresh requires SAM 3 and a persistent state directory")
    from app.gallery.runtime import runtime_owner, GalleryRefresh
    from app.gallery.source import GallerySource
    from app.gallery.pages import PagedGallerySource
    source_class = PagedGallerySource if sync.protocol == 'v2' else GallerySource
    from app.gallery.storage import storage_session
    from app.gallery.pg_store import ColorGalleryRefreshRequired, GalleryUnavailable
    if visual_profile() != 'sam3-animal-focus':
        raise RuntimeError('PostgreSQL gallery requires the SAM 3 profile')
    # Acquire ownership before loading weights; CLI and API must use the same state directory.
    with storage_session() as store, (runtime_owner(state_dir) if state_dir else nullcontext()):
        encoder = await anyio.to_thread.run_sync(get_encoder)
        ready = True
        try:
            await anyio.to_thread.run_sync(validate_focus_gallery, encoder)
        except (ColorGalleryRefreshRequired, GalleryUnavailable):
            if not enabled:
                raise
            ready = False
        refresh = None
        if enabled:
            source = source_class(sync.source_url, sync.source_key, sync.r2_host, state_dir, sync.photo_root)
            try:
                refresh = GalleryRefresh(store, encoder, source, gallery_index(), state_dir,
                                         interval=sync.interval_seconds, ready=ready)
                app.state.gallery_refresh = refresh
                refresh.start()
            except BaseException:
                source.close()
                raise
        try:
            yield
        finally:
            if refresh:
                await anyio.to_thread.run_sync(refresh.close)


app = FastAPI(title="PawBridge Lost Animal Search", lifespan=lifespan,
              docs_url=None, redoc_url=None, openapi_url=None)
app.include_router(router, prefix="/internal/animals")
app.include_router(recommendation_router, prefix="/internal/animals")


@app.get("/health")
def health(request: Request):
    refresh = getattr(request.app.state, "gallery_refresh", None)
    ready = refresh is None or refresh.ready
    return JSONResponse({"status": "ok" if ready else "initializing",
                         "modelVersion": get_encoder().model_version,
                         "galleryRefresh": refresh.status if refresh else {"state": "disabled"}},
                        status_code=200 if ready else 503)
