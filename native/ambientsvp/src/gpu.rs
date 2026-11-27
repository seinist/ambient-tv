//! Native Vulkan compute execution of upstream's WGSL synthesis kernel.
//! wgpu owns feature negotiation and Vulkan synchronization (no render surface).
use std::{borrow::Cow, collections::BTreeMap, sync::mpsc, time::Duration};
use svpflow_core::smooth_engine::{GpuJob, GpuParams};

struct Source {
    packed: wgpu::Texture,
    linear: Option<wgpu::Texture>,
    linear_valid: bool,
}
struct FieldCache {
    texture: wgpu::Texture,
    grid: (u32, u32),
    values: Vec<u16>,
}
struct MaskCache {
    texture: wgpu::Texture,
    grid: (u32, u32),
    values: Option<Vec<u8>>,
}

pub struct Gpu {
    device: wgpu::Device,
    queue: wgpu::Queue,
    shader: wgpu::ShaderModule,
    layout: wgpu::BindGroupLayout,
    pipeline_layout: wgpu::PipelineLayout,
    prepass: wgpu::ComputePipeline,
    prepass_layout: wgpu::BindGroupLayout,
    pipelines: BTreeMap<[i32; 6], wgpu::ComputePipeline>,
    sources: BTreeMap<i32, Source>,
    recycled_sources: Vec<Source>,
    empty_prepass: wgpu::BindGroup,
    base_field: Option<FieldCache>,
    ext_field: Option<FieldCache>,
    mask: Option<MaskCache>,
    uniforms: [wgpu::Buffer; 3],
    output: wgpu::Buffer,
    readback: wgpu::Buffer,
    width: u32,
    height: u32,
    bytes: u64,
}

impl Gpu {
    pub fn clear_sources(&mut self) {
        self.recycled_sources.extend(std::mem::take(&mut self.sources).into_values());
        self.recycled_sources.truncate(2);
    }
    pub fn new(width: u32, height: u32) -> Result<Self, String> {
        let instance = wgpu::Instance::new(&wgpu::InstanceDescriptor {
            backends: wgpu::Backends::VULKAN,
            ..Default::default()
        });
        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::HighPerformance,
            force_fallback_adapter: false,
            compatible_surface: None,
        }))
        .ok_or("no Vulkan compute adapter")?;
        if adapter.get_info().device_type == wgpu::DeviceType::Cpu {
            return Err("software Vulkan adapter".into());
        }
        let (device, queue) = pollster::block_on(adapter.request_device(
            &wgpu::DeviceDescriptor {
                label: Some("Open-SVPFlow compute"),
                required_features: wgpu::Features::empty(),
                required_limits: wgpu::Limits::default().using_resolution(adapter.limits()),
                memory_hints: wgpu::MemoryHints::MemoryUsage,
            },
            None,
        ))
        .map_err(|e| e.to_string())?;
        device.push_error_scope(wgpu::ErrorFilter::Validation);
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Open-SVPFlow smooth.wgsl"),
            source: wgpu::ShaderSource::Wgsl(Cow::Borrowed(include_str!("../shaders/smooth.wgsl"))),
        });
        let mut entries = vec![wgpu::BindGroupLayoutEntry {
            binding: 0,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Buffer {
                ty: wgpu::BufferBindingType::Uniform,
                has_dynamic_offset: false,
                min_binding_size: None,
            },
            count: None,
        }];
        for binding in 1..=5 {
            entries.push(texture_entry(binding));
        }
        entries.push(wgpu::BindGroupLayoutEntry {
            binding: 6,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Buffer {
                ty: wgpu::BufferBindingType::Storage { read_only: false },
                has_dynamic_offset: false,
                min_binding_size: None,
            },
            count: None,
        });
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: None,
            entries: &entries,
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: None,
            bind_group_layouts: &[&layout],
            push_constant_ranges: &[],
        });
        let prepass_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: None,
            entries: &[
                texture_entry(0),
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::StorageTexture {
                        access: wgpu::StorageTextureAccess::WriteOnly,
                        format: wgpu::TextureFormat::R32Float,
                        view_dimension: wgpu::TextureViewDimension::D2,
                    },
                    count: None,
                },
            ],
        });
        let empty = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: None,
            entries: &[],
        });
        let pre_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: None,
            bind_group_layouts: &[&empty, &prepass_layout],
            push_constant_ranges: &[],
        });
        let empty_prepass = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None, layout: &empty, entries: &[],
        });
        let prepass = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: None,
            layout: Some(&pre_layout),
            module: &shader,
            entry_point: Some("linear_luma"),
            compilation_options: Default::default(),
            cache: None,
        });
        let bytes = (width as u64 * height as u64 * 3 / 2).div_ceil(4) * 4;
        let output = device.create_buffer(&wgpu::BufferDescriptor {
            label: None,
            size: bytes,
            usage: wgpu::BufferUsages::STORAGE
                | wgpu::BufferUsages::COPY_SRC
                | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let readback = device.create_buffer(&wgpu::BufferDescriptor {
            label: None,
            size: bytes,
            usage: wgpu::BufferUsages::MAP_READ | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        if let Some(e) = pollster::block_on(device.pop_error_scope()) {
            return Err(e.to_string());
        }
        let uniforms = std::array::from_fn(|_| device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("SVP plane parameters"),
            size: 96,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        }));
        Ok(Self {
            device,
            queue,
            shader,
            layout,
            pipeline_layout,
            prepass,
            prepass_layout,
            pipelines: BTreeMap::new(),
            sources: BTreeMap::new(),
            recycled_sources: Vec::new(),
            empty_prepass,
            base_field: None,
            ext_field: None,
            mask: None,
            uniforms,
            output,
            readback,
            width,
            height,
            bytes,
        })
    }

    fn texture(
        &self,
        width: u32,
        height: u32,
        format: wgpu::TextureFormat,
        storage: bool,
    ) -> wgpu::Texture {
        self.device.create_texture(&wgpu::TextureDescriptor {
            label: None,
            size: wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format,
            view_formats: &[],
            usage: wgpu::TextureUsages::TEXTURE_BINDING
                | if cfg!(test) { wgpu::TextureUsages::COPY_SRC } else { wgpu::TextureUsages::empty() }
                | wgpu::TextureUsages::COPY_DST
                | if storage {
                    wgpu::TextureUsages::STORAGE_BINDING
                } else {
                    wgpu::TextureUsages::empty()
                },
        })
    }

    // Keep the explicit MAP_WRITE allocation: Queue::write_texture and implicit
    // transient staging have crashed Android gfxstream at video resolutions.
    // Fill the mapped allocation directly, with no intermediate padded Vec.
    fn upload_rows(
        &self,
        encoder: &mut wgpu::CommandEncoder,
        texture: &wgpu::Texture,
        w: u32,
        h: u32,
        stride: u32,
        mut fill: impl FnMut(usize, &mut [u8]),
    ) {
        let pitch = stride.div_ceil(wgpu::COPY_BYTES_PER_ROW_ALIGNMENT)
            * wgpu::COPY_BYTES_PER_ROW_ALIGNMENT;
        let staging = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("SVP explicit texture upload"),
            size: u64::from(pitch) * u64::from(h),
            usage: wgpu::BufferUsages::COPY_SRC | wgpu::BufferUsages::MAP_WRITE,
            mapped_at_creation: true,
        });
        {
            let mut mapped = staging.slice(..).get_mapped_range_mut();
            for (row, dst) in mapped.chunks_exact_mut(pitch as usize).enumerate() {
                fill(row, &mut dst[..stride as usize]);
            }
        }
        staging.unmap();
        encoder.copy_buffer_to_texture(
            wgpu::TexelCopyBufferInfo {
                buffer: &staging,
                layout: wgpu::TexelCopyBufferLayout {
                    offset: 0, bytes_per_row: Some(pitch), rows_per_image: Some(h),
                },
            },
            texture.as_image_copy(),
            wgpu::Extent3d { width: w, height: h, depth_or_array_layers: 1 },
        );
    }

    fn source(&mut self, n: i32, data: &[u8], linear_needed: bool,
              encoder: &mut wgpu::CommandEncoder) {
        let (w, h) = (self.width, self.height);
        if !self.sources.contains_key(&n) {
            let mut source = self.recycled_sources.pop().unwrap_or_else(|| Source {
                packed: self.texture(w, h * 3 / 2, wgpu::TextureFormat::R8Unorm, false),
                linear: None,
                linear_valid: false,
            });
            source.linear_valid = false;
            let (width, height) = (w as usize, h as usize);
            let luma = width * height;
            let half = width / 2;
            self.upload_rows(encoder, &source.packed, w, h * 3 / 2, w, |row, dst| {
                if row < height {
                    dst.copy_from_slice(&data[row * width..(row + 1) * width]);
                } else {
                    let offset = (row - height) * half;
                    dst[..half].copy_from_slice(&data[luma + offset..luma + offset + half]);
                    let v = luma * 5 / 4 + offset;
                    dst[half..].copy_from_slice(&data[v..v + half]);
                }
            });
            self.sources.insert(n, source);
        }
        if !linear_needed || self.sources[&n].linear_valid {
            return;
        }
        if self.sources[&n].linear.is_none() {
            let texture = self.texture(w, h, wgpu::TextureFormat::R32Float, true);
            self.sources.get_mut(&n).unwrap().linear = Some(texture);
        }
        let source = self.sources.get_mut(&n).unwrap();
        let input_view = source.packed.create_view(&Default::default());
        let output_view = source.linear.as_ref().unwrap().create_view(&Default::default());
        let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &self.prepass_layout,
            entries: &[texture_binding(0, &input_view), texture_binding(1, &output_view)],
        });
        {
            let mut pass = encoder.begin_compute_pass(&Default::default());
            pass.set_pipeline(&self.prepass);
            pass.set_bind_group(0, &self.empty_prepass, &[]);
            pass.set_bind_group(1, &group, &[]);
            pass.dispatch_workgroups(w.div_ceil(16), h.div_ceil(16), 1);
        }
        source.linear_valid = true;
    }

    fn field(&self, encoder: &mut wgpu::CommandEncoder, cache: &mut Option<FieldCache>,
             data: &[u16], w: u32, h: u32) {
        if cache.as_ref().is_some_and(|c| c.grid == (w, h) && c.values.as_slice() == data) {
            return;
        }
        let mut field = cache.take().filter(|c| c.grid == (w, h)).unwrap_or_else(|| FieldCache {
            texture: self.texture(w, h, wgpu::TextureFormat::Rgba32Float, false),
            grid: (w, h),
            values: Vec::new(),
        });
        let row_values = w as usize * 4;
        self.upload_rows(encoder, &field.texture, w, h, w * 16, |row, dst| {
            for (out, value) in dst.chunks_exact_mut(4)
                .zip(&data[row * row_values..(row + 1) * row_values]) {
                out.copy_from_slice(&(*value as f32).to_le_bytes());
            }
        });
        field.values.clear();
        field.values.extend_from_slice(data);
        *cache = Some(field);
    }

    pub fn render(
        &mut self,
        job: &GpuJob,
        current: &[u8],
        next: &[u8],
        output: &mut [u8],
    ) -> Result<(), String> {
        self.device.push_error_scope(wgpu::ErrorFilter::Validation);
        let mut encoder = self.device.create_command_encoder(&Default::default());
        // Retire old sources before allocating new ones; queue ordering makes
        // reuse safe, and render waits for the previous readback before returning.
        let retained = self.sources.split_off(&job.n);
        self.recycled_sources.extend(std::mem::replace(&mut self.sources, retained).into_values());
        self.recycled_sources.truncate(2);
        self.source(job.n, current, job.linear, &mut encoder);
        self.source(job.n + 1, next, job.linear, &mut encoder);
        let (w, h) = (job.grid.0 as u32, job.grid.1 as u32);
        // Cache by the actual contents, not merely the frame pair: SAD masks
        // and coverage fields can change with fractional phase.
        let mut base = self.base_field.take();
        self.field(&mut encoder, &mut base, &job.base, w, h);
        self.base_field = base;
        if let Some(data) = job.ext.as_deref() {
            let mut ext = self.ext_field.take();
            self.field(&mut encoder, &mut ext, data, w, h);
            self.ext_field = ext;
        }
        let mask_data = job.mask.as_deref();
        if !self.mask.as_ref().is_some_and(|m| m.grid == (w, h) && m.values.as_deref() == mask_data) {
            let mut mask = self.mask.take().filter(|m| m.grid == (w, h)).unwrap_or_else(|| MaskCache {
                texture: self.texture(w, h, wgpu::TextureFormat::Rgba8Unorm, false),
                grid: (w, h),
                values: None,
            });
            let stride = w as usize * 4;
            self.upload_rows(&mut encoder, &mask.texture, w, h, w * 4, |row, dst| {
                if let Some(data) = mask_data {
                    dst.copy_from_slice(&data[row * stride..(row + 1) * stride]);
                } else {
                    dst.fill(0);
                }
            });
            match mask_data {
                Some(data) => {
                    let values = mask.values.get_or_insert_with(Vec::new);
                    values.clear();
                    values.extend_from_slice(data);
                }
                None => mask.values = None,
            }
            self.mask = Some(mask);
        }
        let base = &self.base_field.as_ref().unwrap().texture;
        let ext = if job.ext.is_some() {
            &self.ext_field.as_ref().unwrap().texture
        } else {
            base
        };
        let base_view = base.create_view(&Default::default());
        let ext_view = ext.create_view(&Default::default());
        let mask_view = self.mask.as_ref().unwrap().texture.create_view(&Default::default());
        encoder.clear_buffer(&self.output, 0, None);
        for (plane, p) in job.params.iter().enumerate() {
            let key = [
                p.algorithm,
                p.cubic,
                p.cubic_ref,
                p.has_sad,
                p.linear_luma,
                p.dither,
            ];
            if !self.pipelines.contains_key(&key) {
                let constants = [
                    ("ALGO", p.algorithm as f64),
                    ("CUBIC", (p.cubic != 0) as u8 as f64),
                    ("CUBIC_REF", (p.cubic_ref != 0) as u8 as f64),
                    ("HAS_SAD", (p.has_sad != 0) as u8 as f64),
                    ("LINEAR_LUMA", (p.linear_luma != 0) as u8 as f64),
                    ("DITHER", (p.dither != 0) as u8 as f64),
                ];
                let constants = constants
                    .into_iter()
                    .map(|(k, v)| (k.to_string(), v))
                    .collect();
                let pipeline =
                    self.device
                        .create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                            label: None,
                            layout: Some(&self.pipeline_layout),
                            module: &self.shader,
                            entry_point: Some("render"),
                            compilation_options: wgpu::PipelineCompilationOptions {
                                constants: &constants,
                                ..Default::default()
                            },
                            cache: None,
                        });
                self.pipelines.insert(key, pipeline);
            }
            let luma = self.width * self.height;
            let start = [0, luma, luma * 5 / 4][plane];
            let (stride_words, groups_x, groups_y) = dispatch_geometry(start, p.width as u32, p.height as u32);
            let params = &self.uniforms[plane];
            self.queue.write_buffer(params, 0, &params_bytes(p, start, stride_words));
            let view = |n| {
                let source = &self.sources[&n];
                if plane == 0 && job.linear {
                    source.linear.as_ref().expect("linear source prepared")
                } else {
                    &source.packed
                }
                .create_view(&Default::default())
            };
            let current_view = view(job.n);
            let next_view = view(job.n + 1);
            let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: None,
                layout: &self.layout,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: params.as_entire_binding(),
                    },
                    texture_binding(1, &current_view),
                    texture_binding(2, &next_view),
                    texture_binding(3, &base_view),
                    texture_binding(4, &ext_view),
                    texture_binding(5, &mask_view),
                    wgpu::BindGroupEntry {
                        binding: 6,
                        resource: self.output.as_entire_binding(),
                    },
                ],
            });
            {
                let mut pass = encoder.begin_compute_pass(&Default::default());
                pass.set_pipeline(&self.pipelines[&key]);
                pass.set_bind_group(0, &group, &[]);
                pass.dispatch_workgroups(
                    groups_x,
                    groups_y,
                    1,
                );
            }
        }
        encoder.copy_buffer_to_buffer(&self.output, 0, &self.readback, 0, self.bytes);
        self.queue.submit([encoder.finish()]);
        let (tx, rx) = mpsc::sync_channel(1);
        self.readback
            .slice(..)
            .map_async(wgpu::MapMode::Read, move |r| {
                let _ = tx.send(r);
            });
        // Poll without an unbounded vkWaitForFences: seek/stop must recover from a lost GPU.
        let deadline = std::time::Instant::now() + Duration::from_secs(3);
        let mapped = loop {
            self.device.poll(wgpu::Maintain::Poll);
            match rx.try_recv() {
                Ok(result) => break result.map_err(|e| e.to_string()),
                Err(mpsc::TryRecvError::Disconnected) => break Err("GPU map disconnected".into()),
                Err(_) if std::time::Instant::now() >= deadline => {
                    break Err("GPU compute timeout".into());
                }
                Err(_) => std::thread::sleep(Duration::from_micros(200)),
            }
        };
        let validation = pollster::block_on(self.device.pop_error_scope());
        mapped?;
        if let Some(e) = validation {
            self.readback.unmap();
            return Err(e.to_string());
        }
        {
            let mapped = self.readback.slice(..).get_mapped_range();
            output.copy_from_slice(&mapped[..output.len()]);
        }
        self.readback.unmap();
        Ok(())
    }
}

// WGSL render uses 8x8 invocations, each writing one packed u32 (four
// pixels). Unaligned planes use a flattened word grid, preserving the bytes
// written by the preceding plane at a shared boundary word.
fn dispatch_geometry(base: u32, width: u32, height: u32) -> (u32, u32, u32) {
    let words_per_row = width.div_ceil(4);
    let rows = if base % 4 == 0 && width % 4 == 0 {
        height
    } else {
        ((base % 4 + width * height).div_ceil(4)).div_ceil(words_per_row)
    };
    (words_per_row, words_per_row.div_ceil(8), rows.div_ceil(8))
}

fn texture_entry(binding: u32) -> wgpu::BindGroupLayoutEntry {
    wgpu::BindGroupLayoutEntry {
        binding,
        visibility: wgpu::ShaderStages::COMPUTE,
        ty: wgpu::BindingType::Texture {
            sample_type: wgpu::TextureSampleType::Float { filterable: false },
            view_dimension: wgpu::TextureViewDimension::D2,
            multisampled: false,
        },
        count: None,
    }
}
fn texture_binding(binding: u32, view: &wgpu::TextureView) -> wgpu::BindGroupEntry<'_> {
    wgpu::BindGroupEntry {
        binding,
        resource: wgpu::BindingResource::TextureView(view),
    }
}
fn params_bytes(p: &GpuParams, base: u32, stride: u32) -> [u8; 96] {
    let mut data = [0u8; 96];
    let ints = [
        p.algorithm, p.width, p.height, p.x_ratio, p.y_ratio, p.pel,
        p.block_w, p.block_h, p.origin_x, p.origin_y, p.phase, p.has_sad,
        p.linear_luma, p.cubic, p.cubic_ref, p.offset_x, p.offset_y,
    ];
    for (dst, v) in data.chunks_exact_mut(4).zip(ints) {
        dst.copy_from_slice(&v.to_le_bytes());
    }
    data[68..72].copy_from_slice(&p.sad_blend.to_le_bytes());
    data[72..76].copy_from_slice(&p.dither.to_le_bytes());
    data[76..80].copy_from_slice(&base.to_le_bytes());
    data[80..84].copy_from_slice(&stride.to_le_bytes());
    data
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dispatch_covers_every_word_once() {
        for (w, h) in [(32, 32), (33, 17), (64, 64), (65, 33), (960, 540), (1920, 1080)] {
            for base in [0, 1, 2, 3, 4096] {
                let (stride, gx, gy) = dispatch_geometry(base, w, h);
                let words = (base % 4 + w * h).div_ceil(4);
                let mut covered = vec![0u8; words as usize];
                for y in 0..gy * 8 {
                    for x in 0..gx * 8 {
                        if x >= stride { continue; }
                        let i = y * stride + x;
                        if i < words { covered[i as usize] += 1; }
                    }
                }
                assert!(covered.iter().all(|n| *n == 1), "{base} {w}x{h}");
            }
        }
    }

    // Also runnable as an Android test executable via cargo ndk test --lib --no-run.
    // The original write_texture path SIGSEGVs on the emulator at 2560x1440.
    #[test]
    #[ignore = "requires hardware Vulkan"]
    fn texture_upload_round_trip_at_video_resolutions() {
        for (w, h) in [(128, 72), (1920, 1080), (2560, 1440), (3840, 2160)] {
            for cycle in 0..3 {
                let mut gpu = Gpu::new(w, h).unwrap();
                let data: Vec<u8> = (0..w as usize * h as usize * 3 / 2)
                    .map(|n| ((n * 17 + n / w as usize + cycle * 31) % 251) as u8).collect();
                let mut upload = gpu.device.create_command_encoder(&Default::default());
                gpu.source(0, &data, false, &mut upload);
                gpu.queue.submit([upload.finish()]);
                let pitch = w.div_ceil(wgpu::COPY_BYTES_PER_ROW_ALIGNMENT) * wgpu::COPY_BYTES_PER_ROW_ALIGNMENT;
                let readback = gpu.device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("SVP upload regression readback"),
                    size: pitch as u64 * (h * 3 / 2) as u64,
                    usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
                    mapped_at_creation: false,
                });
                // The production source texture needs COPY_SRC only for this readback test.
                let mut encoder = gpu.device.create_command_encoder(&Default::default());
                encoder.copy_texture_to_buffer(
                    gpu.sources[&0].packed.as_image_copy(),
                    wgpu::TexelCopyBufferInfo { buffer: &readback, layout: wgpu::TexelCopyBufferLayout {
                        offset: 0, bytes_per_row: Some(pitch), rows_per_image: Some(h * 3 / 2),
                    } },
                    wgpu::Extent3d { width: w, height: h * 3 / 2, depth_or_array_layers: 1 },
                );
                gpu.queue.submit([encoder.finish()]);
                let (tx, rx) = mpsc::sync_channel(1);
                readback.slice(..).map_async(wgpu::MapMode::Read, move |r| { tx.send(r).unwrap(); });
                gpu.device.poll(wgpu::Maintain::Wait);
                rx.recv_timeout(Duration::from_secs(10)).unwrap().unwrap();
                let mapped = readback.slice(..).get_mapped_range();
                for row in 0..h as usize * 3 / 2 {
                    let actual = &mapped[row * pitch as usize..row * pitch as usize + w as usize];
                    if row < h as usize {
                        assert_eq!(actual, &data[row * w as usize..(row + 1) * w as usize]);
                    } else {
                        let chroma_row = row - h as usize;
                        let luma = (w * h) as usize;
                        let half = w as usize / 2;
                        assert_eq!(&actual[..half], &data[luma + chroma_row * half..luma + (chroma_row + 1) * half]);
                        assert_eq!(&actual[half..], &data[luma * 5 / 4 + chroma_row * half..luma * 5 / 4 + (chroma_row + 1) * half]);
                    }
                }
                drop(mapped);
                readback.unmap();
                eprintln!("PASS upload {w}x{h} cycle={cycle}");
            }
        }
    }
}
