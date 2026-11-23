"""Natural Earth 1:50m GeoJSON -> the compact binary base map of the weather radar
(android/feature/home/src/main/assets/radar/ne50m.bin, read by RadarMap.kt).

Natural Earth is public domain (naturalearthdata.com). Input files, from
github.com/nvkelso/natural-earth-vector/geojson, renamed: land.geojson (ne_50m_land),
lakes.geojson (ne_50m_lakes), borders.geojson (ne_50m_admin_0_boundary_lines_land),
states.geojson (ne_50m_admin_1_states_provinces_lines).

Usage: python3 -I scripts/make-radar-map.py <dir with the 4 geojson files> <out.bin>

Format (big-endian):
  'VMAP' u8 version=1, u8 layerCount
  per layer: u8 kind (0 land, 1 lakes, 2 country borders, 3 state/province lines), u8 closed, u32 ringCount
    per ring: u16 minX, minY, maxX, maxY, u32 n, then the first point as u16 x, u16 y and
    n-1 zigzag-varint deltas dx, dy.
  x, y = Web Mercator normalised to 0..65535 (65536 = the world's width; 1 unit = 1 px at zoom 7
  with 512 px tiles).
"""
import json
import math
import struct
import sys


def merc(lon, lat):
    lat = max(-85.0511, min(85.0511, lat))
    x = (lon + 180.0) / 360.0
    s = math.sin(math.radians(lat))
    y = 0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)
    return min(65535, max(0, round(x * 65536))), min(65535, max(0, round(y * 65536)))


def rings_of(path):
    with open(path) as f:
        gj = json.load(f)
    for feat in gj['features']:
        g = feat['geometry']
        if g is None:
            continue
        t, c = g['type'], g['coordinates']
        if t == 'Polygon':
            yield from c
        elif t == 'MultiPolygon':
            for p in c:
                yield from p
        elif t == 'LineString':
            yield c
        elif t == 'MultiLineString':
            yield from c


def zigzag(v):
    return (v << 1) ^ (v >> 31)


def varint(v, out):
    while v >= 0x80:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)


def main(src, dst):
    layers = [(0, 1, 'land'), (1, 1, 'lakes'), (2, 0, 'borders'), (3, 0, 'states')]
    blob = bytearray(b'VMAP')
    blob += struct.pack('>BB', 1, len(layers))
    total = 0
    for kind, closed, name in layers:
        rings = []
        for r in rings_of(f'{src}/{name}.geojson'):
            pts = []
            for coord in r:
                p = merc(coord[0], coord[1])
                if not pts or pts[-1] != p:
                    pts.append(p)
            if len(pts) >= 2:
                rings.append(pts)
        blob += struct.pack('>BBI', kind, closed, len(rings))
        for pts in rings:
            xs = [p[0] for p in pts]
            ys = [p[1] for p in pts]
            blob += struct.pack('>HHHHI', min(xs), min(ys), max(xs), max(ys), len(pts))
            blob += struct.pack('>HH', *pts[0])
            for (ax, ay), (bx, by) in zip(pts, pts[1:]):
                varint(zigzag(bx - ax), blob)
                varint(zigzag(by - ay), blob)
            total += len(pts)
        print(name, len(rings), 'rings', sum(len(p) for p in rings), 'points')
    with open(dst, 'wb') as f:
        f.write(blob)
    print('points', total, 'bytes', len(blob))


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
