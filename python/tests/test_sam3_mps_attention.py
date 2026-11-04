"""deploy/macos/sam3-mps.patch 가 만드는 sam3/perflib/mps_attention.py 의 계약 시험.

패치 파일에서 그 새 파일 조각의 '+' 줄을 꺼내 그대로 실행하므로 SAM 3 패키지 설치 없이 돈다.
PyTorch 가 없는 CPU 이미지에서는 manifest 일치 시험만 돌고 나머지는 건너뛴다. MPS 시험은 Apple GPU 에서만 돈다.
"""
import hashlib
import json
import os
import types
import unittest
from pathlib import Path
from unittest.mock import patch

try:
    import torch
    import torch.nn.functional as F
except ModuleNotFoundError as error:
    if error.name != 'torch':
        raise
    torch = None

DEPLOY = Path(__file__).resolve().parents[1] / 'deploy/macos'
PATCH = DEPLOY / 'sam3-mps.patch'
TARGET = 'sam3/perflib/mps_attention.py'


def patched_source():
    """패치에서 TARGET 새 파일 조각의 본문(줄 앞 '+' 제거)을 돌려준다."""
    lines = PATCH.read_text().split('\n')
    start = lines.index(f'+++ b/{TARGET}')
    assert lines[start - 1] == '--- /dev/null', '새 파일 조각이어야 한다'
    assert lines[start + 1].startswith('@@ -0,0 +1,'), '새 파일 조각 머리말'
    body = []
    for line in lines[start + 2:]:
        if line.startswith('--- ') or line == '':
            break
        assert line.startswith('+'), f'새 파일 조각에 제거 줄이 있다: {line!r}'
        body.append(line[1:])
    return '\n'.join(body) + '\n'


def load_module():
    module = types.ModuleType('mps_attention_under_test')
    exec(compile(patched_source(), str(PATCH), 'exec'), module.__dict__)
    return module


class ManifestTest(unittest.TestCase):
    def test_manifest_matches_patch_and_generated_file(self):
        manifest = json.loads((DEPLOY / 'sam3-manifest.json').read_text())
        self.assertEqual(manifest['patch_sha256'], hashlib.sha256(PATCH.read_bytes()).hexdigest())
        self.assertEqual(manifest['patched_files'][TARGET],
                         hashlib.sha256(patched_source().encode()).hexdigest())


@unittest.skipIf(torch is None, 'PyTorch 없는 CPU 이미지')
class ManualAttentionCpuTest(unittest.TestCase):
    def setUp(self):
        self.module = load_module()
        torch.manual_seed(0)

    @staticmethod
    def tensors(q_shape, k_shape):
        # torch 없는 환경에서도 모듈이 import되도록 기본 인자에 torch 를 쓰지 않는다.
        return torch.randn(*q_shape), torch.randn(*k_shape), torch.randn(*k_shape)

    def test_manual_attention_matches_pytorch_without_and_with_query_chunking(self):
        for q_shape, k_shape in [((2, 4, 600, 32), (2, 4, 600, 32)), ((1, 2, 1100, 64), (1, 2, 1500, 64))]:
            with self.subTest(q=q_shape, k=k_shape):
                q, k, v = self.tensors(q_shape, k_shape)
                with torch.inference_mode():
                    expected = F.scaled_dot_product_attention(q, k, v)
                    got = self.module._manual_attention(q, k, v)
                self.assertEqual(got.shape, expected.shape)
                self.assertLess((got - expected).abs().max().item(), 1e-4)

    def test_non_mps_masked_causal_dropout_and_training_keep_pytorch_path(self):
        q, k, v = self.tensors((1, 2, 1100, 32), (1, 2, 1500, 32))
        mask = torch.zeros(1100, 1500, dtype=torch.bool)
        with patch.object(self.module, '_manual_attention', side_effect=AssertionError('직접 구현이 불려서는 안 된다')):
            with torch.inference_mode():
                torch.testing.assert_close(self.module.scaled_dot_product_attention(q, k, v),
                                           F.scaled_dot_product_attention(q, k, v))
                torch.testing.assert_close(self.module.scaled_dot_product_attention(q, k, v, attn_mask=mask),
                                           F.scaled_dot_product_attention(q, k, v, attn_mask=mask))
                torch.testing.assert_close(self.module.scaled_dot_product_attention(q, k, v, is_causal=True),
                                           F.scaled_dot_product_attention(q, k, v, is_causal=True))


@unittest.skipIf(torch is None or not torch.backends.mps.is_available(), 'Apple GPU(MPS) 없음')
class ManualAttentionMpsTest(unittest.TestCase):
    """운영 조건(MPS, bf16 autocast, 추론)에서 직접 구현이 쓰이고 형 변환이 없는지, 환경변수로 되돌아가는지."""

    def setUp(self):
        self.module = load_module()
        torch.manual_seed(0)

    @staticmethod
    def run_counting(fn):
        from torch.utils._python_dispatch import TorchDispatchMode

        class Counter(TorchDispatchMode):
            def __init__(self):
                super().__init__()
                self.names = []

            def __torch_dispatch__(self, func, types_, args=(), kwargs=None):
                self.names.append(func.overloadpacket.__name__)
                return func(*args, **(kwargs or {}))

        counter = Counter()
        with torch.inference_mode(), torch.autocast('mps', dtype=torch.bfloat16), counter:
            out = fn()
        torch.mps.synchronize()
        return out, counter.names

    def test_manual_path_has_no_casts_and_matches_pytorch(self):
        shapes = [((9, 16, 576, 64), (9, 16, 576, 64)), ((1, 16, 512, 64), (1, 16, 5184, 64)), ((1, 8, 5184, 32), (1, 8, 5184, 32))]
        for q_shape, k_shape in shapes:
            with self.subTest(q=q_shape, k=k_shape):
                q = torch.randn(*q_shape, device='mps', dtype=torch.bfloat16)
                k = torch.randn(*k_shape, device='mps', dtype=torch.bfloat16)
                v = torch.randn(*k_shape, device='mps', dtype=torch.bfloat16)
                got, names = self.run_counting(lambda: self.module.scaled_dot_product_attention(q, k, v))
                expected, _ = self.run_counting(lambda: F.scaled_dot_product_attention(q, k, v))
                self.assertEqual(got.dtype, torch.bfloat16)
                self.assertEqual(names.count('_to_copy'), 0, '직접 구현 경로에 형 변환이 있다')
                self.assertEqual(names.count('scaled_dot_product_attention'), 0)
                self.assertLess((got.float() - expected.float()).abs().max().item(), 2e-2)

    def test_environment_variable_restores_pytorch_path(self):
        q = torch.randn(1, 16, 5184, 64, device='mps', dtype=torch.bfloat16)
        k = torch.randn(1, 16, 5184, 64, device='mps', dtype=torch.bfloat16)
        v = torch.randn(1, 16, 5184, 64, device='mps', dtype=torch.bfloat16)
        with patch.dict(os.environ, {'PAWBRIDGE_SAM3_MPS_ATTENTION': 'sdpa'}):
            got, names = self.run_counting(lambda: self.module.scaled_dot_product_attention(q, k, v))
        self.assertEqual(names.count('scaled_dot_product_attention'), 11, '5184 질의를 512씩 나눈 PyTorch 호출 11회')
        expected, _ = self.run_counting(lambda: F.scaled_dot_product_attention(q, k, v))
        self.assertLess((got.float() - expected.float()).abs().max().item(), 2e-2)


if __name__ == '__main__':
    unittest.main()
