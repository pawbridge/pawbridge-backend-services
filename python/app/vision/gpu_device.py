"""Explicit GPU selection; the existing Windows runtime remains CUDA by default."""
from contextlib import contextmanager
from functools import lru_cache
import sys

from app.settings import VisionSettings


def inference_device():
    settings = VisionSettings.load()
    device = settings.device
    if device == "mps" and settings.mps_fallback != "0":
        raise RuntimeError("Set PYTORCH_ENABLE_MPS_FALLBACK=0 before starting the MPS process")
    import torch
    available = torch.cuda.is_available() if device == "cuda" else torch.backends.mps.is_available()
    if not available:
        raise RuntimeError(f"Requested {device} GPU is unavailable; CPU fallback is disabled")
    return device


def is_gpu_oom(error, device):
    import torch
    if device == "cuda":
        return isinstance(error, torch.cuda.OutOfMemoryError)
    return device == "mps" and "MPS backend out of memory" in str(error)


@lru_cache(maxsize=1)
def _objc():
    import ctypes
    objc = ctypes.cdll.LoadLibrary("/usr/lib/libobjc.A.dylib")
    objc.objc_autoreleasePoolPush.restype = ctypes.c_void_p
    objc.objc_autoreleasePoolPop.argtypes = [ctypes.c_void_p]
    return objc


@contextmanager
def autorelease_pool(device):
    # PyTorch MPS leaves Metal command buffers in the thread's pool, which a
    # long-lived Python thread never drains (about 0.27 MB per gallery photo).
    if device != "mps" or sys.platform != "darwin":
        yield
        return
    objc = _objc()
    pool = objc.objc_autoreleasePoolPush()
    try:
        yield
    finally:
        objc.objc_autoreleasePoolPop(pool)
