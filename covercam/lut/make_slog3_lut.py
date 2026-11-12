#!/usr/bin/env python3
"""Writes two .cube files beside this script: CoverCam's log video (Protune Flat) re-encoded as S-Log3,
so a LUT made for S-Log3 can follow it.

  protune_to_slog3.cube                      the curve only; colours stay in the clip's own gamut
  protune_rec2020_to_slog3_sgamut3cine.cube  the curve and Rec.2020 to S-Gamut3.Cine, for LUTs that name that gamut

In:  the clip read as video (limited) range, the way the file is flagged.
Out: S-Log3 as Sony's formula gives it, code value / 1023: black 0.093, middle grey 0.411.
     The clipped sensor lands at 0.596, so an S-Log3 look shows it as light grey, not white.

shortcut: Rec.2020 is what the stream is tagged, not what was measured; shoot a colour chart before trusting the second file.
"""
import math
import os

# every app takes this size; straight lines between 33 points miss the curve by under one 10-bit code
# (the gamut file: up to 13 codes, only on greens so saturated that red falls below zero in S-Gamut3.Cine)
SIZE = 33

# Rec.2020 to S-Gamut3.Cine, both D65, worked out from their primaries
REC2020_TO_SGAMUT3CINE = (
    (1.038144, -0.095453, 0.057309),
    (0.047949, 0.793862, 0.158189),
    (0.030132, 0.040886, 0.928982),
)


def protune_to_linear(y):
    return (113 ** y - 1) / 112


def slog3(x):
    if x >= 0.01125:
        code = 420 + math.log10((x + 0.01) / 0.19) * 261.5
    else:
        code = x * (171.2102946929 - 95) / 0.01125 + 95
    return max(0.0, code / 1023)  # a colour outside S-Gamut3.Cine goes below zero


def convert(rgb, matrix=None):
    lin = [protune_to_linear(v) for v in rgb]
    if matrix:
        lin = [sum(m * v for m, v in zip(row, lin)) for row in matrix]
    return [slog3(v) for v in lin]


def write(name, matrix=None):
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), name), "w") as f:
        f.write('TITLE "%s"\nLUT_3D_SIZE %d\n' % (name[:-5], SIZE))
        # .cube order: red changes fastest
        for b in range(SIZE):
            for g in range(SIZE):
                for r in range(SIZE):
                    f.write("%.6f %.6f %.6f\n" % tuple(convert([v / (SIZE - 1) for v in (r, g, b)], matrix)))


if __name__ == "__main__":
    grey = math.log(112 * 0.18 + 1) / math.log(113)
    assert abs(convert([0, 0, 0])[0] - 95 / 1023) < 1e-9, "black"
    assert abs(convert([grey] * 3)[0] - 420 / 1023) < 1e-9, "middle grey"
    assert abs(convert([1, 1, 1])[0] - 0.5960) < 1e-4, "clipped sensor"
    for row in REC2020_TO_SGAMUT3CINE:
        assert abs(sum(row) - 1) < 1e-5, "white stays white"
    assert all(abs(a - b) < 1e-5 for a, b in zip(convert([grey] * 3, REC2020_TO_SGAMUT3CINE), convert([grey] * 3)))
    write("protune_to_slog3.cube")
    write("protune_rec2020_to_slog3_sgamut3cine.cube", REC2020_TO_SGAMUT3CINE)
