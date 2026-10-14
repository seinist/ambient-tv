//! Native Vulkan compute execution of upstream's WGSL synthesis kernel.
//! wgpu owns feature negotiation and Vulkan synchronization (no render surface).
use std::{borrow::Cow, collections::BTreeMap, sync::mpsc, time::Duration};
use svpflow_core::smooth_engine::{GpuJob, GpuParams};
use wgpu::util::DeviceExt;

struct Source {
    packed: wgpu::Texture,
    linear: wgpu::Texture,
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
    output: wgpu::Buffer,
    readback: wgpu::Buffer,
    width: u32,
    height: u32,
    bytes: u64,
}

impl Gpu {
    pub fn clear_sources(&mut self) {
        self.sources.clear();
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
                | wgpu::TextureUsages::COPY_DST
                | if storage {
                    wgpu::TextureUsages::STORAGE_BINDING
                } else {
                    wgpu::TextureUsages::empty()
                },
        })
    }

    fn upload(
        &self,
        texture: &wgpu::Texture,
        data: &[u8],
        origin: wgpu::Origin3d,
        w: u32,
        h: u32,
        stride: u32,
    ) {
        self.queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture,
                mip_level: 0,
                origin,
                aspect: wgpu::TextureAspect::All,
            },
            data,
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(stride),
                rows_per_image: Some(h),
            },
            wgpu::Extent3d {
                width: w,
                height: h,
                depth_or_array_layers: 1,
            },
        );
    }

    fn source(&mut self, n: i32, data: &[u8]) {
        if self.sources.contains_key(&n) {
            return;
        }
        let (w, h) = (self.width, self.height);
        let packed = self.texture(w, h * 3 / 2, wgpu::TextureFormat::R8Unorm, false);
        let luma = (w * h) as usize;
        self.upload(&packed, &data[..luma], wgpu::Origin3d::ZERO, w, h, w);
        self.upload(
            &packed,
            &data[luma..luma * 5 / 4],
            wgpu::Origin3d { x: 0, y: h, z: 0 },
            w / 2,
            h / 2,
            w / 2,
        );
        self.upload(
            &packed,
            &data[luma * 5 / 4..],
            wgpu::Origin3d {
                x: w / 2,
                y: h,
                z: 0,
            },
            w / 2,
            h / 2,
            w / 2,
        );
        let linear = self.texture(w, h, wgpu::TextureFormat::R32Float, true);
        let input_view = packed.create_view(&Default::default());
        let output_view = linear.create_view(&Default::default());
        let group = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &self.prepass_layout,
            entries: &[
                texture_binding(0, &input_view),
                texture_binding(1, &output_view),
            ],
        });
        let empty_layout = self.prepass.get_bind_group_layout(0);
        let empty = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &empty_layout,
            entries: &[],
        });
        let mut encoder = self.device.create_command_encoder(&Default::default());
        {
            let mut pass = encoder.begin_compute_pass(&Default::default());
            pass.set_pipeline(&self.prepass);
            pass.set_bind_group(0, &empty, &[]);
            pass.set_bind_group(1, &group, &[]);
            pass.dispatch_workgroups(w.div_ceil(16), h.div_ceil(16), 1);
        }
        self.queue.submit([encoder.finish()]);
        self.sources.insert(n, Source { packed, linear });
    }

    fn field(&self, data: &[u16], w: u32, h: u32) -> wgpu::Texture {
        let texture = self.texture(w, h, wgpu::TextureFormat::Rgba32Float, false);
        let bytes: Vec<u8> = data
            .iter()
            .flat_map(|n| (*n as f32).to_le_bytes())
            .collect();
        self.upload(&texture, &bytes, wgpu::Origin3d::ZERO, w, h, w * 16);
        texture
    }

    pub fn render(
        &mut self,
        job: &GpuJob,
        current: &[u8],
        next: &[u8],
        output: &mut [u8],
    ) -> Result<(), String> {
        self.device.push_error_scope(wgpu::ErrorFilter::Validation);
        self.source(job.n, current);
        self.source(job.n + 1, next);
        let (w, h) = (job.grid.0 as u32, job.grid.1 as u32);
        let base = self.field(&job.base, w, h);
        let ext = job.ext.as_ref().map(|data| self.field(data, w, h));
        let mask = self.texture(w, h, wgpu::TextureFormat::Rgba8Unorm, false);
        self.upload(
            &mask,
            job.mask
                .as_deref()
                .unwrap_or(&vec![0; (w * h * 4) as usize]),
            wgpu::Origin3d::ZERO,
            w,
            h,
            w * 4,
        );
        let base_view = base.create_view(&Default::default());
        let ext_view = ext
            .as_ref()
            .unwrap_or(&base)
            .create_view(&Default::default());
        let mask_view = mask.create_view(&Default::default());
        let mut encoder = self.device.create_command_encoder(&Default::default());
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
            let stride = if plane == 0 {
                self.width
            } else {
                self.width / 2
            };
            let params = self
                .device
                .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                    label: None,
                    contents: &params_bytes(p, start, stride),
                    usage: wgpu::BufferUsages::UNIFORM,
                });
            let view = |n| {
                let source = &self.sources[&n];
                if plane == 0 && job.linear {
                    &source.linear
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
                    (p.width as u32).div_ceil(16),
                    (p.height as u32).div_ceil(16),
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
        self.sources.retain(|n, _| *n >= job.n);
        Ok(())
    }
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
fn params_bytes(p: &GpuParams, base: u32, stride: u32) -> Vec<u8> {
    let mut data: Vec<u8> = [
        p.algorithm,
        p.width,
        p.height,
        p.x_ratio,
        p.y_ratio,
        p.pel,
        p.block_w,
        p.block_h,
        p.origin_x,
        p.origin_y,
        p.phase,
        p.has_sad,
        p.linear_luma,
        p.cubic,
        p.cubic_ref,
        p.offset_x,
        p.offset_y,
    ]
    .iter()
    .flat_map(|v| v.to_le_bytes())
    .collect();
    data.extend(p.sad_blend.to_le_bytes());
    data.extend(p.dither.to_le_bytes());
    data.extend(base.to_le_bytes());
    data.extend(stride.to_le_bytes());
    data.resize(96, 0);
    data
}
