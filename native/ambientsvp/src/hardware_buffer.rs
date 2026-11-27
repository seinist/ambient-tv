//! CPU-mappable Android hardware-buffer bridge. This is deliberately not
//! a Vulkan external-memory import: the pinned motion analyser still needs
//! exact I420 host bytes. No opaque MediaCodec surface is treated as an AHB.
use std::ffi::c_void;

#[repr(C)]
#[derive(Clone,Copy)]
struct Plane { data:*mut c_void, pixel_stride:u32, row_stride:u32 }

#[derive(Clone,Copy)]
struct Region { offset:usize, pixel:usize, row:usize, w:usize, h:usize }

fn regions(planes:&[Plane;3], bw:u32,bh:u32,x:u32,y:u32,w:u32,h:u32)->Option<[Region;3]>{
    if w==0 || h==0 || (bw|bh|x|y|w|h)&1!=0
        || bw>16384 || bh>16384 || x.checked_add(w)?>bw || y.checked_add(h)?>bh{return None;}
    let mut result=[Region{offset:0,pixel:0,row:0,w:0,h:0};3];
    for (i,p) in planes.iter().enumerate(){
        let shift=usize::from(i!=0);
        let (pw,ph)=((w>>shift) as usize,(h>>shift) as usize);
        let (px,py)=((x>>shift) as usize,(y>>shift) as usize);
        let pixel=p.pixel_stride as usize;
        let row=p.row_stride as usize;
        if p.data.is_null() || !matches!(pixel,1|2) || i==0 && pixel!=1{return None;}
        let full_row=((bw>>shift) as usize-1).checked_mul(pixel)?.checked_add(1)?;
        if row<full_row{return None;}
        let offset=py.checked_mul(row)?.checked_add(px.checked_mul(pixel)?)?;
        let end=offset.checked_add((ph-1).checked_mul(row)?)?.checked_add((pw-1).checked_mul(pixel)?)?.checked_add(1)?;
        if end>isize::MAX as usize || (p.data as usize).checked_add(end).is_none(){return None;}
        result[i]=Region{offset,pixel,row,w:pw,h:ph};
    }
    Some(result)
}

// SAFETY: all plane addresses are valid for their described extents for
// the duration of this call; NDK lockPlanes guarantees the backing memory.
// Do not create simultaneous mutable slices of interleaved U/V planes.
unsafe fn transfer(planes:&[Plane;3],regions:[Region;3],packed:*mut u8,len:usize,write:bool)->bool{
    if regions.iter().map(|r|r.w*r.h).sum::<usize>()!=len{return false;}
    let mut offset=0;
    for (p,r) in planes.iter().zip(regions){
        for y in 0..r.h{
            let hw=unsafe{p.data.cast::<u8>().add(r.offset+y*r.row)};
            let sw=unsafe{packed.add(offset+y*r.w)};
            if r.pixel==1{
                if write{unsafe{std::ptr::copy_nonoverlapping(sw,hw,r.w)};}
                else{unsafe{std::ptr::copy_nonoverlapping(hw,sw,r.w)};}
            }else{
                for x in 0..r.w{
                    if write{unsafe{*hw.add(x*r.pixel)=*sw.add(x)};}
                    else{unsafe{*sw.add(x)=*hw.add(x*r.pixel)};}
                }
            }
        }
        offset+=r.w*r.h;
    }
    true
}

#[cfg(target_os="android")]
mod android{
    use super::*;
    use crate::{AmbientSvp,ambient_svp_enabled};
    use std::{panic::{catch_unwind,AssertUnwindSafe},ptr};
    const FORMAT_YUV420:u32=0x23;
    const READ_MASK:u64=0xf;
    const WRITE_MASK:u64=0xf0;
    const PROTECTED:u64=1<<14;
    const UNAVAILABLE:i32=-4;
    #[repr(C)]
    struct Desc{width:u32,height:u32,layers:u32,format:u32,usage:u64,stride:u32,rfu0:u32,rfu1:u64}
    #[repr(C)]
    struct Planes{count:u32,planes:[Plane;4]}
    #[link(name="android")]
    unsafe extern "C"{
        fn AHardwareBuffer_acquire(buffer:*mut c_void);
        fn AHardwareBuffer_release(buffer:*mut c_void);
        fn AHardwareBuffer_describe(buffer:*const c_void,desc:*mut Desc);
        fn AHardwareBuffer_lockPlanes(buffer:*mut c_void,usage:u64,fence:i32,rect:*const c_void,planes:*mut Planes)->i32;
        fn AHardwareBuffer_unlock(buffer:*mut c_void,fence:*mut i32)->i32;
    }
    struct Buffer(*mut c_void);
    impl Drop for Buffer{fn drop(&mut self){unsafe{AHardwareBuffer_release(self.0)}}}
    struct Lock{buffer:Buffer,planes:[Plane;3],regions:[Region;3],locked:bool}
    impl Drop for Lock{
        fn drop(&mut self){if self.locked{unsafe{AHardwareBuffer_unlock(self.buffer.0,ptr::null_mut());}}}
    }
    impl Lock{
        unsafe fn new(buffer:*mut c_void,fence:i32,x:u32,y:u32,w:u32,h:u32,write:bool)->Option<Self>{
            if buffer.is_null() || fence< -1{return None;}
            // A borrowed fence must be signaled before locking. Never
            // consume/close caller FDs or wait indefinitely on a producer.
            if fence>=0{
                let mut p=libc::pollfd{fd:fence,events:libc::POLLIN,revents:0};
                if unsafe{libc::poll(&mut p,1,0)}!=1 || p.revents!=libc::POLLIN{return None;}
            }
            unsafe{AHardwareBuffer_acquire(buffer)};
            let buffer=Buffer(buffer);
            let mut d:Desc=unsafe{std::mem::zeroed()};
            unsafe{AHardwareBuffer_describe(buffer.0,&mut d)};
            let usage=d.usage & if write{WRITE_MASK}else{READ_MASK};
            if d.layers!=1 || d.format!=FORMAT_YUV420 || d.usage&PROTECTED!=0
                || !matches!(usage,2|3|0x20|0x30)
                || (x|y|w|h|d.width|d.height)&1!=0
                || x.checked_add(w)?>d.width || y.checked_add(h)?>d.height{return None;}
            let mut p:Planes=unsafe{std::mem::zeroed()};
            if unsafe{AHardwareBuffer_lockPlanes(buffer.0,usage,-1,ptr::null(),&mut p)}!=0{return None;}
            // Construct a guard immediately, including invalid plane count.
            let mut lock=Self{buffer,planes:[p.planes[0],p.planes[1],p.planes[2]],
                regions:[Region{offset:0,pixel:0,row:0,w:0,h:0};3],locked:true};
            if p.count!=3{return None;}
            lock.regions=regions(&lock.planes,d.width,d.height,x,y,w,h)?;
            Some(lock)
        }
        fn unlock(&mut self,fence:*mut i32)->bool{
            self.locked=false;
            unsafe{AHardwareBuffer_unlock(self.buffer.0,fence)==0}
        }
    }
    /// Synchronously copy CPU-mappable decoder hardware planes directly
    /// into the engine-owned I420 allocation, before motion analysis.
    #[unsafe(no_mangle)]
    pub unsafe extern "C" fn ambient_svp_push_ahardwarebuffer(s:*mut AmbientSvp,buffer:*mut c_void,
        x:u32,y:u32,acquire_fence:i32,pts:i64)->i32{
        if s.is_null(){return -1;}
        catch_unwind(AssertUnwindSafe(||{
            let s=unsafe{&mut *s};
            if ambient_svp_enabled()==0{s.bypass(2);}
            let (w,h)=(s.config.width,s.config.height);
            s.push_filled(pts,|dst|{
                let mut lock=unsafe{Lock::new(buffer,acquire_fence,x,y,w,h,false)}.ok_or(UNAVAILABLE)?;
                let ok=unsafe{transfer(&lock.planes,lock.regions,dst.as_mut_ptr(),dst.len(),false)};
                if !lock.unlock(ptr::null_mut()) || !ok{return Err(UNAVAILABLE);}
                Ok(())
            }).map_or_else(|e|e,|_|0)
        })).unwrap_or(-1)
    }
    /// Write synthesis output into a caller-owned CPU-writable hardware
    /// buffer. Acquire readiness/lock before consuming any output.
    #[unsafe(no_mangle)]
    pub unsafe extern "C" fn ambient_svp_receive_ahardwarebuffer(s:*mut AmbientSvp,buffer:*mut c_void,
        x:u32,y:u32,acquire_fence:i32,release_fence:*mut i32,pts:*mut i64,eof:i32)->i32{
        if s.is_null() || pts.is_null(){return -1;}
        if !release_fence.is_null(){unsafe{*release_fence=-1;}}
        catch_unwind(AssertUnwindSafe(||{
            let s=unsafe{&mut *s};
            if ambient_svp_enabled()==0{s.bypass(2);}
            if !s.ready(eof!=0){return 0;}
            let mut lock=match unsafe{Lock::new(buffer,acquire_fence,x,y,s.config.width,s.config.height,true)}{
                Some(lock)=>lock,None=>return UNAVAILABLE
            };
            let mut copied=false;
            let result=s.receive_visit(|data|{
                copied=unsafe{transfer(&lock.planes,lock.regions,data.as_ptr().cast_mut(),data.len(),true)};
            },eof!=0);
            let unlocked=lock.unlock(release_fence);
            match result{
                Ok(Some(p)) if copied && unlocked=>{unsafe{*pts=p;}1}
                Ok(None) if unlocked=>0,
                // Output may have been consumed. Do not retry the same tick.
                _=>{s.bypass(3);-1}
            }
        })).unwrap_or(-1)
    }
}

#[cfg(test)]
mod tests{
    use super::*;
    #[test]
    fn padded_interleaved_cropped_yuv_roundtrip(){
        let (bw,bh)=(12,8);
        let mut y=vec![0xa5u8;16*8];
        let mut uv=vec![0xa5u8;16*4];
        let planes=[Plane{data:y.as_mut_ptr().cast(),pixel_stride:1,row_stride:16},
            Plane{data:uv.as_mut_ptr().cast(),pixel_stride:2,row_stride:16},
            Plane{data:unsafe{uv.as_mut_ptr().add(1)}.cast(),pixel_stride:2,row_stride:16}];
        let layout=regions(&planes,bw,bh,2,2,8,4).unwrap();
        let mut input=(0..48u8).collect::<Vec<_>>();
        let mut output=vec![0;48];
        assert!(unsafe{transfer(&planes,layout,input.as_mut_ptr(),48,true)});
        assert!(unsafe{transfer(&planes,layout,output.as_mut_ptr(),48,false)});
        assert_eq!(input,output);
        assert_eq!(y[0],0xa5);
        assert_eq!(y[2*16+1],0xa5);
        assert_eq!(uv[16+1],0xa5);
        assert_eq!(&uv[18..22],&[32,40,33,41]);
        assert!(regions(&planes,bw,bh,3,2,8,4).is_none());
        assert!(regions(&planes,bw,bh,8,2,8,4).is_none());
        let mut bad=planes;bad[2].row_stride=2;
        assert!(regions(&bad,bw,bh,2,2,8,4).is_none());
        assert!(!unsafe{transfer(&planes,layout,output.as_mut_ptr(),47,false)});
    }
}
