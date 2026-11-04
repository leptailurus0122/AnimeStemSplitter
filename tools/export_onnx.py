"""BandIt Plus（セリフ/BGM/効果音 分離モデル）を Android 用の ONNX に変換する。

書き出すモデルは「6秒の波形 → 3トラックの波形」をまとめて行う形にしてある。
STFT / 逆STFT もモデル内（畳み込み）で行うので、アプリ側は音声を切って渡すだけでよい。

  入力  mix    : float32 [1, 2, 264600]      (44.1kHz ステレオ 6秒)
  出力  stems  : float32 [1, 3, 2, 264600]   (0=セリフ, 1=BGM, 2=効果音)

使い方:
  pip install torch torchaudio onnx onnxruntime numpy pyyaml librosa
  python tools/export_onnx.py --out app/src/main/assets/bandit_plus.onnx

元の PyTorch モデルとの誤差と、ONNX Runtime での処理時間を表示して検証する。
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
import urllib.request
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
import yaml
from torch import nn

# モデル内部の torch.jit.script を無効化（ONNX 変換しやすくするため）
torch.jit.script = lambda m, *a, **k: m  # type: ignore[assignment]

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from bandit import MultiMaskMultiSourceBandSplitRNNSimple  # noqa: E402

BASE = "https://github.com/ZFTurbo/Music-Source-Separation-Training/releases/download/v.1.0.3/"
CONFIG = "config_dnr_bandit_bsrnn_multi_mus64.yaml"
WEIGHTS = "model_bandit_plus_dnr_sdr_11.47.chpt"


def fetch(name: str, cache: Path) -> Path:
    cache.mkdir(parents=True, exist_ok=True)
    p = cache / name
    if not p.exists():
        print(f"downloading {name} ...", flush=True)
        urllib.request.urlretrieve(BASE + name, str(p) + ".part")
        os.replace(str(p) + ".part", p)
    return p


class WaveToWave(nn.Module):
    """元モデルと同じ計算を、複素数を使わず実数だけで書き直したもの。"""

    def __init__(self, model: MultiMaskMultiSourceBandSplitRNNSimple, length: int, cfg: dict):
        super().__init__()
        mc = cfg["model"]
        assert mc["center"] and mc["normalized"] and mc["onesided"] and mc["pad_mode"] == "constant"
        assert mc["window_fn"] == "hann_window" and mc["win_length"] == mc["n_fft"]
        assert mc["in_channel"] == 1 and mc["treat_channel_as_feature"]

        self.n_fft = N = mc["n_fft"]
        self.hop = H = mc["hop_length"]
        self.n_freq = Fq = N // 2 + 1
        self.length = length
        self.n_time = 1 + length // H
        core = model.bsrnn
        self.band_split = core.band_split
        self.tf_model = core.tf_model
        self.band_specs = [tuple(int(v) for v in b) for b in core.band_split.band_specs]
        self.stems = list(core.mask_estim.keys())
        self.mask_estim = core.mask_estim

        # --- STFT / iSTFT を畳み込みで表す（torchaudio の normalized=True と同じスケール） ---
        w = torch.hann_window(N, dtype=torch.float64)  # periodic
        wnorm = w.pow(2).sum().sqrt()
        n = torch.arange(N, dtype=torch.float64)
        k = torch.arange(Fq, dtype=torch.float64)
        ang = 2 * math.pi * k[:, None] * n[None, :] / N
        fwd = torch.cat([w * torch.cos(ang), -w * torch.sin(ang)], 0) / wnorm      # (2F, N)
        c = torch.full((Fq,), 2.0, dtype=torch.float64)
        c[0] = 1.0
        c[-1] = 1.0
        inv = torch.cat([c[:, None] * torch.cos(ang), -c[:, None] * torch.sin(ang)], 0) * w / N * wnorm
        self.register_buffer("fwd_basis", fwd[:, None, :].float())                  # (2F, 1, N)
        self.register_buffer("inv_basis", inv[:, None, :].float())                  # (2F, 1, N)
        env = F.conv_transpose1d(torch.ones(1, 1, self.n_time, dtype=torch.float64),
                                 w.pow(2)[None, None], stride=H)[0, 0]
        env = env[N // 2: N // 2 + length]
        assert float(env.min()) > 1e-6
        self.register_buffer("inv_env", (1.0 / env).float())                        # (L,)

    # x: (B, L) → re, im: (B, F, T)
    def stft(self, x):
        x = F.pad(x[:, None, :], (self.n_fft // 2, self.n_fft // 2))
        s = F.conv1d(x, self.fwd_basis, stride=self.hop)
        return s[:, : self.n_freq], s[:, self.n_freq:]

    # re, im: (B, F, T) → (B, L)
    def istft(self, re, im):
        y = F.conv_transpose1d(torch.cat([re, im], 1), self.inv_basis, stride=self.hop)[:, 0]
        y = y[:, self.n_fft // 2: self.n_fft // 2 + self.length]
        return y * self.inv_env

    def forward(self, mix):  # (1, 2, L)
        b = mix.shape[0] * mix.shape[1]
        re, im = self.stft(mix.reshape(b, self.length))                 # (b, F, T)
        T = re.shape[-1]
        xr = torch.stack([re, im], -1)                                   # (b, F, T, 2)
        xr = xr.permute(0, 2, 3, 1)[:, :, None]                          # (b, T, 1, 2, F)

        z = []
        for (fs, fe), nfm in zip(self.band_specs, self.band_split.norm_fc_modules):
            xb = xr[..., fs:fe].reshape(b, T, 1, -1)
            z.append(nfm(xb))
        z = torch.stack(z, 1)                                            # (b, nb, T, emb)
        q = self.tf_model(z)

        outs = []
        for stem in self.stems:
            mem = self.mask_estim[stem]
            use_fw = getattr(mem, "use_freq_weights", False)
            mask = None
            for i, ((fs, fe), nmlp) in enumerate(zip(self.band_specs, mem.norm_mlp)):
                h = nmlp.output(nmlp.hidden(nmlp.norm(q[:, i])))        # (b, T, bw*2)
                m = h.reshape(b, T, fe - fs, 2).permute(0, 2, 1, 3)      # (b, bw, T, 2)
                if use_fw:
                    m = m * mem.get_buffer(f"freq_weights/{i}").to(m.dtype)[:, None, None]
                m = F.pad(m, (0, 0, 0, 0, fs, self.n_freq - fe))         # (b, F, T, 2)
                mask = m if mask is None else mask + m
            mr, mi = mask[..., 0], mask[..., 1]
            y = self.istft(re * mr - im * mi, re * mi + im * mr)          # (b, L)
            outs.append(y.reshape(mix.shape[0], mix.shape[1], self.length))
        return torch.stack(outs, 1)                                       # (1, 3, 2, L)


def test_signal(length: int, sr: int) -> torch.Tensor:
    g = torch.Generator().manual_seed(0)
    t = torch.arange(length) / sr
    tone = 0.2 * torch.sin(2 * math.pi * 220 * t) * (1 + torch.sin(2 * math.pi * 0.7 * t))
    voice = 0.15 * torch.sin(2 * math.pi * (180 + 40 * torch.sin(2 * math.pi * 3 * t)) * t)
    noise = 0.05 * torch.randn(2, length, generator=g)
    x = torch.stack([tone + voice, tone - 0.5 * voice]) + noise
    return x[None].float()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(HERE.parent / "app/src/main/assets/bandit_plus.onnx"))
    ap.add_argument("--cache", default=str(HERE / ".cache"))
    ap.add_argument("--opset", type=int, default=17)
    args = ap.parse_args()

    cache = Path(args.cache)
    cfg = yaml.load(open(fetch(CONFIG, cache), encoding="utf-8"), Loader=yaml.FullLoader)
    model = MultiMaskMultiSourceBandSplitRNNSimple(**cfg["model"])
    state = torch.load(fetch(WEIGHTS, cache), map_location="cpu", weights_only=False)
    model.load_state_dict(state.get("state_dict", state))
    model.eval()

    L = int(cfg["audio"]["chunk_size"])
    sr = int(cfg["audio"]["sample_rate"])
    em = WaveToWave(model, L, cfg).eval()
    x = test_signal(L, sr)

    with torch.no_grad():
        ref = model(x)
        got = em(x)
    err = (ref - got).abs().max().item()
    scale = ref.abs().max().item()
    print(f"[check] PyTorch original vs rewritten: max|diff|={err:.2e} (signal peak {scale:.3f})")
    assert err < 1e-3 * max(scale, 1e-3), "rewritten model does not match the original"

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    kw = dict(input_names=["mix"], output_names=["stems"], opset_version=args.opset, do_constant_folding=True)
    ref_np = ref.numpy().copy()
    del ref, got, model
    import gc
    gc.collect()
    t0 = time.time()
    with torch.no_grad():
        try:
            torch.onnx.export(em, (x,), str(out), dynamo=False, **kw)
        except TypeError:
            torch.onnx.export(em, (x,), str(out), **kw)
    print(f"[export] {out} ({out.stat().st_size / 1e6:.1f} MB, {time.time() - t0:.0f}s)")

    import onnx
    import onnxruntime as ort

    onnx.checker.check_model(str(out))
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    sess = ort.InferenceSession(str(out), so, providers=["CPUExecutionProvider"])
    xs = x.numpy()
    t0 = time.time()
    y = sess.run(None, {"mix": xs})[0]
    dt = time.time() - t0
    err2 = float(np.abs(y - ref_np).max())
    print(f"[check] ONNX Runtime vs original: max|diff|={err2:.2e}")
    print(f"[speed] {dt:.2f}s per 6s chunk on this CPU ({os.cpu_count()} cores)")
    assert err2 < 1e-3 * max(scale, 1e-3), "ONNX output does not match"

    meta = {
        "sample_rate": sr, "chunk": L, "num_overlap": int(cfg["inference"].get("num_overlap", 4)),
        "stems": ["dialogue" if s == "speech" else s for s in em.stems],
    }
    (out.parent / "model_meta.json").write_text(json.dumps(meta, indent=2))
    print("[meta]", meta)
    print("OK")


if __name__ == "__main__":
    main()
