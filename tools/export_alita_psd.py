#!/usr/bin/env python3
"""
Sépare les illustrations générées d'Alita en calques transparents et génère :
1. Les 6 sprites détourés sur fond transparent (PNG).
2. Chaque partie mobile séparée en PNG individuel (yeux, pupilles, paupières,
   sourcils, bouche, mèches/cheveux, tête, torse, bras/mains).
3. Un fichier multi-calques `alita_live2d_layers.psd` (896x1200 RGBA).
"""

import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DRAWABLE = ROOT / "app/src/main/res/drawable"
OUT_DIR = ROOT / "public/assets/alita_live2d"
SPRITES_DIR = OUT_DIR / "sprites"
LAYERS_DIR = OUT_DIR / "layers"

W, H = 896, 1200

SOURCE_IMAGES = {
    "normal": DRAWABLE / "alita_normal_1794946673708.jpg",
    "joie": DRAWABLE / "alita_joie_1794946696599.jpg",
    "serieux": DRAWABLE / "alita_serieux_1794946697116.jpg",
    "colere": DRAWABLE / "alita_colere_1794946697579.jpg",
    "calme": DRAWABLE / "alita_calme_1794946700116.jpg",
    "fatigue": DRAWABLE / "alita_fatigue_1794946698944.jpg",
}


def run(cmd: list[str]) -> None:
    subprocess.run(cmd, check=True)


def load_ppm_rgba(png_path: Path) -> tuple[bytearray, bytearray]:
    """Charge un PNG en buffers RGB et Alpha (W*H)."""
    pam = subprocess.check_output(["convert", str(png_path), "-depth", "8", "pam:-"])
    end_hdr = pam.find(b"ENDHDR\n") + len(b"ENDHDR\n")
    raw = pam[end_hdr:]
    rgb = bytearray(W * H * 3)
    alpha = bytearray(W * H)
    for i in range(W * H):
        o4 = i * 4
        o3 = i * 3
        rgb[o3] = raw[o4]
        rgb[o3 + 1] = raw[o4 + 1]
        rgb[o3 + 2] = raw[o4 + 2]
        alpha[i] = raw[o4 + 3]
    return rgb, alpha


def save_rgba_png(rgb: bytearray, alpha: bytearray, out_path: Path) -> None:
    """Écrit deux buffers RGB + Alpha (896x1200) en PNG RGBA."""
    hdr = f"P7\nWIDTH {W}\nHEIGHT {H}\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n".encode()
    buf = bytearray(W * H * 4)
    for i in range(W * H):
        o4 = i * 4
        o3 = i * 3
        buf[o4] = rgb[o3]
        buf[o4 + 1] = rgb[o3 + 1]
        buf[o4 + 2] = rgb[o3 + 2]
        buf[o4 + 3] = alpha[i]
    proc = subprocess.run(
        ["convert", "pam:-", "-type", "TrueColorAlpha", str(out_path)],
        input=hdr + bytes(buf),
        check=True,
    )


def remove_bg_and_header(src_jpg: Path, out_png: Path, has_interior_arm_gaps: bool) -> None:
    """Supprime l'en-tête texte éventuel et rend le fond blanc transparent."""
    cmd = [
        "convert",
        str(src_jpg),
        "-alpha", "set",
        # Efface la zone d'en-tête texte en haut à gauche sans toucher les mèches (x >= 420 en haut, >= 340 en bas)
        "-fill", "white",
        "-draw", "rectangle 0,0 415,62",
        "-draw", "rectangle 0,62 390,74",
        "-draw", "rectangle 0,74 320,145",
        "-fuzz", "7%",
        "-fill", "none",
        "-draw", "color 0,0 floodfill",
        "-draw", "color 895,0 floodfill",
        "-draw", "color 0,1199 floodfill",
        "-draw", "color 895,1199 floodfill",
    ]
    if has_interior_arm_gaps:
        # Comble les intervalles blancs entre bras et hanches
        cmd.extend([
            "-draw", "color 315,650 floodfill",
            "-draw", "color 580,650 floodfill",
        ])
    cmd.extend(["-type", "TrueColorAlpha", str(out_png)])
    run(cmd)


def is_skin_pixel(r: int, g: int, b: int, a: int) -> bool:
    return a > 128 and r > 205 and g > 175 and b > 155 and r >= g >= b


def sample_skin_color(rgb: bytearray, alpha: bytearray, x0: int, y0: int, x1: int, y1: int) -> tuple[int, int, int]:
    rs, gs, bs = [], [], []
    for y in range(max(0, y0), min(H, y1)):
        for x in range(max(0, x0), min(W, x1)):
            i = y * W + x
            o = i * 3
            r, g, b = rgb[o], rgb[o + 1], rgb[o + 2]
            if is_skin_pixel(r, g, b, alpha[i]):
                rs.append(r)
                gs.append(g)
                bs.append(b)
    if not rs:
        return (248, 228, 214)
    return (sum(rs) // len(rs), sum(gs) // len(gs), sum(bs) // len(bs))


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


def is_hair_pixel(r: int, g: int, b: int, a: int) -> bool:
    if a < 128:
        return False
    lum = (r + g + b) / 3.0
    # Cheveux sombres bleu-noir neutres (évite d'attraper la peau ou les yeux cyan)
    return lum < 92 and abs(b - r) < 28


def is_cyan_iris(r: int, g: int, b: int, a: int) -> bool:
    return a > 128 and b > 175 and g > 145 and (b - r) > 45


def main() -> None:
    SPRITES_DIR.mkdir(parents=True, exist_ok=True)
    LAYERS_DIR.mkdir(parents=True, exist_ok=True)

    # 1. Détourage des 6 sprites complets
    sprite_paths: dict[str, Path] = {}
    for key, src in SOURCE_IMAGES.items():
        out_png = SPRITES_DIR / f"alita_{key}.png"
        has_gaps = key in ("normal", "joie", "fatigue")
        remove_bg_and_header(src, out_png, has_interior_arm_gaps=has_gaps)
        sprite_paths[key] = out_png

    # Charge normal, calme, joie, colere pour extraire les calques mobiles alignés
    n_rgb, n_alpha = load_ppm_rgba(sprite_paths["normal"])
    c_rgb, c_alpha = load_ppm_rgba(sprite_paths["calme"])
    j_rgb, j_alpha = load_ppm_rgba(sprite_paths["joie"])
    a_rgb, a_alpha = load_ppm_rgba(sprite_paths["colere"])

    skin_color = sample_skin_color(n_rgb, n_alpha, 430, 238, 466, 265)

    def make_empty() -> tuple[bytearray, bytearray]:
        return bytearray(W * H * 3), bytearray(W * H)

    def copy_pixel(src_rgb: bytearray, src_a: bytearray, dst_rgb: bytearray, dst_a: bytearray, x: int, y: int) -> None:
        i = y * W + x
        o = i * 3
        dst_rgb[o] = src_rgb[o]
        dst_rgb[o + 1] = src_rgb[o + 1]
        dst_rgb[o + 2] = src_rgb[o + 2]
        dst_a[i] = src_a[i]

    def set_pixel(dst_rgb: bytearray, dst_a: bytearray, x: int, y: int, col: tuple[int, int, int], a: int) -> None:
        i = y * W + x
        o = i * 3
        dst_rgb[o] = col[0]
        dst_rgb[o + 1] = col[1]
        dst_rgb[o + 2] = col[2]
        dst_a[i] = a

    # Polygones des bras + mains (suivent l'espace exact entre les bras et les hanches)
    left_arm_poly = [
        (0, 355), (336, 355), (344, 435), (332, 540), (315, 650), (275, 780), (242, 895), (235, 1100), (0, 1100)
    ]
    right_arm_poly = [
        (896, 355), (560, 355), (552, 435), (564, 540), (581, 650), (621, 780), (654, 895), (661, 1100), (896, 1100)
    ]

    # Zones faciales précises (coordonnées mesurées sur 896x1200)
    L_BROW = (380, 190, 432, 208)
    R_BROW = (464, 190, 516, 208)
    L_EYE = (380, 208, 432, 242)
    R_EYE = (464, 208, 516, 242)
    MOUTH = (424, 273, 472, 300)

    def in_box(x: int, y: int, box: tuple[int, int, int, int]) -> bool:
        return box[0] <= x <= box[2] and box[1] <= y <= box[3]

    # Prépare tous les buffers de calques
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

    # 2. Séparation Corps / Bras / Tête / Mèches / Visage depuis alita_normal
    for y in range(H):
        for x in range(W):
            i = y * W + x
            a = n_alpha[i]
            if a == 0:
                continue
            o = i * 3
            r, g, b = n_rgb[o], n_rgb[o + 1], n_rgb[o + 2]

            # A) Bras gauche / Bras droit / Torse (sous y=355, ou épaules)
            if y >= 355:
                if point_in_poly(x, y, left_arm_poly):
                    copy_pixel(n_rgb, n_alpha, larm_rgb, larm_a, x, y)
                elif point_in_poly(x, y, right_arm_poly):
                    copy_pixel(n_rgb, n_alpha, rarm_rgb, rarm_a, x, y)
                else:
                    copy_pixel(n_rgb, n_alpha, torse_rgb, torse_a, x, y)
                continue

            # B) Zone Cou / Col (y 312..354) : menton & cou sur tete, col d'armure sur torse
            if y >= 322:
                lum = (r + g + b) / 3.0
                if 412 <= x <= 484 and (lum < 110 or y < 332):
                    copy_pixel(n_rgb, n_alpha, tete_rgb, tete_a, x, y)
                else:
                    copy_pixel(n_rgb, n_alpha, torse_rgb, torse_a, x, y)
                continue

            # C) Zone Tête & Cheveux (y < 322)
            # Sourcils
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

            # Yeux & Pupilles
            if in_box(x, y, L_EYE):
                if is_cyan_iris(r, g, b, a):
                    copy_pixel(n_rgb, n_alpha, lpup_rgb, lpup_a, x, y)
                    set_pixel(leye_rgb, leye_a, x, y, (245, 250, 252), 255)
                    set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                elif not is_skin_pixel(r, g, b, a):
                    copy_pixel(n_rgb, n_alpha, leye_rgb, leye_a, x, y)
                    if x >= 390:
                        set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                else:
                    copy_pixel(n_rgb, n_alpha, tete_rgb, tete_a, x, y)
                continue

            if in_box(x, y, R_EYE):
                if is_cyan_iris(r, g, b, a):
                    copy_pixel(n_rgb, n_alpha, rpup_rgb, rpup_a, x, y)
                    set_pixel(reye_rgb, reye_a, x, y, (245, 250, 252), 255)
                    set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                elif not is_skin_pixel(r, g, b, a):
                    copy_pixel(n_rgb, n_alpha, reye_rgb, reye_a, x, y)
                    if x <= 506:
                        set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                else:
                    copy_pixel(n_rgb, n_alpha, tete_rgb, tete_a, x, y)
                continue

            # Bouche
            if in_box(x, y, MOUTH):
                if is_skin_pixel(r, g, b, a):
                    copy_pixel(n_rgb, n_alpha, tete_rgb, tete_a, x, y)
                else:
                    copy_pixel(n_rgb, n_alpha, bouche_rgb, bouche_a, x, y)
                    set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                continue

            # Mèches / Cheveux vs Tête
            # Les cheveux sombres (haut du crâne y < 188 ou sur les côtés x < 392 / x > 504 ou frange)
            if y < 188 or ((x < 394 or x > 502) and y < 312) or (y < 212 and is_hair_pixel(r, g, b, a)):
                if is_hair_pixel(r, g, b, a) or y < 180 or x < 388 or x > 508:
                    copy_pixel(n_rgb, n_alpha, meches_rgb, meches_a, x, y)
                    # Sous la frange centrale du front, place un fond peau continu à l'intérieur du visage
                    if 402 <= x <= 494 and 168 <= y <= 212:
                        set_pixel(tete_rgb, tete_a, x, y, skin_color, 255)
                    continue

            copy_pixel(n_rgb, n_alpha, tete_rgb, tete_a, x, y)

    # 3. Paupières fermées depuis alita_calme (sans écraser la peau environnante)
    for box, dst_rgb, dst_a in [
        (L_EYE, lpaup_rgb, lpaup_a),
        (R_EYE, rpaup_rgb, rpaup_a),
    ]:
        for y in range(box[1], box[3] + 1):
            for x in range(box[0], box[2] + 1):
                i = y * W + x
                if c_alpha[i] > 0:
                    copy_pixel(c_rgb, c_alpha, dst_rgb, dst_a, x, y)

    # 4. Bouches alternatives (Joie / Colère) depuis alita_joie et alita_colere
    for src_rgb, src_a, dst_rgb, dst_a in [
        (j_rgb, j_alpha, bouche_joie_rgb, bouche_joie_a),
        (a_rgb, a_alpha, bouche_colere_rgb, bouche_colere_a),
    ]:
        for y in range(MOUTH[1], MOUTH[3] + 1):
            for x in range(MOUTH[0], MOUTH[2] + 1):
                i = y * W + x
                o = i * 3
                if src_a[i] > 0 and not is_skin_pixel(src_rgb[o], src_rgb[o + 1], src_rgb[o + 2], src_a[i]):
                    copy_pixel(src_rgb, src_a, dst_rgb, dst_a, x, y)

    # 5. Sauvegarde des calques individuels en PNG
    ordered_layers: list[tuple[str, tuple[bytearray, bytearray] | Path]] = [
        ("pose_fatigue", sprite_paths["fatigue"]),
        ("pose_calme", sprite_paths["calme"]),
        ("pose_colere", sprite_paths["colere"]),
        ("pose_serieux", sprite_paths["serieux"]),
        ("pose_joie", sprite_paths["joie"]),
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
        ("meches_cheveux", (meches_rgb, meches_a)),
    ]

    layer_pngs: list[tuple[str, Path]] = []
    for name, payload in ordered_layers:
        out_png = LAYERS_DIR / f"{name}.png"
        if isinstance(payload, Path):
            run(["cp", str(payload), str(out_png)])
        else:
            save_rgba_png(payload[0], payload[1], out_png)
        layer_pngs.append((name, out_png))

    # 6. Assemblage du fichier PSD multi-calques (composite + calques nommés)
    psd_path = OUT_DIR / "alita_live2d_layers.psd"
    cmd = ["convert", str(sprite_paths["normal"])]
    for name, png in layer_pngs:
        cmd.extend(["(", str(png), "-set", "label", name, ")"])
    cmd.extend(["-type", "TrueColorAlpha", str(psd_path)])
    run(cmd)

    print(f"PSD généré : {psd_path} ({psd_path.stat().st_size // 1024} Ko, {len(layer_pngs)} calques)")


if __name__ == "__main__":
    main()
