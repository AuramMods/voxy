#!/usr/bin/env python3
"""Compare two same-camera screenshots. Requires Pillow and NumPy."""
import argparse
from pathlib import Path

import numpy as np
from PIL import Image


def frame_rgb(path):
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"), dtype=np.float32)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("output", type=Path, help="Orange brightens, blue darkens")
    parser.add_argument("--crop", nargs=4, type=int, metavar=("LEFT", "TOP", "RIGHT", "BOTTOM"))
    parser.add_argument("--threshold", type=float, default=3.0, help="Hide changes below this many luma levels")
    parser.add_argument("--full-color", type=float, default=20.0, help="Luma change shown at full intensity")
    args = parser.parse_args()
    if not 0 <= args.threshold < args.full_color <= 255:
        parser.error("require 0 <= threshold < full-color <= 255")

    before, after = frame_rgb(args.before), frame_rgb(args.after)
    if before.shape != after.shape:
        parser.error("the input frames must have the same dimensions")
    if args.crop:
        left, top, right, bottom = args.crop
        height, width = before.shape[:2]
        if not (0 <= left < right <= width and 0 <= top < bottom <= height):
            parser.error("crop must be inside both frames")
        before = before[top:bottom, left:right]
        after = after[top:bottom, left:right]

    weights = np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)
    change = (after - before) @ weights
    difference = np.abs(change)
    strength = np.clip((difference - args.threshold) / (args.full_color - args.threshold), 0, 1)
    backdrop = 0.35 * (before @ weights)
    tint = np.where(change[..., None] > 0, [255, 133, 35], [38, 167, 255])
    overlay = backdrop[..., None] * (1 - strength[..., None]) + tint * strength[..., None]
    Image.fromarray(np.rint(overlay).astype(np.uint8)).save(args.output)

    p95, p99 = np.percentile(difference, (95, 99))
    print(f"Mean absolute change {difference.mean():.2f} / 255")
    print(f"95th / 99th percentile {p95:.2f} / {p99:.2f} luma levels")
    print(f"Pixels above {args.threshold:g} levels {(difference > args.threshold).mean():.2%}")


if __name__ == "__main__":
    main()
