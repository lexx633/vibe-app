#!/usr/bin/env python3
"""Генератор синтетической golden-фикстуры детектора (детерминирован, чистый stdlib).

Пишет:
  - src/test/resources/detector/weights_v1.bin  — веса в бинарном формате HMDW v1
  - src/test/resources/detector/golden.json      — векторы фич + эталонный score

Формат weights_v1.bin (little-endian, тот же читает LinearWeights.kt):
  magic "HMDW" (4б) | uint32 version | uint32 n_features | float32×n веса | float32 bias

Численно совместимо с Kotlin: веса и фичи хранятся как float32 (round-trip через struct),
скалярное произведение аккумулируется в float64, затем sigmoid. Kotlin делает так же
(FloatArray → toDouble() в аккумуляции), поэтому расхождение << 1e-4.
"""
import json
import math
import random
import struct
from pathlib import Path

SEED = 1234
N_FEATURES = 32
N_VECTORS = 20

# tools/ -> app/ ; ресурсы теста лежат в app/src/test/resources/detector
APP_DIR = Path(__file__).resolve().parent.parent
RES_DIR = APP_DIR / "src" / "test" / "resources" / "detector"

MAGIC = b"HMDW"
VERSION = 1


def f32(x: float) -> float:
    """Округление до float32 (round-trip), чтобы Python-значение совпадало с прочитанным в Kotlin."""
    return struct.unpack("<f", struct.pack("<f", x))[0]


def sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-x))


def main() -> None:
    rng = random.Random(SEED)

    weights = [f32(rng.uniform(-1.0, 1.0)) for _ in range(N_FEATURES)]
    bias = f32(rng.uniform(-0.5, 0.5))

    RES_DIR.mkdir(parents=True, exist_ok=True)

    # --- бинарник весов ---
    blob = bytearray()
    blob += MAGIC
    blob += struct.pack("<I", VERSION)
    blob += struct.pack("<I", N_FEATURES)
    for w in weights:
        blob += struct.pack("<f", w)
    blob += struct.pack("<f", bias)
    (RES_DIR / "weights_v1.bin").write_bytes(blob)

    # --- golden векторы ---
    golden = []
    for _ in range(N_VECTORS):
        feats = [f32(rng.uniform(-2.0, 2.0)) for _ in range(N_FEATURES)]
        acc = float(bias)  # аккумуляция в float64, как в Kotlin
        for w, x in zip(weights, feats):
            acc += float(w) * float(x)
        golden.append({"features": feats, "expected_score": sigmoid(acc)})

    (RES_DIR / "golden.json").write_text(
        json.dumps(golden, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print(f"weights_v1.bin: {len(blob)} байт, n_features={N_FEATURES}")
    print(f"golden.json: {N_VECTORS} векторов")
    print(f"каталог: {RES_DIR}")


if __name__ == "__main__":
    main()
