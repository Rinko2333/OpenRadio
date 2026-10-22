#!/usr/bin/env python3
"""对比 OpenRadio spike 的发送/接收 WAV，量化 Opus 对 SSTV 频段的损伤。

用法：
    python tools/analyze_spike.py                # 自动找 run/ 与 run-server/ 下最新的一对
    python tools/analyze_spike.py tx.wav rx.wav  # 手动指定

只用标准库。没有 numpy，所以频域用 Goertzel —— 我们只关心少数几个已知频点，
用不着 FFT 的全谱分辨率。
"""

import cmath
import math
import sys
import wave
from pathlib import Path

SAMPLE_RATE = 48_000
SILENCE = 0.20
TONE_SECONDS = 0.20
TONES = [1000, 1200, 1500, 1900, 2300, 2800]
SWEEP_SECONDS = 0.60
SWEEP_FROM = 1500
SWEEP_TO = 2300

ROOT = Path(__file__).resolve().parent.parent
SEARCH_DIRS = [ROOT / "run" / "spike", ROOT / "run-server" / "spike"]


def read_wav(path):
    with wave.open(str(path), "rb") as handle:
        if handle.getsampwidth() != 2:
            raise SystemExit(f"{path}: 只支持 16bit，实际 {handle.getsampwidth() * 8}bit")
        frames = handle.getnframes()
        raw = handle.readframes(frames)
    samples = [
        int.from_bytes(raw[i:i + 2], "little", signed=True)
        for i in range(0, len(raw), 2)
    ]
    return samples


def goertzel_amplitude(samples, freq, rate=SAMPLE_RATE):
    """返回该频点的幅度（已按窗口长度归一化，可直接比大小）。"""
    n = len(samples)
    if n == 0:
        return 0.0
    # 取整数个周期，减少频谱泄漏
    k = round(n * freq / rate)
    omega = 2.0 * math.pi * k / n
    coeff = 2.0 * math.cos(omega)
    s1 = s2 = 0.0
    for x in samples:
        s0 = x + coeff * s1 - s2
        s2 = s1
        s1 = s0
    power = s1 * s1 + s2 * s2 - coeff * s1 * s2
    return math.sqrt(max(power, 0.0)) * 2.0 / n


def to_db(amplitude, reference=32768.0):
    if amplitude <= 1e-9:
        return -120.0
    return 20.0 * math.log10(amplitude / reference)


def rms(samples):
    if not samples:
        return 0.0
    return math.sqrt(sum(float(x) * x for x in samples) / len(samples))


def slice_seconds(samples, start, end):
    return samples[int(start * SAMPLE_RATE):int(end * SAMPLE_RATE)]


def tone_window(index):
    """第 index 个单音的时间窗，只取中间 60% —— 避开编码器的起止瞬态。"""
    center = SILENCE + index * TONE_SECONDS + TONE_SECONDS / 2.0
    half = TONE_SECONDS * 0.3
    return center - half, center + half


def sweep_window():
    start = SILENCE + len(TONES) * TONE_SECONDS
    return start, start + SWEEP_SECONDS


def dominant_frequency(samples, low=1200, high=2700, step=20):
    best_hz, best_amp = 0, -1.0
    hz = low
    while hz <= high:
        amp = goertzel_amplitude(samples, hz)
        if amp > best_amp:
            best_amp, best_hz = amp, hz
        hz += step
    return best_hz, best_amp


def analyze(label, tx, rx):
    print(f"\n=== {label} ===")
    print(f"TX  {len(tx) / SAMPLE_RATE:5.2f}s  {len(tx):>6} 样本  峰值 {max(abs(x) for x in tx):>5}")
    print(f"RX  {len(rx) / SAMPLE_RATE:5.2f}s  {len(rx):>6} 样本  峰值 {max(abs(x) for x in rx):>5}")

    expected = SILENCE * 2 + len(TONES) * TONE_SECONDS + SWEEP_SECONDS
    print(f"预期时长 {expected:.2f}s，实际丢帧约 "
          f"{(len(tx) - len(rx)) / SAMPLE_RATE * 1000:.0f} ms")

    print("\n频点响应（dBFS，Δ 为 RX 相对 TX）：")
    print(f"  {'Hz':>6}  {'TX':>8}  {'RX':>8}  {'Δ dB':>7}")
    worst = 0.0
    for index, hz in enumerate(TONES):
        start, end = tone_window(index)
        tx_amp = goertzel_amplitude(slice_seconds(tx, start, end), hz)
        rx_amp = goertzel_amplitude(slice_seconds(rx, start, end), hz)
        delta = to_db(rx_amp) - to_db(tx_amp)
        worst = min(worst, delta)
        flag = ""
        if hz >= 1500 and delta < -6.0:
            flag = "  <-- SSTV 频段，衰减明显"
        print(f"  {hz:>6}  {to_db(tx_amp):>8.1f}  {to_db(rx_amp):>8.1f}  {delta:>+7.1f}{flag}")

    lead = slice_seconds(rx, 0.02, SILENCE * 0.9)
    if lead:
        noise_db = 20.0 * math.log10(max(rms(lead), 1e-9) / 32768.0)
        print(f"\n首段静音的噪声底：{noise_db:.1f} dBFS")

    print("\n扫频主频跟踪（每 50ms 一段，期望从 1500 线性升到 2300）：")
    sweep_start, sweep_end = sweep_window()
    segment = 0.05
    count = int(SWEEP_SECONDS / segment)
    print(f"  {'段':>3}  {'期望':>7}  {'TX':>7}  {'RX':>7}  {'偏差':>6}")
    errors = []
    for i in range(count):
        from_s = sweep_start + i * segment
        to_s = from_s + segment
        point = (i + 0.5) / count
        expect = SWEEP_FROM + (SWEEP_TO - SWEEP_FROM) * point
        tx_hz, _ = dominant_frequency(slice_seconds(tx, from_s, to_s))
        rx_hz, _ = dominant_frequency(slice_seconds(rx, from_s, to_s))
        errors.append(abs(rx_hz - tx_hz))
        print(f"  {i:>3}  {expect:>7.0f}  {tx_hz:>7}  {rx_hz:>7}  {rx_hz - tx_hz:>+6}")

    if errors:
        print(f"\n扫频跟踪平均偏差 {sum(errors) / len(errors):.0f} Hz，最大 {max(errors):.0f} Hz")
    print(f"\n最差频点衰减 {worst:+.1f} dB")
    return worst


def discover():
    """按模式分组，各取最新的一对。"""
    groups = {}
    for directory in SEARCH_DIRS:
        if not directory.is_dir():
            continue
        for path in directory.glob("*.wav"):
            name = path.name
            if not (name.startswith("tx-") or name.startswith("rx-")):
                continue
            parts = name.split("-")
            if len(parts) < 3:
                continue
            kind, mode = parts[0], parts[1]
            key = mode
            slot = groups.setdefault(key, {})
            current = slot.get(kind)
            if current is None or path.stat().st_mtime > current.stat().st_mtime:
                slot[kind] = path
    pairs = [(mode, slot["tx"], slot["rx"])
             for mode, slot in groups.items() if "tx" in slot and "rx" in slot]
    return sorted(pairs)


def main():
    if len(sys.argv) == 3:
        pairs = [("手动", Path(sys.argv[1]), Path(sys.argv[2]))]
    else:
        pairs = discover()

    if not pairs:
        print("没有找到成对的 wav。先在设计里跑：")
        print("  /openradio spike audio")
        print("  /openradio spike voip")
        print(f"查找目录：{', '.join(str(d) for d in SEARCH_DIRS)}")
        return 1

    for label, tx_path, rx_path in pairs:
        print(f"\nTX 文件：{tx_path}")
        print(f"RX 文件：{rx_path}")
        analyze(label, read_wav(tx_path), read_wav(rx_path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
