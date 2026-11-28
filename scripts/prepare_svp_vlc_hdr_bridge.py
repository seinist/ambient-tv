#!/usr/bin/env python3
"""Stage SVP on top of the PR #510 experimental libplacebo display.
The normal Android SurfaceControl build is deliberately not modified.
"""
import pathlib
import shutil
import sys

source = pathlib.Path(sys.argv[1])
root = pathlib.Path(__file__).resolve().parents[1]
adapter = root / "native/ambientsvp/adapters"
display = source / "modules/video_output/libplacebo/display.c"
if not (source / ".ambient-ahb-experimental").is_file():
    raise SystemExit("SVP HDR bridge requires isolated experimental VLC checkout")
s = display.read_text()
backup = source / ".ambient-svp-hdr-display-original"
staged = source / ".ambient-svp-hdr-display-staged"
if len(sys.argv) > 2 and sys.argv[2] == "--restore":
    if "SVP metadata-aware renderer bridge" in s:
        if not backup.is_file() or not staged.is_file() or staged.read_text() != s:
            raise SystemExit("Cannot safely restore edited SVP HDR display")
        display.write_text(backup.read_text())
        backup.unlink()
        staged.unlink()
    sys.exit(0)
if '# include "dovi_fel_render.h"' not in s or 'ambient_fel_render_picture_ex' not in s:
    raise SystemExit("Stage PR510 experimental renderer before SVP HDR bridge")
for name in ("svp_vlc_hdr_pair.h", "svp_vlc_hdr_display.h", "svp_placebo_quality.h"):
    shutil.copyfile(adapter / name, display.parent / name)
for name in ("ambientsvp_quality.h", "ambientsvp_vulkan.h", "svp_hdr_curve.h"):
    shutil.copyfile(adapter.parent / "include" / name, display.parent / name)
marker = "SVP metadata-aware renderer bridge"
if marker in s:
    for expected in ("ambient_svp_hdr_render_pair", "sys->svp_hdr.metadata", "svp_generated"):
        if expected not in s:
            raise SystemExit("Incomplete staged SVP HDR bridge")
    sys.exit(0)

original = s

def replace(old, new):
    global s
    if s.count(old) != 1:
        raise SystemExit("SVP HDR renderer anchor changed: " + old[:100])
    s = s.replace(old, new)

replace('# include "dovi_fel_render.h"', '# include "dovi_fel_render.h"\n# include "svp_vlc_hdr_display.h" // ' + marker)
replace('    struct ambient_fel_render fel;', '    struct ambient_fel_render fel;\n    struct ambient_svp_hdr_display svp_hdr;')
replace('            ambient_fel_render_destroy(gpu, &sys->fel);',
        '            ambient_fel_render_destroy(gpu, &sys->fel);\n            ambient_svp_hdr_display_destroy(gpu, &sys->svp_hdr);')
replace('    struct ambient_ahb_output ahb_output = {0};\n    if (sys->ahb) {',
        '    struct ambient_ahb_output ahb_output = {0};\n'
        '    struct vlc_ancillary *svp_anc = picture_GetAncillary(pic, AMBIENT_SVP_PAIR_ID);\n'
        '    const struct ambient_svp_pair *svp_pair = svp_anc ? vlc_ancillary_GetData(svp_anc) : NULL;\n'
        '    bool svp_generated = false;\n'
        '    if (svp_pair && !sys->ahb) { vlc_placebo_ReleaseCurrent(sys->pl); return; }\n'
        '    if (sys->ahb && !svp_pair) {')
replace('    if (ahb_output.texture) {\n        // The opaque fourcc',
        '    if (svp_pair) {\n'
        '        if (!ambient_svp_hdr_render_pair(gpu, sys->pl->log, &sys->svp_hdr, sys->ahb,\n'
        '                svp_pair, sys->ahb_raw_p010, sys->ahb_dovi, &img)) {\n'
        '            failed = true; goto done;\n'
        '        }\n'
        '        svp_generated = true;\n'
        '    } else if (ahb_output.texture) {\n        // The opaque fourcc')
replace('    if (hdrplus) {\n        vlc_video_hdr_dynamic_metadata_t *hdm',
        '#if defined(__ANDROID__) && defined(AMBIENT_VLC_AHB_EXPERIMENT)\n'
        '    if (svp_generated) {\n'
        '        /* The generated image already carries its own global projection. */\n'
        '    } else\n#endif\n'
        '    if (hdrplus) {\n        vlc_video_hdr_dynamic_metadata_t *hdm')
replace('    if (!ahb_output.texture) {', '    if (!ahb_output.texture && !svp_generated) {')
replace('if (sys->hdr_target_mode || (ahb_output.texture && sys->ahb_hdrplus_spatial))',
        'if (sys->hdr_target_mode || ((ahb_output.texture || svp_generated) && sys->ahb_hdrplus_spatial))')
replace('(!ahb_output.texture || !pl_color_space_is_hdr(&img.color)',
        '((!ahb_output.texture && !svp_generated) || !pl_color_space_is_hdr(&img.color)')
replace('    if (ahb_output.texture && sys->ahb_hdrplus_spatial) {',
        '    if ((ahb_output.texture || svp_generated) && sys->ahb_hdrplus_spatial) {')
replace('!sys->params.lut && ahb_output.metadata_origin_aligned &&',
        '!sys->params.lut && (svp_generated ? sys->svp_hdr.metadata_aligned : ahb_output.metadata_origin_aligned) &&')
replace('        enum ambient_hdrplus_spatial_result spatial_result=ambient_hdrplus_spatial_apply(',
        '        const struct ambient_hdrplus *metadata = svp_generated ?\n'
        '            (sys->svp_hdr.has_metadata ? &sys->svp_hdr.metadata : NULL) :\n'
        '            (full ? vlc_ancillary_GetData(full) : NULL);\n'
        '        enum ambient_hdrplus_spatial_result spatial_result=ambient_hdrplus_spatial_apply(')
replace('            full?vlc_ancillary_GetData(full):NULL,eligible,sys->hdr_target_mode!=0);',
        '            metadata,eligible,sys->hdr_target_mode!=0);')
replace('    if (ahb_output.texture)\n        sys->ahb_frame_rendered = !failed;',
        '    if (ahb_output.texture || svp_pair)\n        sys->ahb_frame_rendered = !failed;\n    if (svp_pair && !failed) ambient_svp_hdr_mark_output(&sys->svp_hdr);')
backup.write_text(original)
staged.write_text(s)
display.write_text(s)
print("Staged SVP after PR510 metadata-aware AHB/FEL reconstruction")
