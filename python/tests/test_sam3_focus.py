import os
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
# CPU image CI intentionally omits PyTorch. These two runtime contracts are
# exercised without skips in the dedicated CUDA environment.
try:
    import torch
except ModuleNotFoundError as error:
    if error.name != 'torch':
        raise
    torch = None
from PIL import Image
from app.vision.sam3_focus import prepare_prediction, load_mapped_checkpoint
from app.vision.dinov3 import DinoV3Encoder, gallery_index


class Sam3ContractTest(unittest.TestCase):
    def test_each_profile_rejects_the_other_profiles_gallery(self):
        indices = {"original": "animals-lost-dinov3-eval-v1",
                   "animal-focus": "animals-lost-dinov3-focus-eval-v1",
                   "sam3-animal-focus": "animals-lost-dinov3-sam3-eval-v1"}
        for profile in indices:
            for owner, index in indices.items():
                with self.subTest(profile=profile, index=index), patch.dict(os.environ, {
                        "LOST_SEARCH_VISUAL_PROFILE": profile, "LOST_SEARCH_INDEX": index}):
                    if profile == owner:
                        self.assertEqual(gallery_index(), index)
                    else:
                        with self.assertRaises(RuntimeError):
                            gallery_index()

    def test_single_animal_masks_background_and_preserves_original(self):
        with Image.new("RGB", (120, 120), "red") as image:
            original = image.tobytes()
            mask = np.zeros((1, 120, 120), dtype=bool)
            mask[0, 20:100, 20:100] = True
            result = prepare_prediction(image, [[10, 10, 110, 110]], mask, [.9])
            try:
                self.assertEqual(result.status, "animal_mask")
                from app.vision.coat_color import valid
                self.assertTrue(valid(result.coat_color))
                self.assertEqual(result.image.size, (256, 256))
                self.assertEqual(result.image.getpixel((0, 0)), (124, 116, 104))
                self.assertEqual(image.tobytes(), original)
            finally:
                result.image.close()

    def test_input_size_384_pads_every_view_to_384_and_uses_its_own_gallery_version(self):
        from app.vision.sam3_focus import FOCUS_VERSION_CLS, FOCUS_VERSION_CLS_384, focus_version
        env = {"LOST_SEARCH_VISUAL_PROFILE": "sam3-animal-focus", "LOST_SEARCH_POOLING": "token"}
        with patch.dict(os.environ, env):
            self.assertEqual(focus_version(), FOCUS_VERSION_CLS)
            with patch.dict(os.environ, {"LOST_SEARCH_INPUT_SIZE": "384"}), Image.new("RGB", (120, 120), "red") as image:
                self.assertEqual(focus_version(), FOCUS_VERSION_CLS_384)
                mask = np.zeros((1, 120, 120), dtype=bool)
                mask[0, 20:100, 20:100] = True
                # 동물 영역과 원본 대체 경로 모두 DINOv3 입력 크기로 바로 패딩해야 한다.
                for result in (prepare_prediction(image, [[10, 10, 110, 110]], mask, [.9]),
                               prepare_prediction(image, np.zeros((0, 4)), np.zeros((0, 120, 120)), np.zeros(0))):
                    with self.subTest(status=result.status):
                        try:
                            self.assertEqual(result.image.size, (384, 384))
                        finally:
                            result.image.close()

    def test_missing_detection_keeps_full_photo_without_color(self):
        with Image.new("RGB", (120, 120), "red") as image:
            result = prepare_prediction(image, np.zeros((0, 4)),
                                        np.zeros((0, 120, 120)), np.ones(0))
            try:
                self.assertEqual(result.status, "original_no_confident_animal")
                self.assertIsNone(result.coat_color)
                self.assertEqual(result.image.getpixel((0, 0)), (255, 0, 0))
            finally:
                result.image.close()

    def test_multiple_detections_keep_full_photo_but_retain_primary_mask_color(self):
        with Image.new("RGB", (120, 120), "red") as image:
            masks = np.zeros((2, 120, 120))
            masks[0, 20:100, 20:100] = 1
            masks[1, 25:95, 25:95] = 1
            result = prepare_prediction(image, [[10, 10, 110, 110], [20, 20, 100, 100]],
                                        masks, [.8, .9])
            try:
                self.assertEqual(result.status, "original_multiple_animals")
                self.assertEqual(result.image.getpixel((0, 0)), (255, 0, 0))
                self.assertEqual(result.coat_color["source"], "primary_mask")
                from app.vision.coat_color import valid
                self.assertTrue(valid(result.coat_color))
            finally:
                result.image.close()

    def test_malformed_model_output_is_failure_not_no_detection(self):
        with Image.new("RGB", (120, 120)) as image:
            for boxes, masks, scores in [([[0, 0, 100, 100]], np.zeros((0, 120, 120)), []),
                                         ([[0, 0, 100, 100]], np.zeros((1, 120, 120)), [np.nan]),
                                         ([[0, 0, 100, 100]], np.full((1, 120, 120), 2), [.9])]:
                with self.subTest(scores=scores), self.assertRaises(RuntimeError):
                    prepare_prediction(image, boxes, masks, scores)

    @unittest.skipIf(torch is None, "Requires the dedicated PyTorch runtime")
    def test_mapped_loader_requires_all_active_keys_and_rejects_unknown_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "weights.pt"
            source = {"detector.weight": torch.full((2, 2), .5),
                      "detector.bias": torch.full((2,), .2),
                      "detector.backbone.vision_backbone.sam2_convs.0.weight": torch.ones(1)}
            model = torch.nn.Linear(2, 2)
            torch.save({"model": source}, path)
            load_mapped_checkpoint(model, path)
            self.assertTrue(torch.equal(model.weight, source["detector.weight"]))
            self.assertTrue(torch.equal(model.bias, source["detector.bias"]))
            for changed in [{k: v for k, v in source.items() if k != "detector.bias"},
                            dict(source, **{"detector.unexpected": torch.ones(1)})]:
                torch.save(changed, path)
                with self.assertRaises(RuntimeError):
                    load_mapped_checkpoint(model, path)

    @unittest.skipIf(torch is None, "Requires the dedicated PyTorch runtime")
    def test_gpu_oom_releases_lock_and_next_request_can_run(self):
        encoder = object.__new__(DinoV3Encoder)
        encoder.device = "cuda"
        from app.vision.inference_gate import InferenceGate
        encoder.gate = InferenceGate()
        with patch.object(encoder, "_encode_image", side_effect=[torch.cuda.OutOfMemoryError(), "ok"]), patch.object(torch.cuda, "empty_cache") as clear:
            with self.assertRaisesRegex(RuntimeError, "GPU memory exhausted"):
                encoder.encode_with_metadata(None, "DOG")
            self.assertFalse(encoder.gate.active)
            clear.assert_called_once_with()
            self.assertEqual(encoder.encode_with_metadata(None, "DOG"), "ok")

    @unittest.skipIf(torch is None, "Requires the dedicated PyTorch runtime")
    def test_color_only_backfill_shares_gate_and_does_not_encode_dino_vectors(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        from app.vision.animal_focus import FocusResult
        from app.vision.inference_gate import InferenceGate
        encoder = object.__new__(DinoV3Encoder)
        encoder.gate = InferenceGate()
        encoder.device = "cuda"
        encoder._vector_for = Mock()
        color = {"test": "descriptor"}
        def prepare(image, species):
            self.assertTrue(encoder.gate.active)
            return FocusResult(Image.new("RGB", (32, 32)), "animal_mask", color)
        encoder.focus = SimpleNamespace(prepare=prepare)
        self.assertEqual(encoder.describe_coat_color(None, "DOG", background=True), color)
        encoder._vector_for.assert_not_called()
        self.assertFalse(encoder.gate.active)
        encoder.focus.prepare = Mock(side_effect=torch.cuda.OutOfMemoryError())
        with patch("torch.cuda.empty_cache") as clear:
            with self.assertRaisesRegex(RuntimeError, "color backfill"):
                encoder.describe_coat_color(None, "DOG", background=True)
            clear.assert_called_once()
        self.assertFalse(encoder.gate.active)


    @unittest.skipIf(torch is None, "Requires the dedicated PyTorch runtime")
    def test_background_prepared_input_reaches_focus_inside_the_shared_gate(self):
        from types import SimpleNamespace
        from app.vision.animal_focus import FocusResult
        from app.vision.inference_gate import InferenceGate
        from app.gallery.runtime import BackgroundEncoder
        encoder = object.__new__(DinoV3Encoder)
        encoder.model_version = "test"
        encoder.device = "mps"  # Real autorelease pool on macOS, no-op elsewhere.
        encoder.gate = InferenceGate()
        encoder._vector_for = lambda image: [1.] + [0.] * 1023
        color = {"test": "descriptor"}
        with Image.new("RGB", (32, 32)) as original, Image.new("RGB", (16, 16)) as small:
            calls = []
            def prepare(image, species, *, prepared_image):
                self.assertTrue(encoder.gate.active)
                self.assertIs(image, original)
                self.assertIs(prepared_image, small)
                self.assertEqual(species, "DOG")
                calls.append(species)
                return FocusResult(Image.new("RGB", (32, 32)), "animal_mask", color)
            encoder.focus = SimpleNamespace(prepare=prepare)
            worker = BackgroundEncoder(encoder)
            with patch.object(encoder.gate, "acquire", wraps=encoder.gate.acquire) as admission:
                self.assertEqual(worker.encode_with_metadata(original, "DOG", prepared_focus_image=small).coat_color, color)
                self.assertFalse(encoder.gate.active)
                self.assertEqual(worker.describe_coat_color(original, "DOG", prepared_focus_image=small), color)
                self.assertFalse(encoder.gate.active)
                self.assertEqual([call.kwargs for call in admission.call_args_list],
                                 [{"background": True}, {"background": True}])
            self.assertEqual(calls, ["DOG", "DOG"])


class Sam3ThroughputTest(unittest.TestCase):
    @unittest.skipIf(torch is None, "Requires the dedicated PyTorch runtime")
    def test_species_text_is_reused_without_reusing_or_mutating_image_state(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        from app.vision.sam3_focus import Sam3Focus
        focus = object.__new__(Sam3Focus)
        focus._species_text = {}
        def encode(prompts, device):
            return {"language_features": torch.tensor([1. if prompts == ["dog"] else 2.], requires_grad=True)}
        backbone = SimpleNamespace(forward_text=Mock(side_effect=encode))
        focus.model = SimpleNamespace(backbone=backbone, _get_dummy_prompt=lambda: "geometry")
        def grounding(state):
            features = state["backbone_out"]
            value = features["language_features"].item()
            features["language_features"].add_(100)
            return (value, features["image_id"], state["geometric_prompt"])
        focus.processor = SimpleNamespace(device="cpu")
        focus._forward_grounding = grounding
        for i, species in enumerate(["DOG", "DOG", "CAT", "DOG"]):
            state = {"backbone_out": {"image_id": i}, "geometric_prompt": "existing"}
            result = focus._set_species_prompt(state, species)
            self.assertEqual(result, (1. if species == "DOG" else 2., i, "existing"))
        self.assertEqual(backbone.forward_text.call_count, 2)
        self.assertEqual(set(focus._species_text), {"DOG", "CAT"})
        self.assertFalse(focus._species_text["DOG"]["language_features"].requires_grad)

    @unittest.skipIf(torch is None, "Requires the dedicated PyTorch runtime")
    def test_failed_text_encoding_is_not_cached_and_next_request_retries(self):
        from types import SimpleNamespace
        from unittest.mock import Mock
        from app.vision.sam3_focus import Sam3Focus
        focus = object.__new__(Sam3Focus)
        focus._species_text = {}
        encode = Mock(side_effect=[RuntimeError("failed text"), {"language_features": torch.ones(1)}])
        focus.model = SimpleNamespace(backbone=SimpleNamespace(forward_text=encode), _get_dummy_prompt=lambda: "geometry")
        focus.processor = SimpleNamespace(device="cpu")
        focus._forward_grounding = lambda state: state["geometric_prompt"]
        with self.assertRaisesRegex(RuntimeError, "failed text"):
            focus._set_species_prompt({"backbone_out": {}}, "DOG")
        self.assertFalse(focus._species_text)
        self.assertEqual(focus._set_species_prompt({"backbone_out": {}}, "DOG"), "geometry")
        self.assertEqual(encode.call_count, 2)


@unittest.skipIf(torch is None, "Requires the dedicated PyTorch runtime")
class Sam3GraphCacheTest(unittest.TestCase):
    """모양별 그래프를 만드는 변환·비교를 GPU 밖으로 옮긴 set_image/_forward_grounding 재구현을 검증한다."""

    def _focus(self, detections, size=(1, 1)):
        from types import SimpleNamespace
        from app.vision.sam3_focus import Sam3Focus
        try:
            from sam3.model.sam3_image_processor import Sam3Processor
        except ModuleNotFoundError:
            self.skipTest("Requires the pinned SAM 3 package")
        generator = torch.Generator().manual_seed(7)
        logits = torch.full((1, 200, 1), -9.)
        logits[0, :detections, 0] = 9.
        outputs = {"pred_logits": logits, "pred_boxes": torch.rand((1, 200, 4), generator=generator) * .5 + .25,
                   "pred_masks": torch.randn((1, 200, 8, 8), generator=generator), "presence_logit_dec": torch.tensor([9.])}
        seen = []
        model = SimpleNamespace(forward_grounding=lambda **kwargs: outputs, inst_interactive_predictor=None,
                                backbone=SimpleNamespace(forward_image=lambda pixels: seen.append(pixels) or {"pixels": pixels}))
        focus = object.__new__(Sam3Focus)
        focus.device, focus.model, focus._species_text = "cpu", model, {}
        focus.processor = Sam3Processor(model, device="cpu", confidence_threshold=.5)
        return focus, seen

    def test_image_tensor_and_grounding_match_the_pinned_processor(self):
        focus, seen = self._focus(detections=2)
        with Image.effect_noise((37, 23), 64).convert("RGB") as image, torch.inference_mode():
            ours, theirs = focus._set_image(image), focus.processor.set_image(image)
            self.assertTrue(torch.equal(seen[0], seen[1]))
            self.assertEqual(seen[0].shape, (1, 3, 1008, 1008))
            self.assertEqual((ours["original_height"], ours["original_width"]), (23, 37))
            for state in (ours, theirs):
                state["geometric_prompt"] = "geometry"
            result, expected = focus._forward_grounding(ours), focus.processor._forward_grounding(theirs)
        self.assertEqual(result["masks"].dtype, torch.bool)
        self.assertEqual(result["masks"].shape, (2, 1, 23, 37))
        for key in ("boxes", "scores", "masks"):
            self.assertTrue(torch.equal(result[key], expected[key]), key)
        self.assertEqual(set(result), {"boxes", "scores", "masks"})

    def test_no_confident_detection_yields_empty_masks_for_the_full_photo_path(self):
        from app.vision.sam3_focus import prepare_prediction
        focus, _ = self._focus(detections=0)
        with Image.new("RGB", (16, 12), "red") as image, torch.inference_mode():
            state = focus._set_image(image)
            state["geometric_prompt"] = "geometry"
            result = focus._forward_grounding(state)
            self.assertEqual(result["masks"].shape, (0, 1, 12, 16))
            self.assertEqual(result["boxes"].shape, (0, 4))
            prepared = prepare_prediction(image, result["boxes"].numpy(), result["masks"][:, 0].numpy(),
                                          result["scores"].numpy())
        try:
            self.assertEqual(prepared.status, "original_no_confident_animal")
        finally:
            prepared.image.close()


@unittest.skipIf(torch is None, "Requires the dedicated PyTorch runtime")
class Sam3FocusCacheTest(unittest.TestCase):
    """SAM 3 출력 저장: 같은 사진은 저장한 출력으로 같은 결과를 내고, 꺼져 있으면 아무것도 쓰지 않는다."""

    def _focus(self, calls):
        from app.vision.sam3_focus import Sam3Focus
        focus = object.__new__(Sam3Focus)
        focus.device = "cpu"

        def set_image(working):
            calls.append(working.size)
            return {}

        def species_prompt(_state, _species):
            height, width = 120, 160
            masks = torch.zeros((1, 1, height, width), dtype=torch.bool)
            masks[0, 0, 20:100, 30:130] = True
            return {"boxes": torch.tensor([[25., 15., 135., 105.]]), "scores": torch.tensor([.93]), "masks": masks}

        focus._set_image, focus._set_species_prompt = set_image, species_prompt
        return focus

    def test_cached_output_reproduces_the_same_crop_without_running_sam(self):
        calls = []
        focus = self._focus(calls)
        with tempfile.TemporaryDirectory() as root, patch.dict(os.environ, {"LOST_SEARCH_FOCUS_CACHE_DIR": root}), \
                Image.effect_noise((160, 120), 64).convert("RGB") as image:
            first = focus.prepare(image, "DOG")
            self.assertEqual(len(calls), 1)
            self.assertEqual(len(list(Path(root).rglob("*.npz"))), 1)
            # 두 번째는 SAM 3를 부르면 실패하게 해 두고, 저장한 출력만으로 같은 결과가 나오는지 본다.
            focus._set_image = lambda _working: self.fail("SAM 3 must not run on a cache hit")
            second = focus.prepare(image, "DOG")
            try:
                self.assertEqual(first.status, "animal_mask")
                self.assertEqual(second.status, first.status)
                self.assertEqual(second.coat_color, first.coat_color)
                self.assertEqual(second.image.tobytes(), first.image.tobytes())
            finally:
                first.image.close(); second.image.close()

    def test_other_species_or_photo_does_not_reuse_and_unreadable_file_is_recomputed(self):
        calls = []
        focus = self._focus(calls)
        with tempfile.TemporaryDirectory() as root, patch.dict(os.environ, {"LOST_SEARCH_FOCUS_CACHE_DIR": root}), \
                Image.effect_noise((160, 120), 64).convert("RGB") as image, Image.effect_noise((160, 120), 32).convert("RGB") as other:
            for photo, species in ((image, "DOG"), (image, "CAT"), (other, "DOG")):
                focus.prepare(photo, species).image.close()
            self.assertEqual(len(calls), 3)
            saved = sorted(Path(root).rglob("*.npz"))
            self.assertEqual(len(saved), 3)
            for path in saved:
                path.write_bytes(b"broken")
            focus.prepare(image, "DOG").image.close()
            self.assertEqual(len(calls), 4)
            self.assertFalse(list(Path(root).rglob("*.tmp")))

    def test_disabled_cache_writes_nothing(self):
        calls = []
        focus = self._focus(calls)
        with tempfile.TemporaryDirectory() as root, patch.dict(os.environ, {"LOST_SEARCH_FOCUS_CACHE_DIR": ""}), \
                Image.effect_noise((160, 120), 64).convert("RGB") as image:
            focus.prepare(image, "DOG").image.close()
            focus.prepare(image, "DOG").image.close()
            self.assertEqual(len(calls), 2)
            self.assertFalse(list(Path(root).rglob("*")))
