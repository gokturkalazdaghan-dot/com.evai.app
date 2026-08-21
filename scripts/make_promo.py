#!/usr/bin/env python3
"""
EVA AI — 30 saniyelik tanitim videosu ureticisi.

Sahneleri Pillow ile cizer, her sahneyi ayri bir klip olarak FFmpeg'e
aktarir, sonra FFmpeg'in `xfade` filtresiyle gecisleri birlestirip tek
bir MP4 uretir.

Cikti : <proje koku>/eva-ai-promo.mp4
Sure  : tam 30.0 saniye (gecis sureleri dusulerek hesaplanir)

Kullanim
--------
    py scripts/make_promo.py                 # 9:16 dikey (YouTube Shorts)
    py scripts/make_promo.py --format promo  # 16:9 yatay
    py scripts/make_promo.py --fps 60 --open

Gereksinimler
-------------
    py -m pip install pillow imageio-ffmpeg
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("Pillow gerekli:  py -m pip install pillow imageio-ffmpeg")


# ---------------------------------------------------------------------------
# Marka
# ---------------------------------------------------------------------------
# BRAND, android/app/src/main/res/values/ic_launcher_background.xml icindeki
# launcher rengiyle ayni tutulmalidir.
BRAND = (0x0F, 0x9D, 0x58)
MINT = (0x4A, 0xDE, 0x97)
VOID = (0x05, 0x0D, 0x0A)
DEEP = (0x08, 0x13, 0x0E)
INK = (0xED, 0xF5, 0xF0)
MUTED = (0x7E, 0x96, 0x89)
FAINT = (0x4A, 0x5E, 0x55)
AMBER = (0xE8, 0xA3, 0x3D)
RED = (0xE0, 0x5A, 0x4B)

FORMATS = {
    "shorts": (1080, 1920),   # YouTube Shorts / Reels / TikTok
    "promo": (1920, 1080),    # klasik yatay tanitim
}

# Supersampling: 2x cizip kucultmek Pillow'da kenar yumusatma saglar.
SS = 2

TRANSITION = 0.5   # saniye, xfade suresi
TARGET_TOTAL = 30.0


# ---------------------------------------------------------------------------
# Font cozumleme
# ---------------------------------------------------------------------------
FONT_CANDIDATES = {
    "bold": ["C:/Windows/Fonts/segoeuib.ttf", "C:/Windows/Fonts/arialbd.ttf",
             "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
             "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"],
    "semi": ["C:/Windows/Fonts/seguisb.ttf", "C:/Windows/Fonts/segoeui.ttf",
             "C:/Windows/Fonts/arial.ttf",
             "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"],
    "body": ["C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf",
             "/System/Library/Fonts/Supplemental/Arial.ttf",
             "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"],
    "mono": ["C:/Windows/Fonts/consola.ttf", "C:/Windows/Fonts/cour.ttf",
             "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"],
}


def _font_path(role: str) -> str:
    for p in FONT_CANDIDATES[role]:
        if Path(p).exists():
            return p
    sys.exit(
        f"'{role}' rolu icin Turkce destekli font bulunamadi.\n"
        f"Denenen yollar: {FONT_CANDIDATES[role]}\n"
        "FONT_CANDIDATES sozlugune sistemdeki bir .ttf yolu ekleyin."
    )


_FONT_CACHE: dict[tuple[str, int], ImageFont.FreeTypeFont] = {}


def font(role: str, size: int) -> ImageFont.FreeTypeFont:
    key = (role, size)
    if key not in _FONT_CACHE:
        _FONT_CACHE[key] = ImageFont.truetype(_font_path(role), size)
    return _FONT_CACHE[key]


# ---------------------------------------------------------------------------
# Yardimcilar
# ---------------------------------------------------------------------------
def clamp(v: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return max(lo, min(hi, v))


def ease_out(x: float) -> float:
    return 1 - (1 - clamp(x)) ** 3


def ease_in_out(x: float) -> float:
    x = clamp(x)
    return 4 * x ** 3 if x < 0.5 else 1 - (-2 * x + 2) ** 3 / 2


def seg(p: float, a: float, b: float) -> float:
    """p degerini [a,b] araliginda 0..1'e normalize eder."""
    return clamp((p - a) / (b - a)) if b > a else 0.0


def mix(c1, c2, t: float):
    t = clamp(t)
    return tuple(round(a + (b - a) * t) for a, b in zip(c1, c2))


def alpha(c, a: float):
    return (*c, round(clamp(a) * 255))


def text(draw: ImageDraw.ImageDraw, xy, s: str, f, fill, anchor="mm", spacing_px=0):
    """Opsiyonel harf araligiyla metin cizer (Pillow letter-spacing desteklemez)."""
    if not spacing_px:
        draw.text(xy, s, font=f, fill=fill, anchor=anchor)
        return
    widths = [draw.textlength(ch, font=f) for ch in s]
    total = sum(widths) + spacing_px * (len(s) - 1)
    x, y = xy
    if anchor[0] == "m":
        x -= total / 2
    elif anchor[0] == "r":
        x -= total
    for ch, w in zip(s, widths):
        draw.text((x, y), ch, font=f, fill=fill, anchor="l" + anchor[1])
        x += w + spacing_px


def rounded_label(draw, cx, cy, s, f, fg, bg, pad=(14, 8), radius=8, border=None):
    w = draw.textlength(s, font=f)
    h = f.size
    x0, y0 = cx - w / 2 - pad[0], cy - h / 2 - pad[1]
    x1, y1 = cx + w / 2 + pad[0], cy + h / 2 + pad[1]
    draw.rounded_rectangle([x0, y0, x1, y1], radius=radius, fill=bg,
                           outline=border, width=2 if border else 0)
    draw.text((cx, cy), s, font=f, fill=fg, anchor="mm")
    return (x1 - x0, y1 - y0)


_VIG_CACHE: dict[tuple[int, int], tuple[Image.Image, Image.Image]] = {}


def vignette_and_grade(img: Image.Image) -> Image.Image:
    """
    Kose karartmasi — sinematik his icin.

    Maske ve karartma katmani cozunurluk basina bir kez uretilip onbellege
    alinir; 900 karede yeniden hesaplamak render suresini katliyordu.
    """
    size = img.size
    cached = _VIG_CACHE.get(size)
    if cached is None:
        w, h = size
        mask = Image.new("L", size, 0)
        ImageDraw.Draw(mask).ellipse(
            [-w * 0.28, -h * 0.28, w * 1.28, h * 1.28], fill=255)
        mask = mask.point(lambda v: int(v * 0.82) + 46)
        cached = (mask, Image.new("RGB", size, VOID))
        _VIG_CACHE[size] = cached
    mask, dark = cached
    return Image.composite(img, dark, mask)


# ---------------------------------------------------------------------------
# Sahne cizimleri
#   Her fonksiyon:  (draw, W, H, p)  ->  p = sahne ilerlemesi 0..1
# ---------------------------------------------------------------------------
def scene_intro(d, W, H, p):
    """Acilis: simsek + wordmark + alt baslik."""
    cx, cy = W / 2, H / 2

    # Nabiz halkasi
    ring = seg(p, 0.05, 0.55)
    if ring > 0:
        r = 60 + 220 * ease_out(ring)
        a = (1 - ring) * 0.5
        d.ellipse([cx - r, cy - r - H * 0.06, cx + r, cy + r - H * 0.06],
                  outline=alpha(MINT, a), width=max(1, int(3 * SS)))

    # Simsek
    bolt_p = ease_out(seg(p, 0.0, 0.35))
    if bolt_p > 0:
        s = min(W, H) * 0.085 * bolt_p
        by = cy - H * 0.06
        pts = [(cx + 0.10 * s, by - s), (cx - 0.55 * s, by + 0.15 * s),
               (cx - 0.03 * s, by + 0.15 * s), (cx - 0.14 * s, by + s),
               (cx + 0.55 * s, by - 0.20 * s), (cx + 0.02 * s, by - 0.20 * s)]
        d.polygon(pts, fill=alpha(MINT, bolt_p))

    # Wordmark
    wp = ease_out(seg(p, 0.22, 0.7))
    if wp > 0:
        f = font("bold", int(min(W, H) * 0.135))
        y = cy + H * 0.07 + (1 - wp) * H * 0.035
        text(d, (cx, y), "Eva AI", f, alpha(INK, wp), spacing_px=-int(2 * SS))

    sp = ease_out(seg(p, 0.45, 0.9))
    if sp > 0:
        f = font("body", int(min(W, H) * 0.032))
        text(d, (cx, cy + H * 0.155), "Akıllı şarj asistanı", f, alpha(MUTED, sp))


def scene_map(d, W, H, p):
    """Harita + yesil/kirmizi anlik doluluk pinleri."""
    # Yol agi
    grid_a = ease_out(seg(p, 0.0, 0.3)) * 0.5
    step = H / 14
    for i in range(15):
        y = i * step
        d.line([(0, y), (W, y)], fill=alpha(FAINT, grid_a * 0.5), width=max(1, SS))
    for i in range(int(W / step) + 1):
        x = i * step
        d.line([(x, 0), (x, H)], fill=alpha(FAINT, grid_a * 0.5), width=max(1, SS))

    # Ana arter
    route = [(W * 0.10, H * 0.86), (W * 0.34, H * 0.70), (W * 0.44, H * 0.50),
             (W * 0.66, H * 0.38), (W * 0.88, H * 0.20)]
    rp = ease_in_out(seg(p, 0.05, 0.55))
    n = max(2, int(len(route) * rp) + 1)
    if rp > 0:
        d.line(route[:n], fill=alpha(BRAND, 0.85), width=int(7 * SS), joint="curve")

    # Pinler — yesil = musait, kirmizi = dolu
    pins = [
        (0.22, 0.78, True,  "7,40 TL", "4/6"),
        (0.40, 0.60, False, "9,10 TL", "DOLU"),
        (0.55, 0.44, True,  "6,85 TL", "2/4"),
        (0.76, 0.29, True,  "7,95 TL", "5/8"),
        (0.33, 0.34, False, "8,60 TL", "DOLU"),
    ]
    fl = font("mono", int(min(W, H) * 0.026))
    for i, (fx, fy, free, price, occ) in enumerate(pins):
        q = ease_out(seg(p, 0.18 + i * 0.09, 0.42 + i * 0.09))
        if q <= 0:
            continue
        x, y = W * fx, H * fy
        col = MINT if free else RED
        r = min(W, H) * 0.014 * q
        # Isik halesi
        d.ellipse([x - r * 3, y - r * 3, x + r * 3, y + r * 3], fill=alpha(col, 0.13 * q))
        d.ellipse([x - r, y - r, x + r, y + r], fill=alpha(col, q))
        if q > 0.5:
            rounded_label(d, x, y - r * 4.4, f"{price} - {occ}", fl,
                          alpha(INK, q), alpha(DEEP, 0.92 * q),
                          pad=(int(10 * SS), int(6 * SS)), radius=int(6 * SS),
                          border=alpha(col, 0.5 * q))

    # Alt yazi
    cp = ease_out(seg(p, 0.42, 0.72))
    if cp > 0:
        text(d, (W / 2, H * 0.90), "ANLIK DOLULUK", font("mono", int(min(W, H) * 0.024)),
             alpha(MINT, cp), spacing_px=int(4 * SS))
        text(d, (W / 2, H * 0.945), "Boş istasyonu haritada anında gör",
             font("semi", int(min(W, H) * 0.042)), alpha(INK, cp))


def scene_price(d, W, H, p):
    """Fiyat trend oklari."""
    cx = W / 2
    top = H * 0.30

    text(d, (cx, H * 0.16), "FİYAT TRENDİ", font("mono", int(min(W, H) * 0.024)),
         alpha(MINT, ease_out(seg(p, 0, 0.2))), spacing_px=int(4 * SS))

    rows = [
        ("Beyoğlu",   "8,90", "9,60", True),
        ("Kadıköy",   "7,20", "6,85", False),
        ("Ataşehir",  "8,10", "7,40", False),
    ]
    fh = font("semi", int(min(W, H) * 0.040))
    fm = font("mono", int(min(W, H) * 0.038))
    gap = H * 0.105

    for i, (name, old, new, up) in enumerate(rows):
        q = ease_out(seg(p, 0.12 + i * 0.13, 0.45 + i * 0.13))
        if q <= 0:
            continue
        y = top + i * gap + (1 - q) * H * 0.02
        col = RED if up else MINT
        d.text((W * 0.12, y), name, font=fh, fill=alpha(INK, q), anchor="lm")
        d.text((W * 0.60, y), f"{old} TL", font=fm, fill=alpha(FAINT, q * 0.8), anchor="rm")

        # Ok
        ax = W * 0.665
        s = min(W, H) * 0.022
        if up:
            pts = [(ax, y - s), (ax + s * 0.9, y + s * 0.6), (ax - s * 0.9, y + s * 0.6)]
        else:
            pts = [(ax, y + s), (ax + s * 0.9, y - s * 0.6), (ax - s * 0.9, y - s * 0.6)]
        d.polygon(pts, fill=alpha(col, q))

        d.text((W * 0.88, y), f"{new} TL", font=fm, fill=alpha(col, q), anchor="rm")

    sp = ease_out(seg(p, 0.62, 0.9))
    if sp > 0:
        text(d, (cx, H * 0.76), "Düşen tarifeyi kaçırma", font("semi", int(min(W, H) * 0.044)),
             alpha(INK, sp))
        text(d, (cx, H * 0.825), "Eva fiyatları dakikada bir tarar",
             font("body", int(min(W, H) * 0.030)), alpha(MUTED, sp))


def scene_assistant(d, W, H, p):
    """AI asistan diyalogu."""
    text(d, (W / 2, H * 0.16), "EVA ASİSTAN", font("mono", int(min(W, H) * 0.024)),
         alpha(MINT, ease_out(seg(p, 0, 0.18))), spacing_px=int(4 * SS))

    fq = font("body", int(min(W, H) * 0.038))
    fa = font("semi", int(min(W, H) * 0.040))

    # Kullanici balonu (sag)
    q1 = ease_out(seg(p, 0.05, 0.3))
    if q1 > 0:
        s = "En ucuz hızlı şarj nerede?"
        w = d.textlength(s, font=fq)
        x1, y = W * 0.90, H * 0.34 + (1 - q1) * H * 0.02
        d.rounded_rectangle([x1 - w - W * 0.09, y - H * 0.035, x1, y + H * 0.035],
                            radius=int(min(W, H) * 0.028), fill=alpha((0x18, 0x2A, 0x22), q1))
        # Balonun gercek merkezi: sag kenar - yari metin genisligi - yari dolgu.
        # w/2 unutuldugunda metin sag kenardan tasiyordu.
        d.text((x1 - w / 2 - W * 0.045, y), s, font=fq, fill=alpha(INK, q1), anchor="mm")

    # Asistan balonu (sol) — yazim efekti
    q2 = seg(p, 0.32, 0.72)
    if q2 > 0:
        full = "Ataşehir — 6,85 TL/kWh, 2 sokak ileride, 2 yuva boş."
        s = full[:max(1, int(len(full) * ease_out(q2)))]
        x0, y = W * 0.10, H * 0.52
        lines, cur = [], ""
        for word in s.split(" "):
            t = (cur + " " + word).strip()
            if d.textlength(t, font=fa) > W * 0.72 and cur:
                lines.append(cur)
                cur = word
            else:
                cur = t
        lines.append(cur)
        bh = len(lines) * fa.size * 1.45 + H * 0.045
        d.rounded_rectangle([x0, y - H * 0.03, x0 + W * 0.80, y - H * 0.03 + bh],
                            radius=int(min(W, H) * 0.028), fill=alpha((0x0F, 0x24, 0x1A), 1.0),
                            outline=alpha(BRAND, 0.55), width=int(2 * SS))
        for i, ln in enumerate(lines):
            d.text((x0 + W * 0.045, y + i * fa.size * 1.45 + H * 0.008), ln,
                   font=fa, fill=INK, anchor="lm")

    q3 = ease_out(seg(p, 0.75, 0.95))
    if q3 > 0:
        text(d, (W / 2, H * 0.82), "Sesle sor, Eva yönlendirsin",
             font("body", int(min(W, H) * 0.032)), alpha(MUTED, q3))


def scene_savings(d, W, H, p):
    """Tasarruf rakami."""
    cx = W / 2
    text(d, (cx, H * 0.30), "AYLIK TASARRUF", font("mono", int(min(W, H) * 0.024)),
         alpha(MINT, ease_out(seg(p, 0, 0.2))), spacing_px=int(4 * SS))

    q = ease_out(seg(p, 0.08, 0.62))
    val = round(165 * q)
    f = font("bold", int(min(W, H) * 0.185))
    text(d, (cx, H * 0.46), f"{val} TL", f, alpha(MINT, min(1, q * 3)), spacing_px=-int(2 * SS))

    q2 = ease_out(seg(p, 0.55, 0.85))
    if q2 > 0:
        text(d, (cx, H * 0.60), "612 TL yerine 447 TL",
             font("body", int(min(W, H) * 0.036)), alpha(MUTED, q2))


def scene_outro(d, W, H, p):
    """Kapanis: wordmark + konumlandirma + konnektorler."""
    cx = W / 2
    q = ease_out(seg(p, 0.0, 0.4))
    text(d, (cx, H * 0.42), "Eva AI", font("bold", int(min(W, H) * 0.125)),
         alpha(INK, q), spacing_px=-int(2 * SS))

    q2 = ease_out(seg(p, 0.2, 0.6))
    if q2 > 0:
        text(d, (cx, H * 0.53), "Gizliliğe öncelik veren EV şarj yardımcınız",
             font("body", int(min(W, H) * 0.033)), alpha(MUTED, q2))

    q3 = ease_out(seg(p, 0.4, 0.8))
    if q3 > 0:
        fm = font("mono", int(min(W, H) * 0.023))
        conns = "CCS   CHAdeMO   TESLA NACS   TYPE 2"
        text(d, (cx, H * 0.62), conns, fm, alpha(FAINT, q3), spacing_px=int(2 * SS))


# ---------------------------------------------------------------------------
# Storyboard
# ---------------------------------------------------------------------------
@dataclass
class Scene:
    name: str
    weight: float                      # goreli sure
    draw: Callable
    duration: float = field(default=0.0)


STORYBOARD = [
    Scene("acilis",   4.0, scene_intro),
    Scene("harita",   7.5, scene_map),
    Scene("fiyat",    6.5, scene_price),
    Scene("asistan",  6.5, scene_assistant),
    Scene("tasarruf", 4.0, scene_savings),
    Scene("kapanis",  4.0, scene_outro),
]


def solve_durations(scenes, target: float, trans: float) -> None:
    """
    xfade her gecis icin `trans` saniye yutar. Nihai sure:
        toplam(sure) - (n-1) * trans
    Bunu `target`e esitleyecek sekilde agirliklari olcekler.
    """
    n = len(scenes)
    needed = target + (n - 1) * trans
    w = sum(s.weight for s in scenes)
    for s in scenes:
        s.duration = needed * s.weight / w


# ---------------------------------------------------------------------------
# Render
# ---------------------------------------------------------------------------
def find_ffmpeg() -> str:
    if os.environ.get("FFMPEG_BINARY"):
        return os.environ["FFMPEG_BINARY"]
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        pass
    from shutil import which
    exe = which("ffmpeg")
    if exe:
        return exe
    sys.exit("FFmpeg bulunamadi:  py -m pip install imageio-ffmpeg")


def render_scene(scene: Scene, W: int, H: int, fps: int, ffmpeg: str, out: Path) -> None:
    """Sahneyi kare kare cizip ham RGB olarak FFmpeg'e boru ile aktarir."""
    frames = max(1, round(scene.duration * fps))
    proc = subprocess.Popen(
        [ffmpeg, "-y", "-loglevel", "error",
         "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(fps), "-i", "-",
         "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
         str(out)],
        stdin=subprocess.PIPE,
    )
    try:
        for i in range(frames):
            p = i / max(1, frames - 1)
            big = Image.new("RGB", (W * SS, H * SS), DEEP)
            layer = Image.new("RGBA", big.size, (0, 0, 0, 0))
            scene.draw(ImageDraw.Draw(layer), W * SS, H * SS, p)
            big = Image.alpha_composite(big.convert("RGBA"), layer).convert("RGB")
            img = big.resize((W, H), Image.LANCZOS)
            proc.stdin.write(vignette_and_grade(img).tobytes())
    finally:
        proc.stdin.close()
        if proc.wait() != 0:
            sys.exit(f"FFmpeg sahne kodlamasi basarisiz: {scene.name}")


def assemble(clips: list[Path], ffmpeg: str, out: Path, trans: float, durations: list[float]) -> None:
    """Klipleri xfade zinciriyle birlestirir."""
    cmd = [ffmpeg, "-y", "-loglevel", "error"]
    for c in clips:
        cmd += ["-i", str(c)]

    if len(clips) == 1:
        cmd += ["-c", "copy", str(out)]
        subprocess.run(cmd, check=True)
        return

    parts, prev, running = [], "[0:v]", durations[0]
    for i in range(1, len(clips)):
        offset = running - trans
        label = f"[v{i}]"
        parts.append(f"{prev}[{i}:v]xfade=transition=fade:duration={trans}:offset={offset:.4f}{label}")
        prev = label
        running += durations[i] - trans

    cmd += ["-filter_complex", ";".join(parts), "-map", prev,
            "-c:v", "libx264", "-preset", "slow", "-crf", "18",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out)]
    subprocess.run(cmd, check=True)


def project_root() -> Path:
    return Path(__file__).resolve().parent.parent


def main() -> None:
    ap = argparse.ArgumentParser(description="EVA AI tanitim videosu ureticisi")
    ap.add_argument("--format", choices=FORMATS, default="shorts",
                    help="shorts = 1080x1920 dikey, promo = 1920x1080 yatay")
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--out", default=None, help="cikti yolu (varsayilan: proje koku)")
    ap.add_argument("--open", action="store_true", help="bitince oynatici ile ac")
    args = ap.parse_args()

    W, H = FORMATS[args.format]
    ffmpeg = find_ffmpeg()
    out = Path(args.out) if args.out else project_root() / "eva-ai-promo.mp4"
    out.parent.mkdir(parents=True, exist_ok=True)

    solve_durations(STORYBOARD, TARGET_TOTAL, TRANSITION)

    print(f"Format : {args.format}  {W}x{H} @ {args.fps}fps")
    print(f"FFmpeg : {ffmpeg}")
    print(f"Cikti  : {out}\n")

    with tempfile.TemporaryDirectory(prefix="eva-promo-") as tmp:
        clips = []
        for i, sc in enumerate(STORYBOARD):
            clip = Path(tmp) / f"{i:02d}-{sc.name}.mp4"
            print(f"  [{i + 1}/{len(STORYBOARD)}] {sc.name:9s} {sc.duration:5.2f}s", flush=True)
            render_scene(sc, W, H, args.fps, ffmpeg, clip)
            clips.append(clip)

        print("\n  gecisler birlestiriliyor...")
        assemble(clips, ffmpeg, out, TRANSITION, [s.duration for s in STORYBOARD])

    mb = out.stat().st_size / 1048576
    print(f"\nTamam -> {out}  ({mb:.1f} MB)")

    if args.open:
        if sys.platform == "win32":
            os.startfile(out)  # noqa: S606
        else:
            subprocess.run(["open" if sys.platform == "darwin" else "xdg-open", str(out)])


if __name__ == "__main__":
    main()
