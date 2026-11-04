"""ONNX Runtime の設定ごとに、メモリ使用量（最大 RSS）と速度を測る。

  python tools/measure_ort_memory.py app/src/main/assets/bandit_plus.onnx
"""
import json
import subprocess
import sys

CHILD = r"""
import json, resource, sys, time
import numpy as np, onnxruntime as ort
path, level, arena, pattern, threads = sys.argv[1], sys.argv[2], sys.argv[3] == "1", sys.argv[4] == "1", int(sys.argv[5])
def rss(): return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
so = ort.SessionOptions()
so.graph_optimization_level = getattr(ort.GraphOptimizationLevel, level)
so.enable_cpu_mem_arena = arena
so.enable_mem_pattern = pattern
so.intra_op_num_threads = threads
t0 = time.time()
s = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
load_s, load_mb = time.time() - t0, rss()
x = (np.random.default_rng(0).standard_normal((1, 2, 264600)) * 0.1).astype(np.float32)
t0 = time.time(); s.run(None, {"mix": x}); run1 = time.time() - t0
t0 = time.time(); s.run(None, {"mix": x}); run2 = time.time() - t0
print(json.dumps(dict(load_s=round(load_s, 1), load_mb=round(load_mb), peak_mb=round(rss()), run1_s=round(run1, 2), run2_s=round(run2, 2))))
"""

CONFIGS = [
    ("ORT_ENABLE_ALL", "1", "1"),
    ("ORT_ENABLE_ALL", "0", "0"),
    ("ORT_ENABLE_EXTENDED", "0", "0"),
    ("ORT_ENABLE_BASIC", "0", "0"),
]


def main() -> None:
    path = sys.argv[1]
    threads = sys.argv[2] if len(sys.argv) > 2 else "4"
    print(f"{'level':22s} arena pattern | load(s) loadMB peakMB run1(s) run2(s)")
    for level, arena, pattern in CONFIGS:
        r = subprocess.run([sys.executable, "-c", CHILD, path, level, arena, pattern, threads],
                           capture_output=True, text=True)
        if r.returncode != 0:
            print(level, arena, pattern, "FAILED", r.stderr[-500:])
            continue
        d = json.loads(r.stdout.strip().splitlines()[-1])
        print(f"{level:22s} {arena:5s} {pattern:7s} | {d['load_s']:7} {d['load_mb']:6} {d['peak_mb']:6} {d['run1_s']:7} {d['run2_s']:7}")


if __name__ == "__main__":
    main()
