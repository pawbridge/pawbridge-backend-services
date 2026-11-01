"""One native MPS API and its in-process gallery refresh, using immutable source."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import sys
from urllib.parse import urlsplit


def validate(env):
    required = ('DINOV3_CHECKPOINT', 'SAM3_CHECKPOINT', 'INTERNAL_API_KEY', 'LOST_SEARCH_INDEX',
                'LOST_PG_DSN', 'LOST_GALLERY_STATE_DIR', 'LOST_GALLERY_PHOTO_ROOT')
    if any(not env.get(name) for name in required):
        raise ValueError('Missing native setting')
    if env.get('LOST_STORAGE_BACKEND') != 'postgresql' or env.get('LOST_SEARCH_VISUAL_PROFILE') != 'sam3-animal-focus':
        raise ValueError('Unsupported native storage/profile')
    db = urlsplit(env['LOST_PG_DSN'])
    if (db.scheme not in ('postgres', 'postgresql') or db.hostname != '127.0.0.1'
            or db.port != 35436 or db.path != '/pawbridge' or db.username != 'pawbridge_vector'
            or not db.password or db.query or db.fragment):
        raise ValueError('Unexpected native database target')
    if env.get('LOST_GALLERY_SYNC_ENABLED') not in ('true', 'false'):
        raise ValueError('Explicit gallery enablement required')
    state, photos = (Path(env[key]) for key in ('LOST_GALLERY_STATE_DIR', 'LOST_GALLERY_PHOTO_ROOT'))
    if not state.is_absolute() or not photos.is_absolute() or state == photos or state not in photos.parents:
        raise ValueError('Photos must be inside persistent native state')
    if env['LOST_GALLERY_SYNC_ENABLED'] == 'true':
        if env.get('LOST_GALLERY_PROTOCOL') != 'v2' or env.get('LOST_GALLERY_SOURCE_URL') != 'http://127.0.0.1:35437/internal/animals/lost-gallery':
            raise ValueError('Explicit local paged gallery feed required')
        if not env.get('LOST_GALLERY_SOURCE_KEY') or not env.get('LOST_GALLERY_R2_HOST'):
            raise ValueError('Missing gallery authentication or photo origin')
        if not 30 <= int(env.get('LOST_GALLERY_INTERVAL_SECONDS', '0')) <= 86400:
            raise ValueError('Invalid gallery interval')
    for name in ('DINOV3_CHECKPOINT', 'SAM3_CHECKPOINT'):
        if not Path(env[name]).is_file():
            raise ValueError('Local model checkpoint missing')


def source_digests(source):
    # Every file, not only .py: the SAM3 patch check below trusts deploy/macos/sam3-manifest.json.
    files = sorted(p for p in Path(source).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    return {str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}


def source_check(source, receipt):
    manifest = json.loads(Path(receipt).read_text())
    actual = source_digests(source)
    if 'files' in manifest:
        expected = manifest['files']
    else:
        # Receipts written before 2026-11 list only .py files; accepted so older sources still roll back.
        print('Legacy native source receipt: only .py files verified', file=sys.stderr)
        actual = {name: digest for name, digest in actual.items() if name.endswith('.py')}
        expected = manifest['python_files']
    if actual != expected:
        raise ValueError('Native source differs from approved receipt')


def main():
    if len(sys.argv) == 3 and sys.argv[1] == '--receipt':
        # Write the receipt with the same rule the check uses: --receipt <extracted source> > receipt.json
        print(json.dumps({'files': source_digests(Path(sys.argv[2]).resolve())}, indent=2, sort_keys=True))
        return
    if len(sys.argv) != 2:
        raise ValueError('Private native environment JSON path required')
    config = Path(sys.argv[1])
    if config.is_symlink() or config.stat().st_uid != os.getuid() or config.stat().st_mode & 0o077:
        raise ValueError('Private owned native configuration required')
    values = json.loads(config.read_text())
    if not isinstance(values, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in values.items()):
        raise ValueError('Invalid native settings')
    os.environ.update(values)
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        raise RuntimeError('Native Apple Silicon required')
    source = Path(os.environ['PAWBRIDGE_NATIVE_SOURCE']).resolve()
    source_check(source, os.environ['PAWBRIDGE_NATIVE_SOURCE_RECEIPT'])
    validate(os.environ)
    os.environ.update(LOST_SEARCH_DEVICE='mps', PYTORCH_ENABLE_MPS_FALLBACK='0',
                      HF_HUB_OFFLINE='1', HF_HUB_DISABLE_TELEMETRY='1')
    manifest = json.loads((source / 'deploy/macos/sam3-manifest.json').read_text())
    spec = importlib.util.find_spec('sam3')
    if spec is None or spec.origin is None:
        raise RuntimeError('Pinned SAM3 package missing')
    parent = Path(spec.origin).parent.parent
    for name, expected in manifest['patched_files'].items():
        if hashlib.sha256((parent / name).read_bytes()).hexdigest() != expected:
            raise RuntimeError('Pinned SAM3 patch changed')
    from mac_background_jobs import private_directory
    private_directory(os.environ['LOST_GALLERY_STATE_DIR'])
    private_directory(os.environ['LOST_GALLERY_PHOTO_ROOT'])
    sys.path.insert(0, str(source))
    import uvicorn
    # Sources before the 2026-10-28 package layout keep the old entrypoint, so rollback still starts.
    entrypoint = 'app.entrypoints.vision_api:app' if (source / 'app/entrypoints/vision_api.py').is_file() else 'app.lost_main:app'
    uvicorn.run(entrypoint, host='127.0.0.1', port=8001, workers=1, access_log=False, reload=False)


if __name__ == '__main__':
    main()
