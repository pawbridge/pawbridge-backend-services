"""Pinned SAM 3 image-only segmentation; no model downloads or image synthesis."""
from contextlib import nullcontext
import hashlib
import logging
import os
from pathlib import Path
import tempfile

from app.settings import VisionSettings
from app.vision.animal_focus import FocusResult, prepare_focus, square_image

CHECKPOINT_SHA256 = "9999e2341ceef5e136daa386eecb55cb414446a00ac2b55eb2dfd2f7c3cf8c9e"
FOCUS_VERSION = "dinov3-large-sam39999e234-dual-pad256-v1"
# CLS-token vectors are not comparable with patch-average ones, so their galleries carry another version.
FOCUS_VERSION_CLS = "dinov3-large-sam39999e234-dual-pad256-cls-v1"
# 384 입력 벡터는 256과 비교할 수 없으므로 갤러리 버전을 따로 둔다.
FOCUS_VERSION_CLS_384 = "dinov3-large-sam39999e234-dual-pad384-cls-v1"
CLS_VERSIONS = frozenset({FOCUS_VERSION_CLS, FOCUS_VERSION_CLS_384})


def focus_version():
    settings = VisionSettings.load()
    if settings.pooling != "token":
        return FOCUS_VERSION
    return FOCUS_VERSION_CLS_384 if settings.input_size == 384 else FOCUS_VERSION_CLS


FOCUS_CACHE_VERSION = "sam3-focus-cache-v1"


def focus_cache_path(working, species):
    """SAM 3 입력 사진(1024 축소본)과 체크포인트, 종으로 정해지는 저장 위치. 설정이 비어 있으면 None."""
    root = VisionSettings.load().focus_cache_dir
    if not root:
        return None
    digest = hashlib.sha256()
    # 같은 사진이라도 체크포인트, 종 문구, 크기가 다르면 다른 결과이므로 모두 키에 넣는다.
    digest.update(f"{FOCUS_CACHE_VERSION}:{CHECKPOINT_SHA256}:{species}:{working.mode}:{working.size}:".encode())
    digest.update(working.tobytes())
    name = digest.hexdigest()
    return Path(root) / name[:2] / f"{name}.npz"


def read_focus_cache(path):
    import numpy as np
    try:
        with np.load(path) as data:
            count, height, width = (int(v) for v in data["mask_shape"])
            masks = np.unpackbits(data["masks"], count=count * height * width).reshape(count, height, width).astype(bool)
            return data["boxes"], masks, data["scores"]
    except FileNotFoundError:
        return None
    except (OSError, ValueError, KeyError):
        # 깨진 파일은 쓰지 않고 다시 계산한다. 다시 계산한 결과가 덮어쓴다.
        logging.getLogger(__name__).warning("Ignoring unreadable SAM 3 focus cache")
        return None


def write_focus_cache(path, boxes, masks, scores):
    import numpy as np
    masks = np.asarray(masks, dtype=bool)
    temporary = None
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        # 다른 프로세스가 반쯤 쓴 파일을 읽지 않도록 임시 파일에 쓴 뒤 이름을 바꾼다.
        with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".tmp", delete=False) as handle:
            temporary = handle.name
            np.savez_compressed(handle, boxes=np.asarray(boxes, dtype=np.float32), scores=np.asarray(scores, dtype=np.float32),
                                masks=np.packbits(masks), mask_shape=np.array(masks.shape, dtype=np.int64))
        os.replace(temporary, path)
    except OSError:
        # 저장은 다음 재계산을 빠르게 하려는 것뿐이라 실패해도 이번 결과는 그대로 쓴다.
        logging.getLogger(__name__).warning("Cannot write SAM 3 focus cache")
        if temporary and os.path.exists(temporary):
            os.unlink(temporary)


def load_mapped_checkpoint(model, checkpoint_path):
    import torch
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=True, mmap=True)
    if "model" in checkpoint and isinstance(checkpoint["model"], dict):
        checkpoint = checkpoint["model"]
    state = {key.removeprefix("detector."): value for key, value in checkpoint.items()
             if key.startswith("detector.")}
    required = set(model.state_dict())
    extra = set(state) - required
    # The pinned checkpoint also carries inactive interactive-predictor convolutions.
    if not required <= set(state) or any(
            not key.startswith("backbone.vision_backbone.sam2_convs.") for key in extra):
        raise RuntimeError("SAM 3 checkpoint does not match the image-only model")
    model.load_state_dict({key: state[key] for key in required}, strict=True, assign=True)


def prepare_prediction(image, boxes, masks, scores):
    import numpy as np
    boxes, masks, scores = np.asarray(boxes), np.asarray(masks), np.asarray(scores)
    if (scores.ndim != 1 or boxes.shape != (len(scores), 4)
            or masks.shape != (len(scores), image.height, image.width)
            or not np.isfinite(boxes).all() or not np.isfinite(scores).all()
            or not np.isfinite(masks).all()
            or (scores < 0).any() or (scores > 1).any()
            or (masks < 0).any() or (masks > 1).any()):
        raise RuntimeError("Invalid SAM 3 prediction")
    if len(scores) == 0:
        return FocusResult(square_image(image), "original_no_confident_animal")
    if len(scores) > 1:
        # Keep the identity embedding conservative (the full photo), but retain
        # color evidence from the primary foreground instead of silently making
        # every multi-detection candidate color-neutral. Mask area weighted by
        # confidence favors the main subject while the descriptor records that
        # it came from a primary, not unambiguous, mask.
        areas = (masks >= .5).sum(axis=(1, 2))
        selected = max(range(len(scores)), key=lambda i: (areas[i] * scores[i], scores[i]))
        box = boxes[selected].tolist()
        box[0], box[1] = max(0, box[0]), max(0, box[1])
        box[2], box[3] = min(image.width, box[2]), min(image.height, box[3])
        focused = prepare_focus(image, box, masks[selected].astype("float32"),
                                color_source="primary_mask")
        try:
            return FocusResult(square_image(image), "original_multiple_animals",
                               focused.coat_color)
        finally:
            focused.image.close()
    # SAM's floating point boxes can extend slightly past the image boundary.
    box = boxes[0].tolist()
    box[0], box[1] = max(0, box[0]), max(0, box[1])
    box[2], box[3] = min(image.width, box[2]), min(image.height, box[3])
    return prepare_focus(image, box, masks[0].astype("float32"))


class Sam3Focus:
    def __init__(self):
        from app.vision.gpu_device import inference_device
        self.device = inference_device()
        import torch
        from sam3.model_builder import build_sam3_image_model
        from sam3.model.sam3_image_processor import Sam3Processor
        if self.device == "cuda" and not torch.cuda.is_bf16_supported():
            raise RuntimeError("SAM 3 requires a BF16-capable CUDA GPU")
        checkpoint = VisionSettings.load().sam3_checkpoint
        if not checkpoint:
            raise RuntimeError("SAM3_CHECKPOINT is required")
        checkpoint = Path(checkpoint)
        digest = hashlib.sha256()
        with checkpoint.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != CHECKPOINT_SHA256:
            raise RuntimeError("SAM 3 checkpoint hash mismatch")
        # Build on CPU without loading weights, then assign the mapped tensors.
        # This avoids global builder monkey-patches and a second full CPU copy.
        model = build_sam3_image_model(device="cpu", checkpoint_path=None,
                                      load_from_HF=False, enable_inst_interactivity=False,
                                      compile=False)
        load_mapped_checkpoint(model, checkpoint)
        self.model = model.eval().to(self.device)
        self.processor = Sam3Processor(self.model, device=self.device, confidence_threshold=0.5)
        self._species_text = {}

    # MPS는 dtype 변환과 비교 연산을 텐서 모양마다 그래프로 컴파일해 프로세스가
    # 끝날 때까지 보관한다. 사진 크기가 수천 가지라 프로세서의 set_image/
    # _forward_grounding을 그대로 쓰면 갤러리 빌드 동안 수 GB가 쌓인다. 리사이즈·
    # 보간·복사는 그래프 없이 Metal 커널로 돌므로 변환과 임계값 비교만 CPU로 옮긴다.
    # 결과 값은 고정된 프로세서 경로와 비트 단위로 같다.

    def _set_image(self, image):
        import torch
        from torchvision.transforms import v2
        if self.model.inst_interactive_predictor is not None:
            raise RuntimeError("SAM 3 focus expects the image-only model")
        width, height = image.size
        pixels = v2.functional.to_image(image).to(torch.float32).to(self.device)
        pixels = v2.functional.resize(pixels, [self.processor.resolution] * 2, antialias=True)
        pixels = pixels.round_().to(torch.uint8)  # torchvision이 uint8 입력에 하는 마무리와 같음
        pixels = self.processor.transform(pixels).unsqueeze(0)
        return {"original_height": height, "original_width": width,
                "backbone_out": self.model.backbone.forward_image(pixels)}

    def _forward_grounding(self, state):
        import torch
        from sam3.model import box_ops
        from sam3.model.data_misc import interpolate
        outputs = self.model.forward_grounding(backbone_out=state["backbone_out"],
                                               find_input=self.processor.find_stage,
                                               geometric_prompt=state["geometric_prompt"],
                                               find_target=None)
        presence = outputs["presence_logit_dec"].sigmoid().unsqueeze(1)
        scores = (outputs["pred_logits"].sigmoid() * presence).squeeze(-1)
        keep = scores > self.processor.confidence_threshold
        height, width = state["original_height"], state["original_width"]
        scale = torch.tensor([width, height, width, height]).to(self.processor.device)
        boxes = box_ops.box_cxcywh_to_xyxy(outputs["pred_boxes"][keep]) * scale[None, :]
        masks = interpolate(outputs["pred_masks"][keep].unsqueeze(1), (height, width),
                            mode="bilinear", align_corners=False).sigmoid()
        return {"boxes": boxes, "scores": scores[keep], "masks": masks.cpu() > 0.5}

    def _set_species_prompt(self, state, species):
        # Same pinned processor path as set_text_prompt, except for the two fixed
        # text-only outputs. The encoder gate serializes use of this model.
        if species not in {"DOG", "CAT"}:
            raise ValueError("Animal focus requires DOG or CAT")
        if "backbone_out" not in state:
            raise ValueError("Image features are required before a species prompt")
        if species not in self._species_text:
            outputs = self.model.backbone.forward_text([species.lower()], device=self.processor.device)
            self._species_text[species] = {key: value.detach().clone() for key, value in outputs.items()}
        # Grounding receives private tensors/dict so per-image state cannot poison
        # the next request. Only text features, never photo features, are cached.
        state["backbone_out"].update({key: value.clone() for key, value in self._species_text[species].items()})
        if "geometric_prompt" not in state:
            state["geometric_prompt"] = self.model._get_dummy_prompt()
        return self._forward_grounding(state)

    def prepare(self, image, species, *, prepared_image=None):
        import torch
        from PIL import Image
        if species not in {"DOG", "CAT"}:
            raise ValueError("Animal focus requires DOG or CAT")
        context = image.copy() if prepared_image is None else nullcontext(prepared_image)
        with context as working:
            if prepared_image is None:
                working.thumbnail((1024, 1024), Image.Resampling.BICUBIC)
            elif working.mode != "RGB" or max(working.size) > 1024:
                raise ValueError("Prepared SAM 3 image must be bounded RGB")
            cache = focus_cache_path(working, species)
            cached = read_focus_cache(cache) if cache else None
            if cached is not None:
                boxes, masks, scores = cached
            else:
                with torch.inference_mode(), torch.autocast(self.device, dtype=torch.bfloat16):
                    state = self._set_image(working)
                    prediction = self._set_species_prompt(state, species)
                boxes = prediction["boxes"].detach().float().cpu().numpy()
                masks = prediction["masks"].detach().cpu().numpy()
                scores = prediction["scores"].detach().float().cpu().numpy()
                if masks.ndim == 4 and masks.shape[1] == 1:
                    masks = masks[:, 0]
                if cache:
                    write_focus_cache(cache, boxes, masks, scores)
            return prepare_prediction(working, boxes, masks, scores)
