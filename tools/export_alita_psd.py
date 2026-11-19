#!/usr/bin/env python3
"""
Sépare les illustrations générées d'Alita en calques transparents, normalise les yeux,
affine légèrement les hanches, réduit les marges horizontales inutiles, et génère :
1. Les 6 sprites principaux détourés sur fond transparent (PNG 760x1200).
2. Chaque partie mobile séparée en PNG individuel (yeux, pupilles, paupières,
   sourcils, bouches dont malice/langue, mèches/cheveux, tête, torse, bras/mains).
3. Un fichier multi-calques `alita_live2d_layers.psd` (760x1200 RGBA).
"""

import math
import subprocess
from collections import deque
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DRAWABLE = ROOT / "app/src/main/res/drawable"
OUT_DIR = ROOT / "public/assets/alita_live2d"
SPRITES_DIR = OUT_DIR / "sprites"
LAYERS_DIR = OUT_DIR / "layers"

SRC_W, SRC_H = 896, 1200
CROP_X0 = 68
CROP_X1 = 828  # 760px wide, centered around cx=448 (keeps joie's raised waving hand unclipped)
W, H = CROP_X1 - CROP_X0, SRC_H

SOURCE_IMAGES = {
    "normal": DRAWABLE / "alita_normal_1794946673708.jpg",
    "joie": DRAWABLE / "alita_joie_1794946696599.jpg",
    "serieux": DRAWABLE / "alita_serieux_1794946697116.jpg",
    "colere": DRAWABLE / "alita_colere_1794946697579.jpg",
    "calme": DRAWABLE / "alita_calme_1794946700116.jpg",
    "fatigue": DRAWABLE / "alita_fatigue_1794946698944.jpg",
}

INTERIOR_GAP_SEEDS: dict[str, list[tuple[int, int]]] = {
    "normal": [(449, 1086), (315, 650), (580, 650), (345, 655), (553, 655), (325, 268), (572, 268)],
    "joie": [(449, 1086), (315, 650), (580, 650), (345, 655), (553, 655), (326, 268), (572, 268)],
    "serieux": [(449, 1086), (326, 268), (572, 268)],
    "colere": [(449, 1086), (326, 268), (572, 268)],
    "calme": [(449, 1086), (318, 590), (579, 590), (346, 670), (552, 669), (327, 268), (573, 268)],
    "fatigue": [(449, 1086), (580, 650), (306, 543), (345, 655), (553, 655), (326, 268), (573, 268)],
}


def run(cmd: list[str]) -> None:
    subprocess.run(cmd, check=True)


def save_rgba_png(rgb: bytearray, alpha: bytearray, out_path: Path, width: int = W, height: int = H) -> None:
    """Écrit deux buffers RGB + Alpha en PNG RGBA."""
    hdr = f"P7\nWIDTH {width}\nHEIGHT {height}\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n".encode()
    buf = bytearray(width * height * 4)
    for i in range(width * height):
        o4 = i * 4
        o3 = i * 3
        buf[o4] = rgb[o3]
        buf[o4 + 1] = rgb[o3 + 1] = rgb[o3 + 1] if False else rgb[o3 + 1]
        buf[o4 + 1] = rgb[o3 + 1]
        buf[o4 + 2] = rgb[o3 + 2]
        buf[o4 + 3] = alpha[i]
    subprocess.run(
        ["convert", "pam:-", "-type", "TrueColorAlpha", str(out_path)],
        input=hdr + bytes(buf),
        check=True,
    )


def load_jpg_rgb(src_jpg: Path) -> bytearray:
    pam = subprocess.check_output(
        ["convert", str(src_jpg), "-colorspace", "sRGB", "-type", "TrueColor", "-depth", "8", "pam:-"]
    )
    end_hdr = pam.find(b"ENDHDR\n") + len(b"ENDHDR\n")
    return bytearray(pam[end_hdr:])


def erase_header_text_on_rgb(rgb: bytearray) -> None:
    rects = [
        (0, 0, 415, 62),
        (0, 62, 390, 74),
        (0, 74, 320, 145),
    ]
    for x0, y0, x1, y1 in rects:
        for y in range(y0, y1 + 1):
            row = y * SRC_W
            for x in range(x0, x1 + 1):
                o = (row + x) * 3
                rgb[o] = 255
                rgb[o + 1] = 255
                rgb[o + 2] = 255


def is_bg_white(r: int, g: int, b: int, lum_thresh: int = 236, chroma_thresh: int = 12) -> bool:
    mx = max(r, g, b)
    mn = min(r, g, b)
    return mx >= lum_thresh and mn >= (lum_thresh - 8) and (mx - mn) <= chroma_thresh


def floodfill_transparent(rgb: bytearray, alpha: bytearray, seeds: list[tuple[int, int]], lum_thresh: int = 236) -> None:
    q = deque()
    for sx, sy in seeds:
        if 0 <= sx < SRC_W and 0 <= sy < SRC_H:
            i = sy * SRC_W + sx
            if alpha[i] == 0:
                continue
            o = i * 3
            if is_bg_white(rgb[o], rgb[o + 1], rgb[o + 2], lum_thresh=lum_thresh):
                alpha[i] = 0
                q.append((sx, sy))

    while q:
        cx, cy = q.popleft()
        for nx, ny in ((cx - 1, cy), (cx + 1, cy), (cx, cy - 1), (cx, cy + 1)):
            if 0 <= nx < SRC_W and 0 <= ny < SRC_H:
                ni = ny * SRC_W + nx
                if alpha[ni] != 0:
                    no = ni * 3
                    if is_bg_white(rgb[no], rgb[no + 1], rgb[no + 2], lum_thresh=lum_thresh):
                        alpha[ni] = 0
                        q.append((nx, ny))


def defringe_white_halo(rgb: bytearray, alpha: bytearray) -> None:
    for ring in range(2):
        snap_a = bytes(alpha)
        candidates: list[tuple[int, int, tuple[int, int, int]]] = []

        for y in range(SRC_H):
            row = y * SRC_W
            for x in range(SRC_W):
                i = row + x
                if snap_a[i] == 0:
                    continue

                touches_bg = False
                for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                    if 0 <= nx < SRC_W and 0 <= ny < SRC_H and snap_a[ny * SRC_W + nx] == 0:
                        touches_bg = True
                        break
                if not touches_bg:
                    continue

                o = i * 3
                r, g, b = rgb[o], rgb[o + 1], rgb[o + 2]
                mx = max(r, g, b)
                mn = min(r, g, b)
                lum = (r + g + b) / 3.0
                if (mx - mn) > 22 or lum < 110.0:
                    continue

                ink_r = ink_g = ink_b = ink_cnt = 0
                for dy in range(-2, 3):
                    yy = y + dy
                    if yy < 0 or yy >= SRC_H:
                        continue
                    for dx in range(-2, 3):
                        xx = x + dx
                        if xx < 0 or xx >= SRC_W or (dx == 0 and dy == 0):
                            continue
                        ni = yy * SRC_W + xx
                        if snap_a[ni] < 200:
                            continue
                        no = ni * 3
                        nr, ng, nb = rgb[no], rgb[no + 1], rgb[no + 2]
                        if (nr + ng + nb) / 3.0 < 115.0:
                            ink_r += nr
                            ink_g += ng
                            ink_b += nb
                            ink_cnt += 1

                if ink_cnt == 0:
                    continue

                avg_ink = (ink_r // ink_cnt, ink_g // ink_cnt, ink_b // ink_cnt)
                ink_lum = (avg_ink[0] + avg_ink[1] + avg_ink[2]) / 3.0
                denom = max(1.0, 245.0 - ink_lum)
                cov = max(0.0, min(1.0, (242.0 - lum) / denom))
                if ring == 0 and lum >= 218.0:
                    cov = 0.0
                new_a = int(round(cov * snap_a[i]))
                candidates.append((i, new_a, avg_ink))

        for i, new_a, ink_col in candidates:
            o = i * 3
            alpha[i] = new_a
            if new_a > 0:
                rgb[o] = ink_col[0]
                rgb[o + 1] = ink_col[1]
                rgb[o + 2] = ink_col[2]
            else:
                rgb[o] = 0
                rgb[o + 1] = 0
                rgb[o + 2] = 0


def is_skin_pixel(r: int, g: int, b: int, a: int) -> bool:
    return a > 128 and r > 205 and g > 175 and b > 155 and r >= g >= b


def is_hair_pixel(r: int, g: int, b: int, a: int) -> bool:
    if a < 128:
        return False
    lum = (r + g + b) / 3.0
    return lum < 92 and abs(b - r) < 28


def is_cyan_iris(r: int, g: int, b: int, a: int) -> bool:
    return a > 128 and b > 145 and g > 115 and (b - r) > 42


def normalize_eyes_to_natural(rgb: bytearray, alpha: bytearray, key: str) -> None:
    """
    Remplace les yeux cyan lumineux par des yeux humains/anime naturels :
    sclère blanche propre, iris brun-noisette chaud, pupille noire et reflets blancs.
    """
    if key == "calme":
        return  # Yeux déjà fermés

    eye_boxes = [
        (380, 208, 434, 242),
        (462, 208, 516, 242),
    ]

    for x0, y0, x1, y1 in eye_boxes:
        cyan_xs = []
        cyan_ys = []
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                i = y * SRC_W + x
                o = i * 3
                r, g, b = rgb[o], rgb[o + 1], rgb[o + 2]
                if alpha[i] > 128 and b > 150 and (b - r) > 50:
                    cyan_xs.append(x)
                    cyan_ys.append(y)

        if not cyan_xs:
            continue

        min_x, max_x = min(cyan_xs), max(cyan_xs)
        min_y, max_y = min(cyan_ys), max(cyan_ys)
        cx = (min_x + max_x) * 0.5
        cy = (min_y + max_y) * 0.5 - 0.5
        rx = min(11.6, max(9.2, (max_x - min_x) * 0.46))
        ry = min(10.8 if key != "fatigue" else 7.0, max(8.5 if key != "fatigue" else 6.0, (max_y - min_y) * 0.48))

        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                i = y * SRC_W + x
                if alpha[i] < 64:
                    continue
                o = i * 3
                r, g, b = rgb[o], rgb[o + 1], rgb[o + 2]
                if is_skin_pixel(r, g, b, alpha[i]):
                    continue

                lum = (r + g + b) / 3.0
                dx = (x - cx) / rx
                dy = (y - cy) / ry
                dist = math.hypot(dx, dy)

                # Nettoie les reflets cyan sur les cils sombres (les garde noir-charbon)
                if lum < 95 and (b - r) > 12 and dist > 0.78:
                    v = int(max(16, min(80, lum * 0.85)))
                    rgb[o], rgb[o + 1], rgb[o + 2] = v, v, v
                    continue

                if dist <= 1.00 and (b - r > 18 or (lum < 140 and dist <= 0.75)):
                    if dy < -0.78 and lum < 85:
                        continue

                    if dist > 0.84:
                        nr, ng, nb = 38, 25, 20
                    elif dist <= 0.37:
                        nr, ng, nb = 20, 16, 16
                    else:
                        t_v = max(0.0, min(1.0, (dy + 0.8) / 1.6))
                        nr = int(68 + 86 * t_v)
                        ng = int(42 + 60 * t_v)
                        nb = int(28 + 38 * t_v)

                    if dist > 0.90:
                        blend = max(0.0, min(1.0, (1.00 - dist) / 0.10))
                        nr = int(nr * blend + 240 * (1.0 - blend))
                        ng = int(ng * blend + 240 * (1.0 - blend))
                        nb = int(nb * blend + 242 * (1.0 - blend))

                    d_spec1 = math.hypot(x - (cx - 0.34 * rx), y - (cy - 0.34 * ry))
                    d_spec2 = math.hypot(x - (cx + 0.30 * rx), y - (cy + 0.30 * ry))
                    if d_spec1 <= 2.1:
                        s = max(0.0, min(1.0, 1.0 - (d_spec1 - 1.2) / 0.9))
                        nr = int(nr * (1 - s) + 255 * s)
                        ng = int(ng * (1 - s) + 255 * s)
                        nb = int(nb * (1 - s) + 255 * s)
                    elif d_spec2 <= 1.35:
                        s = max(0.0, min(1.0, 1.0 - (d_spec2 - 0.7) / 0.65))
                        nr = int(nr * (1 - s) + 248 * s)
                        ng = int(ng * (1 - s) + 248 * s)
                        nb = int(nb * (1 - s) + 252 * s)

                    rgb[o] = max(0, min(255, nr))
                    rgb[o + 1] = max(0, min(255, ng))
                    rgb[o + 2] = max(0, min(255, nb))
                elif (b - r >= 4 or dist <= 1.35) and lum >= 55 and dist > 0.88:
                    w = int(min(248, max(232, lum * 1.24)))
                    if dy < -0.45:
                        w = int(w * 0.94)
                    rgb[o] = w
                    rgb[o + 1] = w
                    rgb[o + 2] = min(255, w + 2)


def smoothstep(edge0: float, edge1: float, x: float) -> float:
    t = max(0.0, min(1.0, (x - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)


def sample_bilinear(rgb: bytearray, alpha: bytearray, fx: float, fy: float) -> tuple[int, int, int, int]:
    if fx < 0 or fx >= SRC_W - 1 or fy < 0 or fy >= SRC_H - 1:
        return (0, 0, 0, 0)
    x0 = int(fx)
    y0 = int(fy)
    dx = fx - x0
    dy = fy - y0

    def get(px: int, py: int) -> tuple[int, int, int, int]:
        idx = py * SRC_W + px
        o = idx * 3
        return (rgb[o], rgb[o + 1], rgb[o + 2], alpha[idx])

    c00 = get(x0, y0)
    c10 = get(x0 + 1, y0)
    c01 = get(x0, y0 + 1)
    c11 = get(x0 + 1, y0 + 1)

    w00 = (1.0 - dx) * (1.0 - dy)
    w10 = dx * (1.0 - dy)
    w01 = (1.0 - dx) * dy
    w11 = dx * dy

    a = int(round(c00[3] * w00 + c10[3] * w10 + c01[3] * w01 + c11[3] * w11))
    if a == 0:
        return (0, 0, 0, 0)

    # Moyenne pondérée par alpha pour éviter les bordures grises/noires
    wa00 = w00 * c00[3]
    wa10 = w10 * c10[3]
    wa01 = w01 * c01[3]
    wa11 = w11 * c11[3]
    wsum = wa00 + wa10 + wa01 + wa11
    if wsum <= 1e-6:
        return (0, 0, 0, 0)
    r = int(round((c00[0] * wa00 + c10[0] * wa10 + c01[0] * wa01 + c11[0] * wa11) / wsum))
    g = int(round((c00[1] * wa00 + c10[1] * wa10 + c01[1] * wa01 + c11[1] * wa11) / wsum))
    b = int(round((c00[2] * wa00 + c10[2] * wa10 + c01[2] * wa01 + c11[2] * wa11) / wsum))
    return (r, g, b, a)


def point_in_poly(x: float, y: float, poly: list[tuple[float, float]]) -> bool:
    inside = False
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if ((yi > y) != (yj > y)) and (x < (xj - xi) * (y - yi) / ((yj - yi) or 1e-9) + xi):
            inside = not inside
        j = i
    return inside


LEFT_ARM_POLY = [
    (0, 355), (336, 355), (344, 435), (332, 540), (315, 650), (275, 780), (242, 895), (235, 1100), (0, 1100)
]
RIGHT_ARM_POLY = [
    (SRC_W, 355), (560, 355), (552, 435), (564, 540), (581, 650), (621, 780), (654, 895), (661, 1100), (SRC_W, 1100)
]


def slim_hips_and_tuck_arms(rgb: bytearray, alpha: bytearray, key: str) -> None:
    """
    Affine les hanches (~11%) sans déformer les bras qui pendent le long du corps,
    et rapproche légèrement les bras pendants vers la taille pour réduire l'emprise latérale.
    """
    cx = 448.5
    body_rgb = bytearray(len(rgb))
    body_a = bytearray(len(alpha))
    arm_rgb = bytearray(len(rgb))
    arm_a = bytearray(len(alpha))

    has_left_hang = key in ("normal", "joie")
    has_right_hang = key in ("normal", "fatigue")

    for y in range(SRC_H):
        for x in range(SRC_W):
            i = y * SRC_W + x
            if alpha[i] == 0:
                continue
            o = i * 3
            in_l = has_left_hang and y >= 355 and point_in_poly(x, y, LEFT_ARM_POLY)
            in_r = has_right_hang and y >= 355 and point_in_poly(x, y, RIGHT_ARM_POLY)
            if in_l or in_r:
                arm_rgb[o:o+3] = rgb[o:o+3]
                arm_a[i] = alpha[i]
            else:
                body_rgb[o:o+3] = rgb[o:o+3]
                body_a[i] = alpha[i]

    out_rgb = bytearray(len(rgb))
    out_a = bytearray(len(alpha))

    # 1. Affinement des hanches (y >= 620) sur le corps uniquement
    for y in range(SRC_H):
        if y < 620:
            row = y * SRC_W
            out_rgb[row*3:(row+SRC_W)*3] = body_rgb[row*3:(row+SRC_W)*3]
            out_a[row:row+SRC_W] = body_a[row:row+SRC_W]
            continue

        ramp_up = smoothstep(620.0, 825.0, float(y))
        taper_down = 1.0 - 0.25 * smoothstep(950.0, 1200.0, float(y))
        scale_x = 1.0 - 0.115 * ramp_up * taper_down

        for x in range(SRC_W):
            src_x = cx + (x - cx) / scale_x
            r, g, b, a = sample_bilinear(body_rgb, body_a, src_x, float(y))
            if a > 0:
                i = y * SRC_W + x
                o = i * 3
                out_rgb[o], out_rgb[o + 1], out_rgb[o + 2], out_a[i] = r, g, b, a

    # 2. Ré-application des bras pendants légèrement rentrés vers le buste
    if has_left_hang or has_right_hang:
        for y in range(355, SRC_H):
            tuck = 15.0 * smoothstep(370.0, 680.0, float(y))
            for x in range(SRC_W):
                shift = 0.0
                if has_left_hang and x < 365:
                    shift = -tuck  # échantillonne à gauche -> déplace le bras vers la droite (vers la taille)
                elif has_right_hang and x > 530:
                    shift = tuck   # échantillonne à droite -> déplace le bras vers la gauche (vers la taille)
                else:
                    continue
                r, g, b, a = sample_bilinear(arm_rgb, arm_a, float(x) + shift, float(y))
                if a > 0:
                    i = y * SRC_W + x
                    o = i * 3
                    if a >= 250 or out_a[i] == 0:
                        out_rgb[o], out_rgb[o + 1], out_rgb[o + 2], out_a[i] = r, g, b, a
                    else:
                        fa = a / 255.0
                        inv = 1.0 - fa
                        out_rgb[o] = int(r * fa + out_rgb[o] * inv)
                        out_rgb[o + 1] = int(g * fa + out_rgb[o + 1] * inv)
                        out_rgb[o + 2] = int(b * fa + out_rgb[o + 2] * inv)
                        out_a[i] = max(out_a[i], a)

    rgb[:] = out_rgb
    alpha[:] = out_a


def process_pose(key: str, src_jpg: Path) -> tuple[bytearray, bytearray]:
    rgb = load_jpg_rgb(src_jpg)
    erase_header_text_on_rgb(rgb)
    alpha = bytearray([255] * (SRC_W * SRC_H))

    outer_seeds = [(0, 0), (SRC_W - 1, 0), (0, SRC_H - 1), (SRC_W - 1, SRC_H - 1)]
    floodfill_transparent(rgb, alpha, outer_seeds, lum_thresh=232)
    floodfill_transparent(rgb, alpha, INTERIOR_GAP_SEEDS.get(key, [(449, 1086)]), lum_thresh=216)
    defringe_white_halo(rgb, alpha)
    normalize_eyes_to_natural(rgb, alpha, key)
    slim_hips_and_tuck_arms(rgb, alpha, key)
    return rgb, alpha


def crop_horizontal(src_rgb: bytearray, src_a: bytearray) -> tuple[bytearray, bytearray]:
    """Recadre horizontalement de 896x1200 vers 760x1200 pour supprimer les marges vides."""
    dst_rgb = bytearray(W * H * 3)
    dst_a = bytearray(W * H)
    for y in range(H):
        s_row = y * SRC_W + CROP_X0
        d_row = y * W
        dst_rgb[d_row * 3:(d_row + W) * 3] = src_rgb[s_row * 3:(s_row + W) * 3]
        dst_a[d_row:d_row + W] = src_a[s_row:s_row + W]
    return dst_rgb, dst_a


def sample_skin_color(rgb: bytearray, alpha: bytearray, x0: int, y0: int, x1: int, y1: int) -> tuple[int, int, int]:
    rs, gs, bs = [], [], []
    for y in range(max(0, y0), min(SRC_H, y1)):
        for x in range(max(0, x0), min(SRC_W, x1)):
            i = y * SRC_W + x
            o = i * 3
            r, g, b = rgb[o], rgb[o + 1], rgb[o + 2]
            if is_skin_pixel(r, g, b, alpha[i]):
                rs.append(r)
                gs.append(g)
                bs.append(b)
    if not rs:
        return (248, 228, 214)
    return (sum(rs) // len(rs), sum(gs) // len(gs), sum(bs) // len(bs))


def synthesize_malice_mouth(dst_rgb: bytearray, dst_a: bytearray, skin_col: tuple[int, int, int]) -> None:
    """
    Dessine une bouche espiègle tirant la langue (`bouche_malice`) en coordonnées SRC (896x1200).
    """
    def put(x: int, y: int, col: tuple[int, int, int], a: int = 255) -> None:
        if 0 <= x < SRC_W and 0 <= y < SRC_H:
            i = y * SRC_W + x
            o = i * 3
            dst_rgb[o], dst_rgb[o + 1], dst_rgb[o + 2], dst_a[i] = col[0], col[1], col[2], a

    # Fond peau dégradé sur la zone bouche pour masquer les commissures neutres sans bord rectangle
    for y in range(275, 299):
        for x in range(427, 470):
            dx = abs(x - 448.5) / 21.0
            dy = abs(y - 287.0) / 12.0
            r_box = max(dx, dy)
            if r_box <= 1.0:
                feather = 1.0 if r_box <= 0.65 else max(0.0, (1.0 - r_box) / 0.35)
                put(x, y, skin_col, int(255 * feather))

    # Ligne supérieure souriante (x=435..462, y=279..282)
    for x in range(435, 463):
        t = (x - 448.5) / 14.0
        y_top = 281.0 - 1.8 * (t * t)
        for dy in range(-1, 2):
            yy = int(round(y_top + dy))
            if abs(dy) == 0:
                put(x, yy, (42, 32, 36), 255)
            else:
                put(x, yy, (95, 72, 74), 170)

    # Langue rose corail sortant vers le bas (x=441..457, y=281..296)
    tcx = 449.5
    tcy = 285.5
    trx = 7.4
    try_down = 10.5
    for y in range(281, 298):
        for x in range(440, 459):
            if y < 282:
                continue
            dx = (x - tcx) / trx
            dy = (y - tcy) / try_down
            # Forme arrondie en U de la langue
            d = math.hypot(dx, max(0.0, dy))
            if y < 286:
                d = abs(dx)
            if d <= 1.08:
                if d >= 0.86:
                    # Contour sombre fin de la langue
                    a = int(255 * max(0.2, min(1.0, (1.08 - d) / 0.22)))
                    put(x, y, (58, 34, 42), a)
                else:
                    # Remplissage rose corail avec léger reflet et trait central
                    shade = max(0.0, min(1.0, (y - 282) / 12.0))
                    r = int(238 - 18 * shade)
                    g = int(134 - 24 * shade)
                    b = int(146 - 18 * shade)
                    # Trait central léger de la langue
                    if abs(x - tcx) <= 0.7 and 284 <= y <= 291:
                        r, g, b = int(r * 0.82), int(g * 0.74), int(b * 0.78)
                    # Petit éclat brillant
                    if math.hypot(x - (tcx - 2.2), y - 288.0) <= 1.6:
                        r, g, b = 250, 182, 190
                    put(x, y, (r, g, b), 255)


def synthesize_surprise_mouth(dst_rgb: bytearray, dst_a: bytearray, skin_col: tuple[int, int, int]) -> None:
    """
    Dessine une petite bouche surprise en « O » (`bouche_surprise`) en coordonnées SRC (896x1200).
    """
    def put(x: int, y: int, col: tuple[int, int, int], a: int = 255) -> None:
        if 0 <= x < SRC_W and 0 <= y < SRC_H:
            i = y * SRC_W + x
            o = i * 3
            dst_rgb[o], dst_rgb[o + 1], dst_rgb[o + 2], dst_a[i] = col[0], col[1], col[2], a

    # Fond peau dégradé pour masquer la bouche neutre sans contour visible
    for y in range(274, 299):
        for x in range(427, 470):
            dx = abs(x - 448.5) / 21.0
            dy = abs(y - 286.5) / 12.5
            r_box = max(dx, dy)
            if r_box <= 1.0:
                feather = 1.0 if r_box <= 0.65 else max(0.0, (1.0 - r_box) / 0.35)
                put(x, y, skin_col, int(255 * feather))

    # Petite bouche ronde/ovale en « O » centrée sur x=448.5, y=286.0
    ocx = 448.5
    ocy = 286.0
    orx = 5.4
    ory = 6.2
    for y in range(278, 295):
        for x in range(441, 457):
            dx = (x - ocx) / orx
            dy = (y - ocy) / ory
            d = math.hypot(dx, dy)
            if d <= 1.15:
                if d >= 0.84:
                    # Contour sombre fin des lèvres
                    a = int(255 * max(0.25, min(1.0, (1.15 - d) / 0.31)))
                    put(x, y, (56, 34, 40), a)
                else:
                    # Intérieur chaud (bordeaux sombre en haut, corail doux en bas pour la langue)
                    t_y = max(0.0, min(1.0, (y - (ocy - ory)) / (2.0 * ory)))
                    if t_y < 0.52:
                        r, g, b = 104, 52, 62
                    else:
                        s = (t_y - 0.52) / 0.48
                        r = int(156 + 52 * s)
                        g = int(84 + 36 * s)
                        b = int(96 + 34 * s)
                    put(x, y, (r, g, b), 255)


def main() -> None:
    SPRITES_DIR.mkdir(parents=True, exist_ok=True)
    LAYERS_DIR.mkdir(parents=True, exist_ok=True)

    full_poses: dict[str, tuple[bytearray, bytearray]] = {}
    for key, src in SOURCE_IMAGES.items():
        rgb, alpha = process_pose(key, src)
        full_poses[key] = (rgb, alpha)
        c_rgb, c_a = crop_horizontal(rgb, alpha)
        save_rgba_png(c_rgb, c_a, SPRITES_DIR / f"alita_{key}.png")

    n_rgb, n_alpha = full_poses["normal"]
    c_rgb, c_alpha = full_poses["calme"]
    j_rgb, j_alpha = full_poses["joie"]
    a_rgb, a_alpha = full_poses["colere"]

    skin_color = sample_skin_color(n_rgb, n_alpha, 430, 238, 466, 265)

    def make_empty() -> tuple[bytearray, bytearray]:
        return bytearray(SRC_W * SRC_H * 3), bytearray(SRC_W * SRC_H)

    def copy_pixel(src_rgb: bytearray, src_a: bytearray, dst_rgb: bytearray, dst_a: bytearray, x: int, y: int) -> None:
        i = y * SRC_W + x
        o = i * 3
        dst_rgb[o:o+3] = src_rgb[o:o+3]
        dst_a[i] = src_a[i]

    def set_pixel(dst_rgb: bytearray, dst_a: bytearray, x: int, y: int, col: tuple[int, int, int], a: int) -> None:
        i = y * SRC_W + x
        o = i * 3
        dst_rgb[o], dst_rgb[o + 1], dst_rgb[o + 2] = col
        dst_a[i] = a

    L_BROW = (380, 190, 432, 208)
    R_BROW = (464, 190, 516, 208)
    L_EYE = (380, 208, 434, 242)
    R_EYE = (462, 208, 516, 242)
    MOUTH = (424, 273, 472, 300)

    def in_box(x: int, y: int, box: tuple[int, int, int, int]) -> bool:
        return box[0] <= x <= box[2] and box[1] <= y <= box[3]

    torse_rgb, torse_a = make_empty()
    larm_rgb, larm_a = make_empty()
    rarm_rgb, rarm_a = make_empty()
    tete_rgb, tete_a = make_empty()
    meches_rgb, meches_a = make_empty()
    lbrow_rgb, lbrow_a = make_empty()
    rbrow_rgb, rbrow_a = make_empty()
    leye_rgb, leye_a = make_empty()
    reye_rgb, reye_a = make_empty()
    lpup_rgb, lpup_a = make_empty()
    rpup_rgb, rpup_a = make_empty()
    lpaup_rgb, lpaup_a = make_empty()
    rpaup_rgb, rpaup_a = make_empty()
    bouche_rgb, bouche_a = make_empty()
    bouche_joie_rgb, bouche_joie_a = make_empty()
    bouche_colere_rgb, bouche_colere_a = make_empty()
    bouche_malice_rgb, bouche_malice_a = make_empty()
    bouche_surprise_rgb, bouche_surprise_a = make_empty()

    hair_bg_color = (34, 38, 44)
    dark_suit_color = (36, 40, 46)

    for y in range(SRC_H):
        for x in range(SRC_W):
            i = y * SRC_W + x
            a = n_alpha[i]
            if a == 0:
                continue
            o = i * 3
            r, g, b = n_rgb[o], n_rgb[o + 1], n_rgb[o + 2]

            if y >= 355:
                if point_in_poly(x, y, LEFT_ARM_POLY):
                    copy_pixel(n_rgb, n_alpha, larm_rgb, larm_a, x, y)
                    # Sous-couche sombre sous l'épaule & bordure interne pour autoriser l'élévation des bras
                    if y <= 465 or not point_in_poly(x + 14, y, LEFT_ARM_POLY):
                        set_pixel(torse_rgb, torse_a, x, y, dark_suit_color, a)
                elif point_in_poly(x, y, RIGHT_ARM_POLY):
                    copy_pixel(n_rgb, n_alpha, rarm_rgb, rarm_a, x, y)
                    if y <= 465 or not point_in_poly(x - 14, y, RIGHT_ARM_POLY):
                        set_pixel(torse_rgb, torse_a, x, y, dark_suit_color, a)
                else:
                    copy_pixel(n_rgb, n_alpha, torse_rgb, torse_a, x, y)
                continue

            if y >= 322:
                lum = (r + g + b) / 3.0
                if 412 <= x <= 484 and (lum < 110 or y < 332):
                    copy_pixel(n_rgb, n_alpha, tete_rgb, tete_a, x, y)
                    set_pixel(torse_rgb, torse_a, x, y, dark_suit_color, a)
                else:
                    copy_pixel(n_rgb, n_alpha, torse_rgb, torse_a, x, y)
                continue

            if in_box(x, y, L_BROW):
                if is_skin_pixel(r, g, b, a):
                    copy_pixel(n_rgb, n_alpha, tete_rgb, tete_a, x, y)
                else:
                    copy_pixel(n_rgb, n_alpha, lbrow_rgb, lbrow_a, x, y)
                    set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                continue

            if in_box(x, y, R_BROW):
                if is_skin_pixel(r, g, b, a):
                    copy_pixel(n_rgb, n_alpha, tete_rgb, tete_a, x, y)
                else:
                    copy_pixel(n_rgb, n_alpha, rbrow_rgb, rbrow_a, x, y)
                    set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                continue

            # Yeux & Iris naturels (sépare l'iris/pupille mobile de la sclère/cils)
            if in_box(x, y, L_EYE):
                dx = (x - 408.5) / 11.4
                dy = (y - 223.5) / 10.6
                is_iris = math.hypot(dx, dy) <= 1.00 and (r + g + b) / 3.0 < 185 and not is_skin_pixel(r, g, b, a)
                if is_iris:
                    copy_pixel(n_rgb, n_alpha, lpup_rgb, lpup_a, x, y)
                    set_pixel(leye_rgb, leye_a, x, y, (245, 247, 249), 255)
                    set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                elif not is_skin_pixel(r, g, b, a):
                    copy_pixel(n_rgb, n_alpha, leye_rgb, leye_a, x, y)
                    if x >= 390:
                        set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                else:
                    copy_pixel(n_rgb, n_alpha, tete_rgb, tete_a, x, y)
                continue

            if in_box(x, y, R_EYE):
                dx = (x - 488.5) / 11.4
                dy = (y - 223.5) / 10.6
                is_iris = math.hypot(dx, dy) <= 1.00 and (r + g + b) / 3.0 < 185 and not is_skin_pixel(r, g, b, a)
                if is_iris:
                    copy_pixel(n_rgb, n_alpha, rpup_rgb, rpup_a, x, y)
                    set_pixel(reye_rgb, reye_a, x, y, (245, 247, 249), 255)
                    set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                elif not is_skin_pixel(r, g, b, a):
                    copy_pixel(n_rgb, n_alpha, reye_rgb, reye_a, x, y)
                    if x <= 506:
                        set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                else:
                    copy_pixel(n_rgb, n_alpha, tete_rgb, tete_a, x, y)
                continue

            if in_box(x, y, MOUTH):
                if is_skin_pixel(r, g, b, a):
                    copy_pixel(n_rgb, n_alpha, tete_rgb, tete_a, x, y)
                else:
                    copy_pixel(n_rgb, n_alpha, bouche_rgb, bouche_a, x, y)
                    set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                continue

            if y < 188 or ((x < 394 or x > 502) and y < 312) or (y < 212 and is_hair_pixel(r, g, b, a)):
                if is_hair_pixel(r, g, b, a) or y < 180 or x < 388 or x > 508:
                    copy_pixel(n_rgb, n_alpha, meches_rgb, meches_a, x, y)
                    if 402 <= x <= 494 and 168 <= y <= 212:
                        set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                    elif 372 <= x <= 524 and 130 <= y <= 315:
                        set_pixel(tete_rgb, tete_a, x, y, hair_bg_color, a)
                    continue

            copy_pixel(n_rgb, n_alpha, tete_rgb, tete_a, x, y)

    # Paupières fermées (depuis alita_calme)
    for box, dst_rgb, dst_a in [
        (L_EYE, lpaup_rgb, lpaup_a),
        (R_EYE, rpaup_rgb, rpaup_a),
    ]:
        for y in range(box[1], box[3] + 1):
            for x in range(box[0], box[2] + 1):
                i = y * SRC_W + x
                if c_alpha[i] > 0:
                    copy_pixel(c_rgb, c_alpha, dst_rgb, dst_a, x, y)

    # Bouches alternatives (Joie / Colère / Malice langue)
    for src_rgb, src_a, dst_rgb, dst_a in [
        (j_rgb, j_alpha, bouche_joie_rgb, bouche_joie_a),
        (a_rgb, a_alpha, bouche_colere_rgb, bouche_colere_a),
    ]:
        for y in range(MOUTH[1], MOUTH[3] + 1):
            for x in range(MOUTH[0], MOUTH[2] + 1):
                i = y * SRC_W + x
                o = i * 3
                if src_a[i] > 0 and not is_skin_pixel(src_rgb[o], src_rgb[o + 1], src_rgb[o + 2], src_a[i]):
                    copy_pixel(src_rgb, src_a, dst_rgb, dst_a, x, y)

    mouth_skin = sample_skin_color(n_rgb, n_alpha, 430, 268, 468, 278)
    synthesize_malice_mouth(bouche_malice_rgb, bouche_malice_a, mouth_skin)
    synthesize_surprise_mouth(bouche_surprise_rgb, bouche_surprise_a, mouth_skin)

    # Corps sans mèches (pose_*_body.png) recadrés en 760x1200
    for emo_key in ("joie", "serieux", "colere", "calme", "fatigue"):
        p_rgb_orig, p_alpha_orig = full_poses[emo_key]
        p_rgb = bytearray(p_rgb_orig)
        p_alpha = bytearray(p_alpha_orig)
        for y in range(60, 322):
            row = y * SRC_W
            for x in range(310, 590):
                i = row + x
                if meches_a[i] > 180:
                    if 402 <= x <= 494 and 168 <= y <= 212:
                        set_pixel(p_rgb, p_alpha, x, y, skin_color, 255)
                    elif 372 <= x <= 524 and 130 <= y <= 315:
                        set_pixel(p_rgb, p_alpha, x, y, hair_bg_color, p_alpha[i])
                    else:
                        p_alpha[i] = 0
                        o = i * 3
                        p_rgb[o] = p_rgb[o + 1] = p_rgb[o + 2] = 0
        c_p_rgb, c_p_a = crop_horizontal(p_rgb, p_alpha)
        save_rgba_png(c_p_rgb, c_p_a, LAYERS_DIR / f"pose_{emo_key}_body.png")

    ordered_layers: list[tuple[str, tuple[bytearray, bytearray] | Path]] = [
        ("pose_fatigue", SPRITES_DIR / "alita_fatigue.png"),
        ("pose_calme", SPRITES_DIR / "alita_calme.png"),
        ("pose_colere", SPRITES_DIR / "alita_colere.png"),
        ("pose_serieux", SPRITES_DIR / "alita_serieux.png"),
        ("pose_joie", SPRITES_DIR / "alita_joie.png"),
        ("torse", (torse_rgb, torse_a)),
        ("bras_main_gauche", (larm_rgb, larm_a)),
        ("bras_main_droit", (rarm_rgb, rarm_a)),
        ("tete", (tete_rgb, tete_a)),
        ("oeil_gauche", (leye_rgb, leye_a)),
        ("oeil_droit", (reye_rgb, reye_a)),
        ("pupille_gauche", (lpup_rgb, lpup_a)),
        ("pupille_droite", (rpup_rgb, rpup_a)),
        ("paupiere_gauche", (lpaup_rgb, lpaup_a)),
        ("paupiere_droite", (rpaup_rgb, rpaup_a)),
        ("sourcil_gauche", (lbrow_rgb, lbrow_a)),
        ("sourcil_droit", (rbrow_rgb, rbrow_a)),
        ("bouche", (bouche_rgb, bouche_a)),
        ("bouche_joie", (bouche_joie_rgb, bouche_joie_a)),
        ("bouche_colere", (bouche_colere_rgb, bouche_colere_a)),
        ("bouche_malice", (bouche_malice_rgb, bouche_malice_a)),
        ("bouche_surprise", (bouche_surprise_rgb, bouche_surprise_a)),
        ("meches_cheveux", (meches_rgb, meches_a)),
    ]

    layer_pngs: list[tuple[str, Path]] = []
    for name, payload in ordered_layers:
        out_png = LAYERS_DIR / f"{name}.png"
        if isinstance(payload, Path):
            run(["cp", str(payload), str(out_png)])
        else:
            cr_rgb, cr_a = crop_horizontal(payload[0], payload[1])
            save_rgba_png(cr_rgb, cr_a, out_png)
        layer_pngs.append((name, out_png))

    psd_path = OUT_DIR / "alita_live2d_layers.psd"
    cmd = ["convert", str(SPRITES_DIR / "alita_normal.png")]
    for name, png in layer_pngs:
        cmd.extend(["(", str(png), "-set", "label", name, ")"])
    cmd.extend(["-type", "TrueColorAlpha", str(psd_path)])
    run(cmd)

    print(f"PSD généré : {psd_path} ({psd_path.stat().st_size // 1024} Ko, {len(layer_pngs)} calques, {W}x{H})")


if __name__ == "__main__":
    main()
