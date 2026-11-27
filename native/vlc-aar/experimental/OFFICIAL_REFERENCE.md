# Official HDR reference intake (no bundled restricted assets)

Sources verified on 2026-11-27:

- DVB / EBU, **DVB 2160p50 HDR with 2094-40 DM** (Samsung bitstream, SES signaling), https://dvb.org/specifications/verification-validation/hdr-test-content/ — 171 MB. EBU CC BY 4.0: original photography Frans de Jong, EBU, June 2019; HLG-to-PQ conversion Andrew Cotton, BBC; published by DVB. Download through https://fetch.dvb.org/?filename=DVB_2160p50_HDR_with_2094-40_DM.ts (provider form). This is an official verification *input*, not a golden rendered frame.
- HDR10+ Technologies test specifications and certification material are provided through the adopter program: https://hdr10plus.org/.
- Dolby Vision Browser Test Kit: https://ott.dolby.com/browser_test_kit/help_files/topics/r_test_signals_all.html — profiles 5, 8.1 and 8.4. This is not a profile 7 FEL reference. Do not mark the FEL check complete using this kit. Dolby's professional verification toolkit is described at https://professionalsupport.dolby.com/s/article/Dolby-Vision-QC. Obtain rights-cleared profile 7 FEL inputs and matching reference decoder output from Dolby/the authorized test provider. No proprietary kit is committed or redistributed.

Build after the pinned libplacebo and patched FFmpeg have been built as in
the experimental CI (set PL_SOURCE, PL_BUILD and FFMPEG_SOURCE to those
directories):

```sh
cc -std=c11 -DAMBIENT_OFFICIAL_FEL \
  -I "$PL_SOURCE/src/include" -I "$PL_BUILD/src/include" \
  native/vlc-aar/experimental/test_official_hdr_gpu.c \
  "$FFMPEG_SOURCE/libavcodec/libavcodec.a" "$FFMPEG_SOURCE/libavutil/libavutil.a" \
  -L "$PL_BUILD/src" -Wl,-rpath,"$PL_BUILD/src" -lplacebo -lm -pthread -o /tmp/official-hdr-gpu
python3 native/vlc-aar/experimental/run_official_hdr_reference.py /path/to/manifest.json \
  --gpu-runner /tmp/official-hdr-gpu --report /tmp/official-hdr-report.json
```

Manifest schema 1: object with `schema: 1` and `cases` array. Every case
requires `id, mode, source_url, provider_asset_id, reference_decoder_version,
frame_id, usage_permission, reference_stage, width, height, max_pq_error,
source, input, metadata, expected`. Each file object is
`{"path":"relative/file","sha256":"64 hex digits"}`. Keep source, decoded
input, metadata and expected from the exact same frame (document extraction
and PTS in frame_id); reference_stage must be prepass-bt2020-pq-rgb.

- hdr10plus: input and expected are little-endian float32 RGB triples,
  normalized PQ BT.2020, tightly packed. Metadata is the original unescaped
  ITU-T T.35 payload including provider header, not a recreated JSON curve.
  Supply target_min_nits, target_max_nits, source_mastering_max_nits. Reference
  must be prepass display-referred PQ at that characterized target, not a
  screenshot or final encoded SDR/HLG output.
- fel: dolby_profile=7, input is native sampler order Cr/Y/Cb, float32
  normalized 10-bit codewords (code/1023); metadata is unescaped complete
  Dolby RPU starting with byte 25; el is actual tightly packed little-endian
  uint16 Y/Cb/Cr 4:2:0 codewords; el_width and el_height describe EL. Expected
  is reference reconstructed BT.2020 PQ RGB float32. Current intake supports
  zero-origin visible rectangles and default chroma siting only; other cases
  must be explicitly extended, not silently resampled. Supply complete RPU
  (no unresolved prior state).

The checker invokes the actual spatial HDR10+ and FEL picture renderer,
reads GPU pixels, verifies hashes and outputs max/RMS PQ errors. Both formats
must pass; no missing-data or unsupported-mode skip is counted as a pass.
Limits 0.0025 and 0.001 are project guardrails, not official certification
thresholds. A provider-supplied reference, independently decoded source frames
and matching metadata remain mandatory; hashes do not authenticate an
untrusted provenance assertion. No official pixel equivalence, optical
measurements, licensed certification or device-level pass is claimed until
real authorized data and hardware results are available.
