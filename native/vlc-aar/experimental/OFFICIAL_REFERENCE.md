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

## Public standards-based alternative to proprietary golden images

The ETSI GS CCM 001 V1.1.1 specification (2017-02), clauses 5.4.2–5.4.3, is available at https://www.etsi.org/deliver/etsi_gs/CCM/001_099/001/01.01.01_60/gs_ccm001v010101p.pdf . CI retrieves the public dovi_tool Profile-7 FEL RPU fixture at commit 41dae646193c12de152333ff5dbc33579cb9ef4a, verifies its Git blob identity, and parses the actual bytes with Ambient's patched FFmpeg. A separate GPU test compares 4096 aligned BL/EL sample triplets with an independently written integer Q20/Q16/12-bit oracle (polynomial, MMR, NLQ, signed floor, rounding and clipping). Maximum allowed difference: one 12-bit code value; a larger discrepancy fails CI. The test samples are constructed, the RPU is external, and the mathematical reference is the published standard. This is not a Dolby-certified reference picture; it excludes normative chroma downsampling of spatially varying inputs, resampling, display mapping and optical output. No copyrighted video frames are redistributed.

For actual official HDR10+ picture input, the AOM AV1 HDR10+ conformance corpus includes the Netflix Sparks MP4 at commit 47f1059c45f95b21b77a94037618e79c44d79c4e. See https://aomediacodec.github.io/av1-hdr10plus/conformance/ and the accompanying CC BY-NC-ND 4.0 license. Use only if your testing is permitted under that license or separate authorization. A manual workflow dispatch with aom_reference=true retrieves the LFS object and checks its SHA-256. Only numerical metrics are uploaded; decoded and transformed images exist only in a temporary directory. No automated commercial-use assumption is made. The GPU comparison uses the original official access unit and T.35 data, independent double-precision ST 2084/Bezier calculations, and actual decoded pixels. Authored single-window curves and no-compression clipping are covered; unsupported metadata or no independently decodable first frame fails explicitly. This verifies the prepass, not AV1 hardware decoding, display fitting or certification.

For the CC BY 4.0 DVB/EBU/Samsung HEVC sample listed above, use the same local script with --kind dvb and the SHA-256 of your authorized original TS. The official download is behind a form; it is not scraped or bypassed. Example (after compiling test-official-hdr):

```sh
python3 native/vlc-aar/experimental/audit_public_hdr10plus.py /path/to/DVB_2160p50_HDR_with_2094-40_DM.ts --kind dvb --sha256 "$(sha256sum /path/to/DVB_2160p50_HDR_with_2094-40_DM.ts | cut -d' ' -f1)" --gpu-runner .ci/test-official-hdr --report dvb-metrics.json
```

This path provides official source material plus a standards-derived independent output, not an official golden rendered image. The separate official golden intake remains a distinct higher-level validation gate. Do not mark missing Dolby reference pictures or unexecuted CI as passed.

The external RPU gate now also evaluates two actual HEVC-decoded 64x64 Main10 4:2:0 frames, using the native R16 EL texture uploads and the spatial GPU composition path. A separate integer oracle computes equal-resolution centered chroma interpolation (quarter-pixel weights, signed half-away rounding), followed by the ETSI-derived fixed-point reconstruction. Both tests retain the one-code maximum error limit. The decoded pictures are generated in CI; the public RPU was not authored for these pictures. This adds coverage of the renderer's spatial approximation, not normative Dolby resampling or licensed golden imagery. Previous aligned external-RPU CI (f4fe619) passed with 12005/12288 exact and all within one code.

The spatial FEL gate now uses a rational sixteenth-texel coordinate oracle for nine geometry/siting configurations: identity with centered, negative and positive chroma offsets; half-resolution on both axes with centered and asymmetric siting; horizontal-only and vertical-only half-resolution; odd EL visible dimensions with integral crop offsets (including odd chroma phase), with two siting choices. Each case uses samples cropped from the decoded EL plane, and exercises the production coded-geometry mapper, actual R16 uploads and GPU interpolation. 9 × 4096 pixels = 110592 component comparisons; the maximum one-code tolerance applies separately to every case. Invalid origin and coverage cases must be rejected. Texture resources are reused between cases. This is a regression oracle for Ambient's bilinear approximation, not the normative Dolby filter. The original source frames are generated independently and externally supplied RPU coefficients are not their original metadata.

### ETSI Annex B.3 reference EL resampling

The public specification calls Annex B informative (clause 4 note 1), not normative. The experimental Profile 7 renderer now exports the original RPU's BL/EL resampling flags through the private FFmpeg ABI. For a signalled half-resolution EL on both axes it performs the Annex B.3 vertical phase-specific luma/chroma filter, followed by the Annex B.2 eight-tap horizontal filter, using reusable R16 GPU textures, integer arithmetic, rounding/clipping after each pass and replicated edges. It then uses the existing NLQ and packed PQ RGB prepass. Equal-resolution, unsignalled EL bypasses the new filter. Unsupported BL-resampling, flag/ratio mismatch and odd visible EL sizes in the reference-filter path fail closed; MEL needs no EL upload. The final 4:2:0-to-packed grid and BL chroma prediction remain approximations, not a normative 4:2:0 compositor. Annex B.2 BL vertical filter is not enabled for this full-resolution Profile 7 BL input. Independent CPU filter tests compare all 16-bit codes on decoded samples and stress patterns (12288 samples) before NLQ, in addition to the prior spatial approximation gate. No Dolby certification is implied. Source: ETSI GS CCM 001 V1.1.1, p. 37, https://www.etsi.org/deliver/etsi_gs/CCM/001_099/001/01.01.01_60/gs_ccm001v010101p.pdf .

The reference-filter output retains Annex B's 16-bit intermediate range through NLQ rather than prematurely clamping to 10-bit. Additional checks exercise the actual renderer's RPU flag routing, contradictory geometry rejection, and the filtered composition against the independent fixed-point oracle (one 12-bit code limit). 1088-line coded EL allocations are supported by the intermediate texture bound. Previous nine bilinear regression cases all passed (110592 components within one code, workflow 46060243099); these new filter tests are distinct.

### Native 4:2:0 composition

The actual experimental renderer now reconstructs full-size Y and half-size Cb/Cr separately, with the original nearest-sampled RGB-identity P010 codewords in Cr/Y/Cb order. ETSI 5.4.2.3.3 luma downsampling uses the independently rounded horizontal 1:2:1 kernel on two adjacent rows, then rounded vertical averaging, with replicated edges. MMR uses this Y predictor and native chroma codes, not per-luma-pixel chroma interpolation. The filtered EL remains 4:2:0 through NLQ and the 12-bit rounding step; persistent R16/RG16 (RGBA16 fallback) textures retain reconstructed integer codewords. Only the subsequent display conversion upsamples reconstructed UV (explicit left/center bilinear policy) and applies the established PQ matrix. The old packed helper remains available for legacy tests, not the live renderer. Native EL offset must be even and layer grids matched after any Annex B.3 expansion; unsupported luma MMR and BL upscale remain rejected. The independent integer oracle checks the live renderer's Y and UV outputs, including a forced luma-dependent MMR case, with at most one 12-bit code difference. Physical Android sampler behavior, bit-exact fixed-point GPU MMR, display interpolation equivalence and licensed reference frames remain separate validation requirements.

The live 4:2:0 composer now uses a portable 96-bit two's-complement accumulator implemented with 32-bit GLSL words and umulExtended. Q20 polynomial/MMR terms, signed Q16 prediction, NLQ saturation and signed rounding precede 12-bit output quantization. The original RPU integer coefficients are uploaded as word pairs. The native Y/UV oracle requires zero code differences (legacy packed float tests retain their one-code bound). Display chroma expansion uses the separable four-tap Annex C reference kernel, with signed Q6 vertical intermediate and final rounding/clipping to 12 bits. Applying this kernel to PQ RGB display conversion is an explicit policy, not a normative Dolby PQ rule. CI and real-device validation are required before asserting bit-exact results.

Signalled BL resampling now extracts the original native nearest-sampled P010 planes, performs the Annex B.2 six-tap vertical and eight-tap horizontal integer filters on each component, then passes the expanded BL to the native fixed-point composer. A verified factor-two geometry is used to align it with the independently signalled EL, and output crop coordinates are doubled. The actual live renderer is tested with both layers signalled and with independently calculated filter output and native reconstruction; odd BL sizes and geometry outside the supported 1920x1088 source remain rejected. The public Annex B reference does not specify the full proprietary Profile-7 flag contract; testing real signalled streams and Android import remains necessary.
