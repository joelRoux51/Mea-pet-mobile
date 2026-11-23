#!/usr/bin/env python3
"""
Construit le 3e modèle Live2D intégré (« Alita — Visage Live2D ») dans
`app/src/main/assets/live2d/alita_face_live2d/` :
1. Recadre le buste / visage d'Alita en 520x520 RGBA avec épaules complètes et fondu doux sous l'emblème.
2. Génère des calques faciaux propres sans artefacts de mèches sur les sourcils/paupières/bouche :
   - Sourcils nets animables (rotation + élévation par expression)
   - Paupières fermées (`calme`/`clin_doeil`) et mi-closes (`fatigue`)
   - Joues rougissantes douces (`colere`/`joie`/`malice`)
   - 8 bouches liées aux expressions (`normal`, `joie`, `colere`, `malice`, `surprise`, `serieux`, `calme`, `fatigue`)
3. Génère `alita_face.model3.json` et les 11 fichiers `.exp3.json` liés.
"""

import json
import math
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPRITES_ALITA = ROOT / "app/src/main/assets/sprites/alita"
SRC_LAYERS = SPRITES_ALITA / "layers"
OUT_DIR = ROOT / "app/src/main/assets/live2d/alita_face_live2d"
OUT_LAYERS = OUT_DIR / "layers"
OUT_EXPR = OUT_DIR / "expressions"

SRC_W, SRC_H = 760, 1200
CROP_X0 = 120
CROP_Y0 = 28
BUST_SIZE = 520  # 520x520 centered at x=380 (120..640), y=28..548

# Bounding boxes on 760x1200 canvas (aligned with tools/export_alita_psd.py)
L_EYE = (312, 208, 366, 242)
R_EYE = (394, 208, 448, 242)
MOUTH = (356, 273, 404, 300)


def read_rgba(png_path: Path, width: int = SRC_W, height: int = SRC_H) -> bytearray:
    pam = subprocess.check_output(
        ["convert", str(png_path), "-colorspace", "sRGB", "-type", "TrueColorAlpha", "-depth", "8", "pam:-"]
    )
    end_hdr = pam.find(b"ENDHDR\n") + len(b"ENDHDR\n")
    buf = bytearray(pam[end_hdr:])
    expected = width * height * 4
    if len(buf) != expected:
        raise RuntimeError(f"Unexpected PAM size for {png_path}: {len(buf)} != {expected}")
    return buf


def write_rgba(buf: bytearray, out_path: Path, width: int = BUST_SIZE, height: int = BUST_SIZE) -> None:
    hdr = f"P7\nWIDTH {width}\nHEIGHT {height}\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n".encode()
    subprocess.run(
        ["convert", "pam:-", "-type", "TrueColorAlpha", str(out_path)],
        input=hdr + bytes(buf),
        check=True,
    )


def crop_bust(src: bytearray, fade_bottom: bool = False) -> bytearray:
    out = bytearray(BUST_SIZE * BUST_SIZE * 4)
    for dy in range(BUST_SIZE):
        sy = CROP_Y0 + dy
        fade = 1.0
        if fade_bottom and dy >= 458:
            t = max(0.0, min(1.0, (dy - 458) / 58.0))
            fade = 0.5 * (1.0 + math.cos(math.pi * t))
        for dx in range(BUST_SIZE):
            sx = CROP_X0 + dx
            so = (sy * SRC_W + sx) * 4
            do = (dy * BUST_SIZE + dx) * 4
            out[do] = src[so]
            out[do + 1] = src[so + 1]
            out[do + 2] = src[so + 2]
            out[do + 3] = int(src[so + 3] * fade)
    return out


def build_seamless_bust_torso(normal_rgba: bytearray, torse_rgba: bytearray) -> bytearray:
    """Uses normal.png shoulders/arms below the neck (y>=355) with torse collar so sleeves never clip."""
    combined = bytearray(SRC_W * SRC_H * 4)
    for y in range(320, CROP_Y0 + BUST_SIZE):
        for x in range(CROP_X0, CROP_X0 + BUST_SIZE):
            o = (y * SRC_W + x) * 4
            if y >= 355 and normal_rgba[o + 3] > 0:
                combined[o:o + 4] = normal_rgba[o:o + 4]
            else:
                combined[o:o + 4] = torse_rgba[o:o + 4]
    return crop_bust(combined, fade_bottom=True)


def is_skin(r: int, g: int, b: int, a: int) -> bool:
    if a < 64:
        return False
    return r >= 200 and g >= 165 and b >= 145 and (r - b) <= 68 and (r + g + b) / 3.0 >= 175


def extract_box_non_skin(src: bytearray, box: tuple[int, int, int, int]) -> bytearray:
    out = bytearray(SRC_W * SRC_H * 4)
    x0, y0, x1, y1 = box
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            o = (y * SRC_W + x) * 4
            r, g, b, a = src[o], src[o + 1], src[o + 2], src[o + 3]
            if a > 0 and not is_skin(r, g, b, a):
                out[o:o + 4] = src[o:o + 4]
    return out


def extract_sleepy_eyelid(fatigue_rgba: bytearray, tete_rgba: bytearray, box: tuple[int, int, int, int], is_left: bool) -> bytearray:
    """
    Extracts Alita's heavy-lidded eye from fatigue.png while replacing the small dark hair/cyan overlap
    at the top corners with clean porcelain skin tone so it stays crisp when tilting/tilted.
    """
    out = bytearray(SRC_W * SRC_H * 4)
    x0, y0, x1, y1 = box
    cx = (x0 + x1) * 0.5
    cy = (y0 + y1) * 0.5
    rx = max(1.0, (x1 - x0) * 0.5)
    ry = max(1.0, (y1 - y0) * 0.5)
    skin = (247, 232, 224)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            o = (y * SRC_W + x) * 4
            if fatigue_rgba[o + 3] == 0:
                continue
            r, g, b = fatigue_rgba[o], fatigue_rgba[o + 1], fatigue_rgba[o + 2]
            dx = abs(x - cx) / rx
            dy = abs(y - cy) / ry
            edge = max(dx, dy)
            feather = 1.0 if edge <= 0.78 else max(0.0, (1.0 - edge) / 0.22)

            # Avoid hair strand / cyan cheek line bleed at top of box (y <= 216)
            is_cyan = (b > r + 15 and g > r + 12)
            is_hair_bleed = y <= 215 and (r + g + b) / 3.0 < 95 and ((is_left and x < 332) or (not is_left and x > 428))
            if is_cyan or is_hair_bleed:
                out[o], out[o + 1], out[o + 2] = skin
            else:
                out[o], out[o + 1], out[o + 2] = r, g, b
            out[o + 3] = int(fatigue_rgba[o + 3] * feather)
    return out


def synthesize_clean_eyebrow(is_left: bool) -> bytearray:
    """Draws an anti-aliased anime eyebrow cleanly detached from bangs/cyber lines."""
    out = bytearray(SRC_W * SRC_H * 4)
    # Left brow spans x=316..361, right brow mirrors x=399..444 around center 380
    x_start, x_end = (316, 361) if is_left else (399, 444)
    for y in range(190, 210):
        for x in range(x_start, x_end + 1):
            t = (x - x_start) / float(x_end - x_start)
            if not is_left:
                t = 1.0 - t
            # Slight graceful arch peaking near inner-mid brow (t=0.62)
            center_y = 201.5 - 2.6 * math.sin(math.pi * (t ** 0.85))
            # Thicker near inner edge, tapering delicately toward outer temple
            half_thick = 1.15 + 1.05 * math.sin(math.pi * (t ** 0.7))
            dist = abs(y - center_y)
            if dist <= half_thick + 0.9:
                cov = max(0.0, min(1.0, (half_thick + 0.9 - dist) / 1.1))
                # Soften tips
                tip = min(1.0, t / 0.12, (1.0 - t) / 0.10)
                alpha = int(245 * cov * tip)
                if alpha > 2:
                    o = (y * SRC_W + x) * 4
                    out[o], out[o + 1], out[o + 2], out[o + 3] = 32, 36, 44, alpha
    return out


def synthesize_yawn_mouth() -> bytearray:
    """Draws a clean soft oval yawn mouth (fatigue) without hand occlusion."""
    out = bytearray(SRC_W * SRC_H * 4)
    cx, cy = 380.0, 288.0
    rx, ry = 9.5, 8.2
    for y in range(276, 301):
        for x in range(366, 395):
            dx = (x - cx) / rx
            dy = (y - cy) / ry
            d = math.hypot(dx, dy)
            if d <= 1.18:
                o = (y * SRC_W + x) * 4
                if d >= 0.85:
                    a = int(240 * max(0.0, min(1.0, (1.18 - d) / 0.33)))
                    out[o], out[o + 1], out[o + 2], out[o + 3] = 56, 34, 40, a
                else:
                    ty = max(0.0, min(1.0, (y - (cy - ry)) / (2.0 * ry)))
                    if ty < 0.50:
                        r, g, b = 88, 42, 52
                    else:
                        s = (ty - 0.50) / 0.50
                        r = int(172 + 44 * s)
                        g = int(86 + 38 * s)
                        b = int(98 + 34 * s)
                    out[o], out[o + 1], out[o + 2], out[o + 3] = r, g, b, 255
    return out


def synthesize_soft_cheek_blush() -> bytearray:
    """Draws smooth anime cheek blush ovals aligned under Alita's eyes."""
    out = bytearray(SRC_W * SRC_H * 4)
    for y in range(238, 274):
        for x in range(310, 451):
            dl = math.hypot((x - 340.0) / 22.0, (y - 255.0) / 10.5)
            dr = math.hypot((x - 420.0) / 22.0, (y - 255.0) / 10.5)
            d = min(dl, dr)
            if d <= 1.0:
                falloff = (0.5 * (1.0 + math.cos(math.pi * d))) ** 1.3
                alpha = int(165 * falloff)
                if alpha > 2:
                    o = (y * SRC_W + x) * 4
                    out[o], out[o + 1], out[o + 2], out[o + 3] = 244, 114, 122, alpha
    return out


def write_expressions() -> None:
    OUT_EXPR.mkdir(parents=True, exist_ok=True)
    expressions = {
        "normal": {
            "FadeInTime": 0.25,
            "FadeOutTime": 0.25,
            "Parameters": [
                {"Id": "ParamAngleX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamAngleY", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamAngleZ", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLOpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeROpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallY", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamPupilScale", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamBrowLY", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamBrowRY", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamBrowLAngle", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamBrowRAngle", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamCheek", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamMouthVariant", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLidVariant", "Value": 0.0, "Blend": "Overwrite"},
            ],
        },
        "joie": {
            "FadeInTime": 0.28,
            "FadeOutTime": 0.28,
            "Parameters": [
                {"Id": "ParamAngleX", "Value": 2.5, "Blend": "Overwrite"},
                {"Id": "ParamAngleY", "Value": 2.8, "Blend": "Overwrite"},
                {"Id": "ParamAngleZ", "Value": 4.5, "Blend": "Overwrite"},
                {"Id": "ParamEyeLOpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeROpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallY", "Value": 0.08, "Blend": "Overwrite"},
                {"Id": "ParamPupilScale", "Value": 1.05, "Blend": "Overwrite"},
                {"Id": "ParamBrowLY", "Value": 0.55, "Blend": "Overwrite"},
                {"Id": "ParamBrowRY", "Value": 0.55, "Blend": "Overwrite"},
                {"Id": "ParamBrowLAngle", "Value": 0.22, "Blend": "Overwrite"},
                {"Id": "ParamBrowRAngle", "Value": 0.22, "Blend": "Overwrite"},
                {"Id": "ParamCheek", "Value": 0.30, "Blend": "Overwrite"},
                {"Id": "ParamMouthVariant", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLidVariant", "Value": 0.0, "Blend": "Overwrite"},
            ],
        },
        "serieux": {
            "FadeInTime": 0.26,
            "FadeOutTime": 0.26,
            "Parameters": [
                {"Id": "ParamAngleX", "Value": -2.0, "Blend": "Overwrite"},
                {"Id": "ParamAngleY", "Value": -2.4, "Blend": "Overwrite"},
                {"Id": "ParamAngleZ", "Value": -1.5, "Blend": "Overwrite"},
                {"Id": "ParamEyeLOpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeROpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallY", "Value": -0.05, "Blend": "Overwrite"},
                {"Id": "ParamPupilScale", "Value": 0.94, "Blend": "Overwrite"},
                {"Id": "ParamBrowLY", "Value": -0.35, "Blend": "Overwrite"},
                {"Id": "ParamBrowRY", "Value": -0.35, "Blend": "Overwrite"},
                {"Id": "ParamBrowLAngle", "Value": -0.52, "Blend": "Overwrite"},
                {"Id": "ParamBrowRAngle", "Value": -0.52, "Blend": "Overwrite"},
                {"Id": "ParamCheek", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamMouthVariant", "Value": 5.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLidVariant", "Value": 0.0, "Blend": "Overwrite"},
            ],
        },
        "colere": {
            "FadeInTime": 0.20,
            "FadeOutTime": 0.25,
            "Parameters": [
                {"Id": "ParamAngleX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamAngleY", "Value": -3.5, "Blend": "Overwrite"},
                {"Id": "ParamAngleZ", "Value": -2.5, "Blend": "Overwrite"},
                {"Id": "ParamEyeLOpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeROpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallY", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamPupilScale", "Value": 0.88, "Blend": "Overwrite"},
                {"Id": "ParamBrowLY", "Value": -0.55, "Blend": "Overwrite"},
                {"Id": "ParamBrowRY", "Value": -0.55, "Blend": "Overwrite"},
                {"Id": "ParamBrowLAngle", "Value": -0.92, "Blend": "Overwrite"},
                {"Id": "ParamBrowRAngle", "Value": -0.92, "Blend": "Overwrite"},
                {"Id": "ParamCheek", "Value": 0.92, "Blend": "Overwrite"},
                {"Id": "ParamMouthVariant", "Value": 2.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLidVariant", "Value": 0.0, "Blend": "Overwrite"},
            ],
        },
        "calme": {
            "FadeInTime": 0.35,
            "FadeOutTime": 0.35,
            "Parameters": [
                {"Id": "ParamAngleX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamAngleY", "Value": -3.8, "Blend": "Overwrite"},
                {"Id": "ParamAngleZ", "Value": 3.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLOpen", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeROpen", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallY", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamPupilScale", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamBrowLY", "Value": -0.25, "Blend": "Overwrite"},
                {"Id": "ParamBrowRY", "Value": -0.25, "Blend": "Overwrite"},
                {"Id": "ParamBrowLAngle", "Value": 0.14, "Blend": "Overwrite"},
                {"Id": "ParamBrowRAngle", "Value": 0.14, "Blend": "Overwrite"},
                {"Id": "ParamCheek", "Value": 0.14, "Blend": "Overwrite"},
                {"Id": "ParamMouthVariant", "Value": 6.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLidVariant", "Value": 0.0, "Blend": "Overwrite"},
            ],
        },
        "fatigue": {
            "FadeInTime": 0.35,
            "FadeOutTime": 0.30,
            "Parameters": [
                {"Id": "ParamAngleX", "Value": -3.0, "Blend": "Overwrite"},
                {"Id": "ParamAngleY", "Value": -5.2, "Blend": "Overwrite"},
                {"Id": "ParamAngleZ", "Value": -5.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLOpen", "Value": 0.42, "Blend": "Overwrite"},
                {"Id": "ParamEyeROpen", "Value": 0.42, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallY", "Value": -0.25, "Blend": "Overwrite"},
                {"Id": "ParamPupilScale", "Value": 0.96, "Blend": "Overwrite"},
                {"Id": "ParamBrowLY", "Value": -0.35, "Blend": "Overwrite"},
                {"Id": "ParamBrowRY", "Value": -0.35, "Blend": "Overwrite"},
                {"Id": "ParamBrowLAngle", "Value": 0.42, "Blend": "Overwrite"},
                {"Id": "ParamBrowRAngle", "Value": 0.42, "Blend": "Overwrite"},
                {"Id": "ParamCheek", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamMouthVariant", "Value": 7.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLidVariant", "Value": 1.0, "Blend": "Overwrite"},
            ],
        },
        "malice": {
            "FadeInTime": 0.22,
            "FadeOutTime": 0.24,
            "Parameters": [
                {"Id": "ParamAngleX", "Value": -5.5, "Blend": "Overwrite"},
                {"Id": "ParamAngleY", "Value": 1.5, "Blend": "Overwrite"},
                {"Id": "ParamAngleZ", "Value": 8.5, "Blend": "Overwrite"},
                {"Id": "ParamEyeLOpen", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeROpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallX", "Value": -0.45, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallY", "Value": -0.15, "Blend": "Overwrite"},
                {"Id": "ParamPupilScale", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamBrowLY", "Value": -0.22, "Blend": "Overwrite"},
                {"Id": "ParamBrowRY", "Value": 0.48, "Blend": "Overwrite"},
                {"Id": "ParamBrowLAngle", "Value": -0.20, "Blend": "Overwrite"},
                {"Id": "ParamBrowRAngle", "Value": 0.25, "Blend": "Overwrite"},
                {"Id": "ParamCheek", "Value": 0.35, "Blend": "Overwrite"},
                {"Id": "ParamMouthVariant", "Value": 3.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLidVariant", "Value": 0.0, "Blend": "Overwrite"},
            ],
        },
        "etirement": {
            "FadeInTime": 0.30,
            "FadeOutTime": 0.30,
            "Parameters": [
                {"Id": "ParamAngleX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamAngleY", "Value": 6.5, "Blend": "Overwrite"},
                {"Id": "ParamAngleZ", "Value": 2.2, "Blend": "Overwrite"},
                {"Id": "ParamEyeLOpen", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeROpen", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallY", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamPupilScale", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamBrowLY", "Value": 0.35, "Blend": "Overwrite"},
                {"Id": "ParamBrowRY", "Value": 0.35, "Blend": "Overwrite"},
                {"Id": "ParamBrowLAngle", "Value": 0.22, "Blend": "Overwrite"},
                {"Id": "ParamBrowRAngle", "Value": 0.22, "Blend": "Overwrite"},
                {"Id": "ParamCheek", "Value": 0.16, "Blend": "Overwrite"},
                {"Id": "ParamMouthVariant", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLidVariant", "Value": 0.0, "Blend": "Overwrite"},
            ],
        },
        "surprise": {
            "FadeInTime": 0.16,
            "FadeOutTime": 0.25,
            "Parameters": [
                {"Id": "ParamAngleX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamAngleY", "Value": 3.8, "Blend": "Overwrite"},
                {"Id": "ParamAngleZ", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLOpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeROpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallY", "Value": 0.08, "Blend": "Overwrite"},
                {"Id": "ParamPupilScale", "Value": 0.80, "Blend": "Overwrite"},
                {"Id": "ParamBrowLY", "Value": 0.95, "Blend": "Overwrite"},
                {"Id": "ParamBrowRY", "Value": 0.95, "Blend": "Overwrite"},
                {"Id": "ParamBrowLAngle", "Value": 0.30, "Blend": "Overwrite"},
                {"Id": "ParamBrowRAngle", "Value": 0.30, "Blend": "Overwrite"},
                {"Id": "ParamCheek", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamMouthVariant", "Value": 4.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLidVariant", "Value": 0.0, "Blend": "Overwrite"},
            ],
        },
        "curiosite": {
            "FadeInTime": 0.24,
            "FadeOutTime": 0.26,
            "Parameters": [
                {"Id": "ParamAngleX", "Value": 7.5, "Blend": "Overwrite"},
                {"Id": "ParamAngleY", "Value": 2.0, "Blend": "Overwrite"},
                {"Id": "ParamAngleZ", "Value": -10.5, "Blend": "Overwrite"},
                {"Id": "ParamEyeLOpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeROpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallX", "Value": 0.32, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallY", "Value": 0.25, "Blend": "Overwrite"},
                {"Id": "ParamPupilScale", "Value": 1.04, "Blend": "Overwrite"},
                {"Id": "ParamBrowLY", "Value": -0.12, "Blend": "Overwrite"},
                {"Id": "ParamBrowRY", "Value": 0.68, "Blend": "Overwrite"},
                {"Id": "ParamBrowLAngle", "Value": -0.10, "Blend": "Overwrite"},
                {"Id": "ParamBrowRAngle", "Value": 0.28, "Blend": "Overwrite"},
                {"Id": "ParamCheek", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamMouthVariant", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLidVariant", "Value": 0.0, "Blend": "Overwrite"},
            ],
        },
        "clin_doeil": {
            "FadeInTime": 0.20,
            "FadeOutTime": 0.24,
            "Parameters": [
                {"Id": "ParamAngleX", "Value": -3.5, "Blend": "Overwrite"},
                {"Id": "ParamAngleY", "Value": -1.0, "Blend": "Overwrite"},
                {"Id": "ParamAngleZ", "Value": 6.2, "Blend": "Overwrite"},
                {"Id": "ParamEyeLOpen", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeROpen", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallX", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeBallY", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamPupilScale", "Value": 1.04, "Blend": "Overwrite"},
                {"Id": "ParamBrowLY", "Value": -0.18, "Blend": "Overwrite"},
                {"Id": "ParamBrowRY", "Value": 0.38, "Blend": "Overwrite"},
                {"Id": "ParamBrowLAngle", "Value": 0.0, "Blend": "Overwrite"},
                {"Id": "ParamBrowRAngle", "Value": 0.22, "Blend": "Overwrite"},
                {"Id": "ParamCheek", "Value": 0.35, "Blend": "Overwrite"},
                {"Id": "ParamMouthVariant", "Value": 1.0, "Blend": "Overwrite"},
                {"Id": "ParamEyeLidVariant", "Value": 0.0, "Blend": "Overwrite"},
            ],
        },
    }

    for name, payload in expressions.items():
        obj = {"Type": "Live2D Expression", **payload}
        (OUT_EXPR / f"{name}.exp3.json").write_text(
            json.dumps(obj, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )

    model3 = {
        "Version": 3,
        "Type": 0,
        "FileReferences": {
            "PortraitBustSize": BUST_SIZE,
            "LayersDir": "layers",
            "Expressions": [
                {"Name": name, "File": f"expressions/{name}.exp3.json"}
                for name in expressions.keys()
            ],
        },
        "Controllers": {
            "EyeBlink": {"MinInterval": 500, "MaxInterval": 6000, "Enabled": True},
            "MouseTracking": {"SmoothTime": 0.15, "Enabled": True},
            "AutoBreath": {"Enabled": True},
            "FaceTracking": {"Enabled": True},
        },
    }
    (OUT_DIR / "alita_face.model3.json").write_text(
        json.dumps(model3, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )


def main() -> None:
    OUT_LAYERS.mkdir(parents=True, exist_ok=True)
    # Remove any old unused brow variants from earlier run
    for old in OUT_LAYERS.glob("sourcil_*_*"):
        old.unlink()

    normal_rgba = read_rgba(SPRITES_ALITA / "normal.png")
    torse_rgba = read_rgba(SRC_LAYERS / "torse.png")
    write_rgba(build_seamless_bust_torso(normal_rgba, torse_rgba), OUT_LAYERS / "torse.png")

    passthrough_layers = [
        "tete",
        "oeil_gauche",
        "oeil_droit",
        "pupille_gauche",
        "pupille_droite",
        "paupiere_gauche",
        "paupiere_droite",
        "bouche",
        "bouche_joie",
        "bouche_colere",
        "bouche_malice",
        "bouche_surprise",
        "meches_cheveux",
    ]
    for name in passthrough_layers:
        src = read_rgba(SRC_LAYERS / f"{name}.png")
        write_rgba(crop_bust(src, fade_bottom=False), OUT_LAYERS / f"{name}.png")

    tete_rgba = read_rgba(SRC_LAYERS / "tete.png")
    fatigue_rgba = read_rgba(SPRITES_ALITA / "fatigue.png")
    serieux_rgba = read_rgba(SPRITES_ALITA / "serieux.png")
    calme_rgba = read_rgba(SPRITES_ALITA / "calme.png")

    derived_layers = {
        "joues_colere": synthesize_soft_cheek_blush(),
        "sourcil_gauche": synthesize_clean_eyebrow(is_left=True),
        "sourcil_droit": synthesize_clean_eyebrow(is_left=False),
        "paupiere_fatigue_gauche": extract_sleepy_eyelid(fatigue_rgba, tete_rgba, L_EYE, is_left=True),
        "paupiere_fatigue_droite": extract_sleepy_eyelid(fatigue_rgba, tete_rgba, R_EYE, is_left=False),
        "bouche_serieux": extract_box_non_skin(serieux_rgba, MOUTH),
        "bouche_calme": extract_box_non_skin(calme_rgba, MOUTH),
        "bouche_fatigue": synthesize_yawn_mouth(),
    }
    for name, full_rgba in derived_layers.items():
        write_rgba(crop_bust(full_rgba, fade_bottom=False), OUT_LAYERS / f"{name}.png")

    write_expressions()
    print(f"Built alita_face_live2d in {OUT_DIR}: {1 + len(passthrough_layers) + len(derived_layers)} layers + 11 expressions.")


if __name__ == "__main__":
    main()
