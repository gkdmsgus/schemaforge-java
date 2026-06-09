#!/usr/bin/env python3
"""
Generates RS-274X Gerber files from a .kicad_pcb file.
Usage: python generate_gerbers.py <pcb_file> <output_dir>
"""
import sys
import os
import re
import math
import json
from datetime import datetime

def mm(v):
    """Convert mm float to Gerber coordinate integer (FSLAX46Y46: 6 decimal places)"""
    return int(round(float(v) * 1_000_000))

def coord(x, y):
    return f"X{mm(x)}Y{mm(y)}"

# ── S-expression parser ────────────────────────────────────────────────────────

def tokenize(s):
    tokens = []
    i = 0
    while i < len(s):
        c = s[i]
        if c in ' \t\n\r':
            i += 1
        elif c == '(':
            tokens.append('('); i += 1
        elif c == ')':
            tokens.append(')'); i += 1
        elif c == '"':
            j = i + 1
            while j < len(s) and s[j] != '"':
                j += 2 if s[j] == '\\' else j + 1 - j
            tokens.append(s[i:j+1]); i = j + 1
        else:
            j = i
            while j < len(s) and s[j] not in ' \t\n\r()':
                j += 1
            tokens.append(s[i:j]); i = j
    return tokens

def parse(tokens, pos=0):
    if pos >= len(tokens):
        return None, pos
    tok = tokens[pos]
    if tok == '(':
        lst = []
        pos += 1
        while pos < len(tokens) and tokens[pos] != ')':
            elem, pos = parse(tokens, pos)
            if elem is not None:
                lst.append(elem)
        return lst, pos + 1
    else:
        val = tok
        if val.startswith('"') and val.endswith('"'):
            val = val[1:-1]
        try:
            return float(val), pos + 1
        except ValueError:
            return val, pos + 1

def find_all(node, key):
    results = []
    if isinstance(node, list):
        if node and node[0] == key:
            results.append(node)
        for child in node:
            results.extend(find_all(child, key))
    return results

def find_first(node, key):
    r = find_all(node, key)
    return r[0] if r else None

def get_val(node, key, default=None):
    f = find_first(node, key)
    if f and len(f) > 1:
        return f[1]
    return default

# ── Gerber helpers ─────────────────────────────────────────────────────────────

def gerber_header(file_function, file_polarity='Positive'):
    now = datetime.utcnow().strftime('%Y%m%dT%H%M%SZ')
    return '\n'.join([
        '%TF.GenerationSoftware,SchemaForge,1.0,{}*%'.format(now),
        '%TF.CreationDate,{}*%'.format(now),
        '%TF.FileFunction,{}*%'.format(file_function),
        '%TF.FilePolarity,{}*%'.format(file_polarity),
        '%FSLAX46Y46*%',
        '%MOMM*%',
        '%LPD*%',
        'G01*',
    ])

def gerber_footer():
    return 'M02*'

def define_apertures(widths):
    """Define circle apertures for given widths (mm), returns aperture dict and definition lines"""
    apertures = {}
    lines = []
    d = 10
    for w in sorted(set(widths)):
        key = round(w, 4)
        if key not in apertures:
            apertures[key] = d
            lines.append(f'%ADD{d}C,{w:.6f}*%')
            d += 1
    return apertures, lines

# ── Extract PCB data ───────────────────────────────────────────────────────────

def extract_segments(pcb):
    """Extract copper segments (traces) from F.Cu"""
    segs = []
    for seg in find_all(pcb, 'segment'):
        layer = get_val(seg, 'layer')
        if layer not in ('F.Cu', '"F.Cu"'):
            continue
        start = find_first(seg, 'start')
        end = find_first(seg, 'end')
        width = get_val(seg, 'width') or 0.25
        if start and end and len(start) >= 3 and len(end) >= 3:
            segs.append({'x1': start[1], 'y1': start[2],
                         'x2': end[1],   'y2': end[2], 'w': float(width)})
    return segs

def extract_pads(pcb):
    """Extract pads from footprints on F.Cu"""
    pads = []
    for fp in find_all(pcb, 'footprint'):
        at = find_first(fp, 'at')
        fp_x = float(at[1]) if at and len(at) > 1 else 0.0
        fp_y = float(at[2]) if at and len(at) > 2 else 0.0
        fp_rot = float(at[3]) if at and len(at) > 3 else 0.0
        for pad in find_all(fp, 'pad'):
            pad_at = find_first(pad, 'at')
            if not pad_at or len(pad_at) < 3:
                continue
            px, py = float(pad_at[1]), float(pad_at[2])
            # Rotate pad position by footprint rotation
            if fp_rot:
                rad = math.radians(fp_rot)
                cx, cy = px * math.cos(rad) - py * math.sin(rad), px * math.sin(rad) + py * math.cos(rad)
                px, py = cx, cy
            size = find_first(pad, 'size')
            w = float(size[1]) if size and len(size) > 1 else 1.6
            h = float(size[2]) if size and len(size) > 2 else 0.8
            drill = find_first(pad, 'drill')
            drill_d = float(drill[1]) if drill and len(drill) > 1 else 0.0
            layers = find_first(pad, 'layers')
            is_front = any('F.Cu' in str(l) for l in (layers or []))
            pads.append({'x': fp_x + px, 'y': fp_y + py, 'w': w, 'h': h,
                         'drill': drill_d, 'front': is_front})
    return pads

def extract_edge(pcb):
    """Extract board outline from Edge.Cuts"""
    lines = []
    for seg in find_all(pcb, 'segment'):
        layer = get_val(seg, 'layer')
        if layer not in ('Edge.Cuts', '"Edge.Cuts"'):
            continue
        start = find_first(seg, 'start')
        end = find_first(seg, 'end')
        if start and end and len(start) >= 3 and len(end) >= 3:
            lines.append({'x1': start[1], 'y1': start[2], 'x2': end[1], 'y2': end[2]})
    # Also check gr_rect for board outline
    for rect in find_all(pcb, 'gr_rect'):
        layer = get_val(rect, 'layer')
        if layer not in ('Edge.Cuts', '"Edge.Cuts"'):
            continue
        start = find_first(rect, 'start')
        end = find_first(rect, 'end')
        if start and end and len(start) >= 3 and len(end) >= 3:
            x1, y1, x2, y2 = start[1], start[2], end[1], end[2]
            lines += [
                {'x1': x1, 'y1': y1, 'x2': x2, 'y2': y1},
                {'x1': x2, 'y1': y1, 'x2': x2, 'y2': y2},
                {'x1': x2, 'y1': y2, 'x2': x1, 'y2': y2},
                {'x1': x1, 'y1': y2, 'x2': x1, 'y2': y1},
            ]
    return lines

def infer_board_bounds(pads, margin=5.0):
    """If no edge cuts found, calculate board bounds from pads"""
    if not pads:
        return 0, 0, 50, 50
    xs = [p['x'] for p in pads]
    ys = [p['y'] for p in pads]
    return min(xs) - margin, min(ys) - margin, max(xs) + margin, max(ys) + margin

# ── Generate individual Gerber files ──────────────────────────────────────────

def write_fcu(path, segs, pads):
    widths = [s['w'] for s in segs] + [max(p['w'], p['h']) for p in pads if p['front']]
    if not widths:
        widths = [0.25]
    apertures, ap_defs = define_apertures(widths)

    lines = [gerber_header('Copper,L1,Top'), ''] + ap_defs + ['']

    # Draw traces
    for s in segs:
        d = apertures[round(s['w'], 4)]
        lines += [
            f"D{d}*",
            f"{coord(s['x1'], s['y1'])}D02*",
            f"{coord(s['x2'], s['y2'])}D01*",
        ]

    # Flash pads
    for p in pads:
        if not p['front']:
            continue
        d = apertures[round(max(p['w'], p['h']), 4)]
        lines += [f"D{d}*", f"{coord(p['x'], p['y'])}D03*"]

    lines.append(gerber_footer())
    Path(path).write_text('\n'.join(lines))

def write_fmask(path, pads):
    sizes = [max(p['w'], p['h']) + 0.1 for p in pads if p['front']]
    if not sizes:
        sizes = [1.7]
    apertures, ap_defs = define_apertures(sizes)

    lines = [gerber_header('Soldermask,Top'), ''] + ap_defs + ['']
    for p in pads:
        if not p['front']:
            continue
        sz = round(max(p['w'], p['h']) + 0.1, 4)
        d = apertures[sz]
        lines += [f"D{d}*", f"{coord(p['x'], p['y'])}D03*"]
    lines.append(gerber_footer())
    Path(path).write_text('\n'.join(lines))

def write_edge_cuts(path, edge_lines, pads):
    lines = [gerber_header('Profile,NP'), '', '%ADD10C,0.050000*%', '']

    if edge_lines:
        lines.append('D10*')
        for l in edge_lines:
            lines += [
                f"{coord(l['x1'], l['y1'])}D02*",
                f"{coord(l['x2'], l['y2'])}D01*",
            ]
    else:
        # Auto-generate board outline from pads
        x1, y1, x2, y2 = infer_board_bounds(pads)
        lines += [
            'D10*',
            f"{coord(x1, y1)}D02*", f"{coord(x2, y1)}D01*",
            f"{coord(x2, y1)}D02*", f"{coord(x2, y2)}D01*",
            f"{coord(x2, y2)}D02*", f"{coord(x1, y2)}D01*",
            f"{coord(x1, y2)}D02*", f"{coord(x1, y1)}D01*",
        ]

    lines.append(gerber_footer())
    Path(path).write_text('\n'.join(lines))

def write_drill(path, pads):
    holes = [(p['x'], p['y'], p['drill']) for p in pads if p['drill'] > 0]

    lines = [
        'M48',
        '; DRILL file generated by SchemaForge',
        '; FORMAT={-:-/ absolute / metric / decimal}',
        'METRIC,LZ',
    ]
    sizes = sorted(set(d for _, _, d in holes))
    tool = {}
    for i, s in enumerate(sizes, 1):
        lines.append(f'T{i:02d}C{s:.3f}')
        tool[s] = i
    lines.append('%')
    lines.append('G90')
    lines.append('G05')

    for x, y, d in holes:
        t = tool[d]
        lines += [f'T{t:02d}', f'X{x:.4f}Y{y:.4f}']

    lines += ['T00', 'M30']
    Path(path).write_text('\n'.join(lines))

# ── Main ──────────────────────────────────────────────────────────────────────

def main(pcb_file, output_dir):
    os.makedirs(output_dir, exist_ok=True)
    content = Path(pcb_file).read_text(encoding='utf-8', errors='replace')

    tokens = tokenize(content)
    pcb, _ = parse(tokens)

    if not isinstance(pcb, list):
        print("ERROR: Could not parse KiCad PCB file", file=sys.stderr)
        sys.exit(1)

    segs  = extract_segments(pcb)
    pads  = extract_pads(pcb)
    edge  = extract_edge(pcb)

    base = os.path.splitext(os.path.basename(pcb_file))[0]
    out = output_dir

    files = [
        f'{base}-F.Cu.gbr',
        f'{base}-F.Mask.gbr',
        f'{base}-Edge.Cuts.gbr',
        f'{base}.drl',
    ]

    write_fcu(      os.path.join(out, files[0]), segs, pads)
    write_fmask(    os.path.join(out, files[1]), pads)
    write_edge_cuts(os.path.join(out, files[2]), edge, pads)
    write_drill(    os.path.join(out, files[3]), pads)

    print(json.dumps({'files': files, 'dir': output_dir}))

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print(f'Usage: {sys.argv[0]} <pcb_file> <output_dir>', file=sys.stderr)
        sys.exit(1)
    from pathlib import Path
    main(sys.argv[1], sys.argv[2])
