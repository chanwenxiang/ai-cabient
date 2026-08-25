#!/usr/bin/env python3
"""Extract evenly spaced and scene-change frames from a video for agent Read()."""
from __future__ import annotations

import argparse
import os
import sys

import cv2


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("-i", "--input", required=True, help="Video path")
    p.add_argument("-o", "--out", required=True, help="Output directory")
    p.add_argument("--max-frames", type=int, default=12)
    p.add_argument("--scene", action="store_true", help="Also keep high scene-diff frames")
    p.add_argument("--scene-threshold", type=float, default=18.0)
    p.add_argument("--quality", type=int, default=88)
    args = p.parse_args()

    src = os.path.abspath(args.input)
    out = os.path.abspath(args.out)
    if not os.path.isfile(src):
        print(f"missing video: {src}", file=sys.stderr)
        return 1
    os.makedirs(out, exist_ok=True)

    cap = cv2.VideoCapture(src)
    if not cap.isOpened():
        print(f"cannot open: {src}", file=sys.stderr)
        return 1

    fps = float(cap.get(cv2.CAP_PROP_FPS) or 15.0)
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    dur = total / fps if fps else 0.0
    print(f"fps={fps:.2f} frames={total} duration={dur:.2f}s")

    even_idxs = set()
    n = max(1, args.max_frames)
    if total <= 0:
        print("empty video", file=sys.stderr)
        return 1
    for i in range(n):
        even_idxs.add(int(total * i / max(1, n - 1)))
    even_idxs.add(0)
    even_idxs.add(total - 1)

    scene_idxs: list[int] = []
    prev_gray = None
    idx = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if args.scene:
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            gray = cv2.resize(gray, (160, 90))
            if prev_gray is not None:
                diff = float(cv2.absdiff(gray, prev_gray).mean())
                if diff >= args.scene_threshold:
                    scene_idxs.append(idx)
            prev_gray = gray
        idx += 1
    cap.release()

    # keep strongest scene cuts, spaced a bit
    picked_scene: list[int] = []
    last = -999
    for s in scene_idxs:
        if s - last >= max(3, int(fps * 0.2)):
            picked_scene.append(s)
            last = s
    # limit scene extras
    if len(picked_scene) > args.max_frames:
        step = len(picked_scene) / args.max_frames
        picked_scene = [picked_scene[int(i * step)] for i in range(args.max_frames)]

    selected = sorted(set(even_idxs) | set(picked_scene))
    cap = cv2.VideoCapture(src)
    written = []
    for i, fi in enumerate(selected):
        cap.set(cv2.CAP_PROP_POS_FRAMES, fi)
        ok, frame = cap.read()
        if not ok:
            continue
        t = fi / fps if fps else 0
        name = f"frame_{i:02d}_t{t:05.2f}_f{fi}.jpg"
        path = os.path.join(out, name)
        cv2.imwrite(path, frame, [int(cv2.IMWRITE_JPEG_QUALITY), args.quality])
        written.append(path)
        print(path)
    cap.release()
    print(f"wrote {len(written)} frames -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
