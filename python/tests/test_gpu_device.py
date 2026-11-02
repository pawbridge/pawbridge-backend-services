import os
import unittest
from unittest.mock import patch, Mock

try:
    import torch
except ModuleNotFoundError as error:
    if error.name != "torch":
        raise
    torch = None

from app.vision.dinov3 import DinoV3Encoder
from app.vision.gpu_device import autorelease_pool, inference_device
from app.vision.inference_gate import InferenceGate


@unittest.skipIf(torch is None, "Requires the dedicated PyTorch runtime")
class GpuDeviceTest(unittest.TestCase):
    def test_default_remains_cuda_even_on_an_mps_host(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(torch.cuda, "is_available", return_value=True):
            self.assertEqual(inference_device(), "cuda")
        with patch.dict(os.environ, {}, clear=True), patch.object(torch.cuda, "is_available", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "cuda GPU is unavailable"):
                inference_device()

    def test_mps_is_explicit_and_requires_disabled_operator_fallback(self):
        for fallback in ("", "1", "0"):
            with self.subTest(fallback=fallback), patch.dict(os.environ, {
                    "LOST_SEARCH_DEVICE": "mps", "PYTORCH_ENABLE_MPS_FALLBACK": fallback
            }), patch.object(torch.backends.mps, "is_available", return_value=True):
                if fallback == "0":
                    self.assertEqual(inference_device(), "mps")
                else:
                    with self.assertRaisesRegex(RuntimeError, "FALLBACK=0"):
                        inference_device()

    def test_unavailable_mps_and_cpu_device_are_not_silently_replaced(self):
        with patch.dict(os.environ, {"LOST_SEARCH_DEVICE": "mps", "PYTORCH_ENABLE_MPS_FALLBACK": "0"}), patch.object(torch.backends.mps, "is_available", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "mps GPU is unavailable"):
                inference_device()
        for device in ("cpu", "auto", "typo"):
            with self.subTest(device=device), patch.dict(os.environ, {"LOST_SEARCH_DEVICE": device}):
                with self.assertRaisesRegex(RuntimeError, "LOST_SEARCH_DEVICE"):
                    inference_device()

    def test_legacy_focus_cannot_accidentally_construct_a_cuda_model_on_mps(self):
        with patch.dict(os.environ, {"LOST_SEARCH_VISUAL_PROFILE": "animal-focus"}), patch("app.vision.gpu_device.inference_device", return_value="mps"):
            with self.assertRaisesRegex(RuntimeError, "legacy animal-focus"):
                DinoV3Encoder()

    def test_mps_oom_releases_gate_clears_mps_cache_and_allows_next_request(self):
        for color_only in (False, True):
            with self.subTest(color_only=color_only):
                encoder = object.__new__(DinoV3Encoder)
                encoder.device = "mps"
                encoder.gate = InferenceGate()
                oom = RuntimeError("MPS backend out of memory (test)")
                if color_only:
                    from app.vision.animal_focus import FocusResult
                    from PIL import Image
                    encoder.focus = Mock()
                    encoder.focus.prepare.side_effect = [oom, FocusResult(Image.new("RGB", (8, 8)), "animal_mask", {"ok": True})]
                    invoke = encoder.describe_coat_color
                    expected = {"ok": True}
                else:
                    encoder._encode_image = Mock(side_effect=[oom, "ok"])
                    invoke, expected = encoder.encode_with_metadata, "ok"
                with patch.object(torch.mps, "empty_cache") as clear, patch.object(torch.cuda, "empty_cache") as cuda_clear:
                    with self.assertRaisesRegex(RuntimeError, "GPU memory exhausted"):
                        invoke(None, "DOG")
                    self.assertFalse(encoder.gate.active)
                    clear.assert_called_once_with()
                    cuda_clear.assert_not_called()
                    self.assertEqual(invoke(None, "DOG"), expected)

    def test_non_oom_model_errors_keep_their_cause_and_release_gate(self):
        encoder = object.__new__(DinoV3Encoder)
        encoder.device = "mps"
        encoder.gate = InferenceGate()
        error = RuntimeError("unsupported model operator")
        encoder._encode_image = Mock(side_effect=error)
        with patch.object(torch.mps, "empty_cache") as clear:
            with self.assertRaises(RuntimeError) as raised:
                encoder.encode_with_metadata(None, "CAT")
            self.assertIs(raised.exception, error)
            self.assertFalse(encoder.gate.active)
            clear.assert_not_called()


class AutoreleasePoolTest(unittest.TestCase):
    def test_mps_work_drains_one_pool_per_request_even_on_error(self):
        objc = Mock()
        objc.objc_autoreleasePoolPush.return_value = 42
        with patch("app.vision.gpu_device._objc", return_value=objc):
            with autorelease_pool("mps"):
                objc.objc_autoreleasePoolPop.assert_not_called()
            with self.assertRaises(ValueError), autorelease_pool("mps"):
                raise ValueError("model failed")
        self.assertEqual(objc.objc_autoreleasePoolPush.call_count, 2)
        self.assertEqual([c.args for c in objc.objc_autoreleasePoolPop.call_args_list], [(42,), (42,)])

    def test_cuda_never_loads_the_objective_c_runtime(self):
        with patch("app.vision.gpu_device._objc", side_effect=AssertionError("loaded")):
            with autorelease_pool("cuda"):
                pass
