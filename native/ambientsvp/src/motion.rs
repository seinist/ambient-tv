//! Exact integer SAD batch primitive for a future Vulkan motion analyser.
//! It deliberately does not choose vectors or replace upstream search stages:
//! those require pyramid, predictor, SATD, penalties and border parity.
use wgpu::util::DeviceExt;

const MAX_CANDIDATES: usize = 65_535;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Candidate {
    pub x: u32,
    pub y: u32,
    pub dx: i32,
    pub dy: i32,
    pub width: u32,
    pub height: u32,
    pub reverse: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Cost {
    pub y: u32,
    pub u: u32,
    pub v: u32,
}

impl Candidate {
    fn valid(self, width: u32, height: u32) -> bool {
        // Interior pel=1 I420 blocks only. Padding and fractional pel are
        // handled by later kernels, not by implicit edge clamping here.
        let inside = |x: i64, y: i64, w: u32, h: u32| {
            x >= 0 && y >= 0
                && x + i64::from(self.width) <= i64::from(w)
                && y + i64::from(self.height) <= i64::from(h)
        };
        matches!(self.width, 8 | 16 | 32)
            && matches!(self.height, 8 | 16 | 32)
            && self.x % 2 == 0 && self.y % 2 == 0
            && width % 2 == 0 && height % 2 == 0
            && inside(i64::from(self.x), i64::from(self.y), width, height)
            && inside(i64::from(self.x) + i64::from(self.dx),
                      i64::from(self.y) + i64::from(self.dy), width, height)
    }
}

/// CPU reference for the raw per-plane metric, not upstream vector selection.
pub fn reference_cost(a: &[u8], b: &[u8], width: u32, height: u32, c: Candidate) -> Option<Cost> {
    let len = (width as usize).checked_mul(height as usize)?.checked_mul(3)?.checked_div(2)?;
    if a.len() != len || b.len() != len || !c.valid(width, height) { return None; }
    let (a,b) = if c.reverse { (b,a) } else { (a,b) };
    let luma = width as usize * height as usize;
    let mut result = [0;3];
    for plane in 0..3 {
        let shift = usize::from(plane != 0);
        let pitch = width as usize >> shift;
        let base = [0,luma,luma*5/4][plane];
        let sx = c.x as i64 >> shift;
        let sy = c.y as i64 >> shift;
        let rx = (i64::from(c.x) + i64::from(c.dx)) >> shift;
        let ry = (i64::from(c.y) + i64::from(c.dy)) >> shift;
        for y in 0..(c.height as usize >> shift) {
            for x in 0..(c.width as usize >> shift) {
                let left = a[base + (sy as usize+y)*pitch + sx as usize+x];
                let right = b[base + (ry as usize+y)*pitch + rx as usize+x];
                result[plane] += u32::from(left.abs_diff(right));
            }
        }
    }
    Some(Cost {y:result[0],u:result[1],v:result[2]})
}

pub struct MotionCostKernel {
    pipeline: wgpu::ComputePipeline,
}

impl MotionCostKernel {
    pub fn new(device: &wgpu::Device) -> Self {
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("SVP exact motion candidate costs"),
            source: wgpu::ShaderSource::Wgsl(std::borrow::Cow::Borrowed(WGSL)),
        });
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("SVP candidate SAD reduction"),
            layout: None,
            module: &shader,
            entry_point: Some("cost"),
            compilation_options: Default::default(),
            cache: None,
        });
        Self { pipeline }
    }

    /// Enqueue one independent workgroup per candidate on the caller's
    /// queue. Source textures use the existing packed SVP layout (Y above
    /// side-by-side U/V); no additional pixel upload is needed. The output
    /// is vec4<u32> (Y, U, V, sum) per candidate, ready for later GPU stages.
    pub fn encode(&self, device: &wgpu::Device, encoder: &mut wgpu::CommandEncoder,
                  a: &wgpu::Texture, b: &wgpu::Texture,
                  width: u32, height: u32, candidates: &[Candidate])
                  -> Result<wgpu::Buffer, String> {
        if width == 0 || height == 0 || width > 3840 || height > 2160
            || a.width() != width || b.width() != width
            || a.height() != height*3/2 || b.height() != height*3/2
            || a.format() != wgpu::TextureFormat::R8Unorm || b.format() != a.format()
            || candidates.is_empty() || candidates.len() > MAX_CANDIDATES
            || candidates.len() > device.limits().max_compute_workgroups_per_dimension as usize
            || candidates.iter().any(|c| !c.valid(width,height))
        { return Err("unsupported motion candidate batch".into()); }
        let bytes: Vec<u8> = candidates.iter().flat_map(|c| {
            [c.x as i32,c.y as i32,c.dx,c.dy,c.width as i32,c.height as i32,
             i32::from(c.reverse),0].into_iter().flat_map(i32::to_le_bytes)
        }).collect();
        let input = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("SVP candidate descriptors"), contents: &bytes,
            usage: wgpu::BufferUsages::STORAGE,
        });
        let output = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("SVP candidate costs"),
            size: candidates.len() as u64 * 16,
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_SRC,
            mapped_at_creation: false,
        });
        let av = a.create_view(&Default::default());
        let bv = b.create_view(&Default::default());
        let group = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None, layout: &self.pipeline.get_bind_group_layout(0),
            entries: &[
                wgpu::BindGroupEntry {binding:0,resource:wgpu::BindingResource::TextureView(&av)},
                wgpu::BindGroupEntry {binding:1,resource:wgpu::BindingResource::TextureView(&bv)},
                wgpu::BindGroupEntry {binding:2,resource:input.as_entire_binding()},
                wgpu::BindGroupEntry {binding:3,resource:output.as_entire_binding()},
            ],
        });
        {
            let mut pass = encoder.begin_compute_pass(&Default::default());
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0,&group,&[]);
            pass.dispatch_workgroups(candidates.len() as u32,1,1);
        }
        Ok(output)
    }
}

const WGSL: &str = r#"
struct Candidate { x:i32,y:i32,dx:i32,dy:i32,w:i32,h:i32,reverse:i32,pad:i32 };
@group(0) @binding(0) var a: texture_2d<f32>;
@group(0) @binding(1) var b: texture_2d<f32>;
@group(0) @binding(2) var<storage,read> candidates: array<Candidate>;
@group(0) @binding(3) var<storage,read_write> costs: array<vec4u>;
var<workgroup> partial: array<vec3u,64>;

fn pixel(tex: texture_2d<f32>, p:vec2i) -> i32 {
    return i32(round(textureLoad(tex,p,0).x*255.0));
}
fn diff(p:vec2i,q:vec2i,reverse:bool) -> u32 {
    if reverse { return u32(abs(pixel(b,p)-pixel(a,q))); }
    return u32(abs(pixel(a,p)-pixel(b,q)));
}
@compute @workgroup_size(64)
fn cost(@builtin(workgroup_id) group:vec3u,@builtin(local_invocation_index) lane:u32) {
    let c=candidates[group.x];
    let size=vec2i(textureDimensions(a));
    let height=size.y*2/3;
    let src=vec2i(c.x,c.y);
    let dst=src+vec2i(c.dx,c.dy);
    var sum=vec3u(0);
    for(var i=lane;i<u32(c.w*c.h);i+=64u){
        let offset=vec2i(i32(i)%c.w,i32(i)/c.w);
        sum.x+=diff(src+offset,dst+offset,c.reverse!=0);
    }
    let chroma_width=c.w/2;
    for(var i=lane;i<u32(chroma_width*(c.h/2));i+=64u){
        let offset=vec2i(i32(i)%chroma_width,i32(i)/chroma_width);
        // Validated nonnegative coordinates: division rounds toward zero,
        // which equals floor for both source and reference coordinates.
        let p=src/2+offset+vec2i(0,height);
        let q=dst/2+offset+vec2i(0,height);
        sum.y+=diff(p,q,c.reverse!=0);
        sum.z+=diff(p+vec2i(size.x/2,0),q+vec2i(size.x/2,0),c.reverse!=0);
    }
    partial[lane]=sum;
    workgroupBarrier();
    for(var stride=32u;stride>0u;stride/=2u){
        if lane<stride { partial[lane]+=partial[lane+stride]; }
        workgroupBarrier();
    }
    if lane==0u {
        let s=partial[0];
        costs[group.x]=vec4u(s,s.x+s.y+s.z);
    }
}
"#;

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_metric_and_invalid_geometry() {
        let (w,h)=(64,64);
        let a=vec![0;w*h*3/2];
        let b=vec![255;a.len()];
        let c=Candidate{x:16,y:16,dx:-3,dy:5,width:32,height:16,reverse:false};
        assert_eq!(reference_cost(&a,&b,w as u32,h as u32,c),Some(Cost{y:32*16*255,u:8*16*255,v:8*16*255}));
        assert_eq!(reference_cost(&a,&a,w as u32,h as u32,c),Some(Cost{y:0,u:0,v:0}));
        assert!(reference_cost(&a,&b,w as u32,h as u32,Candidate{dx:-17,..c}).is_none());
        assert!(reference_cost(&a,&b,w as u32,h as u32,Candidate{x:15,..c}).is_none());
    }
}
