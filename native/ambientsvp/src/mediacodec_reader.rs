//! NDK MediaCodec Surface producer. AImageReader owns the buffer queue;
//! each acquired AImage is returned exactly once, with its producer or
//! GPU release fence. A reader cannot be deleted while a lease exists.
use std::{ffi::c_void,ptr,sync::Arc,panic::{catch_unwind,AssertUnwindSafe}};
use jni_sys::{JNIEnv,jobject};

const PRIVATE:i32=0x22;
const GPU_SAMPLED_IMAGE:u64=1<<8;
const MAX_IMAGES:i32=4;

#[repr(C)]
#[derive(Clone,Copy,Debug,Default)]
pub(crate) struct Crop{pub left:i32,pub top:i32,pub right:i32,pub bottom:i32}
#[repr(C)]
#[derive(Clone,Copy,Debug,Default)]
pub struct FrameInfo{
    pub timestamp_ns:i64,
    pub crop_left:u32,pub crop_top:u32,pub width:u32,pub height:u32,
    pub buffer_width:u32,pub buffer_height:u32,
}
#[repr(C)]
#[derive(Clone,Copy,Default)]
pub(crate) struct Desc{
    pub width:u32,pub height:u32,pub layers:u32,pub format:u32,pub usage:u64,
    pub stride:u32,pub rfu0:u32,pub rfu1:u64,
}
#[link(name="mediandk")]
unsafe extern "C"{
    fn AImageReader_newWithUsage(w:i32,h:i32,format:i32,usage:u64,max_images:i32,out:*mut *mut c_void)->i32;
    fn AImageReader_getWindow(reader:*mut c_void,out:*mut *mut c_void)->i32;
    fn AImageReader_acquireNextImageAsync(reader:*mut c_void,out:*mut *mut c_void,fence:*mut i32)->i32;
    fn AImageReader_delete(reader:*mut c_void);
    fn AImage_getHardwareBuffer(image:*const c_void,out:*mut *mut c_void)->i32;
    fn AImage_getCropRect(image:*const c_void,crop:*mut Crop)->i32;
    fn AImage_getTimestamp(image:*const c_void,timestamp:*mut i64)->i32;
    fn AImage_getFormat(image:*const c_void,format:*mut i32)->i32;
    fn AImage_deleteAsync(image:*mut c_void,release_fence:i32);
}
#[link(name="android")]
unsafe extern "C"{
    fn ANativeWindow_toSurface(env:*mut JNIEnv,window:*mut c_void)->jobject;
    pub(crate) fn AHardwareBuffer_describe(buffer:*const c_void,desc:*mut Desc);
    pub(crate) fn AHardwareBuffer_acquire(buffer:*mut c_void);
    pub(crate) fn AHardwareBuffer_release(buffer:*mut c_void);
}

struct ReaderInner{reader:*mut c_void,window:*mut c_void,allow_p010:bool}
impl Drop for ReaderInner{
    fn drop(&mut self){unsafe{AImageReader_delete(self.reader)}}
}
pub struct Reader(Arc<ReaderInner>);
pub struct Frame{
    // Keep parent AImageReader alive: deleting it would invalidate AImage.
    _reader:Option<Arc<ReaderInner>>,
    external:Option<ExternalLease>,
    image:*mut c_void,
    pub(crate) buffer:*mut c_void,
    pub(crate) acquire_fd:i32,
    pub(crate) info:FrameInfo,
}
type ReleaseSource=unsafe extern "C" fn(*mut c_void,i32);
struct ExternalLease{context:*mut c_void,release:ReleaseSource,buffer:*mut c_void}
impl Drop for ExternalLease{
    fn drop(&mut self){
        unsafe{AHardwareBuffer_release(self.buffer);}
    }
}
impl Frame{
    pub(crate) fn external_pending(&self)->bool{self.external.is_some()}
    /// Adopt an additional reference to a decoder picture via its owner
    /// callback, not the AImage itself. The caller transfers the token
    /// even if validation fails. A duplicate producer FD belongs to us.
    pub(crate) unsafe fn borrowed(buffer:*mut c_void,fd:i32,crop:Crop,timestamp:i64,
        context:*mut c_void,release:ReleaseSource)->Option<Self>{
        let reject=||{unsafe{release(context,-1)};None};
        if buffer.is_null() || fd< -1{return reject();}
        let mut d=Desc::default();
        unsafe{AHardwareBuffer_describe(buffer,&mut d);}
        let Some(info)=checked_info(crop,d,PRIVATE,timestamp)else{return reject();};
        let acquire_fd=if fd>=0{
            let dup=unsafe{libc::fcntl(fd,libc::F_DUPFD_CLOEXEC,0)};
            if dup<0{return reject();}dup
        }else{-1};
        unsafe{AHardwareBuffer_acquire(buffer);}
        Some(Self{_reader:None,external:Some(ExternalLease{context,release,buffer}),
            image:ptr::null_mut(),buffer,acquire_fd,info})
    }
    // Called only after successful GPU submission and release-fence export,
    // or after completion when export is unavailable. Transfer the FD to NDK.
    pub(crate) fn return_after_gpu(&mut self,fd:i32){
        if let Some(lease)=self.external.take(){
            if self.acquire_fd>=0{unsafe{libc::close(self.acquire_fd)};}
            self.acquire_fd=-1;self.buffer=ptr::null_mut();
            unsafe{(lease.release)(lease.context,fd)};return;
        }
        if self.image.is_null(){if fd>=0{unsafe{libc::close(fd)};}return;}
        if self.acquire_fd>=0{unsafe{libc::close(self.acquire_fd)};}
        self.acquire_fd=-1;
        unsafe{AImage_deleteAsync(self.image,fd)};
        self.image=ptr::null_mut();
        self.buffer=ptr::null_mut();
    }
}
impl Drop for Frame{
    fn drop(&mut self){
        if let Some(lease)=self.external.take(){
            unsafe{(lease.release)(lease.context,self.acquire_fd.max(-1))};
            self.acquire_fd=-1;self.buffer=ptr::null_mut();
        }
        if !self.image.is_null(){
            // No GPU use committed. Return the original producer fence,
            // not -1, so a dropped image cannot be reused prematurely.
            unsafe{AImage_deleteAsync(self.image,self.acquire_fd.max(-1))};
            self.image=ptr::null_mut();self.acquire_fd=-1;
        }
    }
}

fn checked_info(crop:Crop,d:Desc,image_format:i32,timestamp_ns:i64)->Option<FrameInfo>{
    checked_info_format(crop,d,image_format,timestamp_ns,false)
}
fn checked_info_format(crop:Crop,d:Desc,image_format:i32,timestamp_ns:i64,allow_p010:bool)->Option<FrameInfo>{
    let explicit_p010=allow_p010 && d.format==0x36 && matches!(image_format,PRIVATE|0x36);
    if crop.left<0 || crop.top<0 || crop.right<=crop.left || crop.bottom<=crop.top
        || crop.right as u32>d.width || crop.bottom as u32>d.height
        || d.layers!=1 || d.usage&GPU_SAMPLED_IMAGE==0 || d.usage&(1<<14)!=0
        // PRIVATE AImages may expose an implementation-defined gralloc
        // format. Vulkan queries the true format; never infer its layout.
        || (!explicit_p010 && (!matches!(image_format,PRIVATE|0x23)
        || !(matches!(d.format,0x22|0x23) || image_format==PRIVATE && d.format>=0x100)))
    {return None;}
    let (width,height)=((crop.right-crop.left) as u32,(crop.bottom-crop.top) as u32);
    if width<32 || height<32 || width>3840 || height>2160
        || (width|height|crop.left as u32|crop.top as u32)&1!=0
        || u64::from(width)*u64::from(height)>1920*1080 || timestamp_ns<0
    {return None;}
    Some(FrameInfo{timestamp_ns,crop_left:crop.left as u32,crop_top:crop.top as u32,
        width,height,buffer_width:d.width,buffer_height:d.height})
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_mediacodec_reader_create(width:u32,height:u32)->*mut Reader{
    unsafe{ambient_svp_mediacodec_reader_create_format(width,height,0)}
}
// Mode 1 keeps the PRIVATE Surface but accepts explicitly identified P010
// leases. Vendor-private formats still need importer-side proof of depth.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_mediacodec_reader_create_format(width:u32,height:u32,mode:u32)->*mut Reader{
    if mode>1{return ptr::null_mut();}
    if width<32 || height<32 || width>3840 || height>2160 || (width|height)&1!=0
        || u64::from(width)*u64::from(height)>1920*1080{return ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||{
        let mut reader=ptr::null_mut();
        if unsafe{AImageReader_newWithUsage(width as i32,height as i32,PRIVATE,GPU_SAMPLED_IMAGE,MAX_IMAGES,&mut reader)}!=0
            || reader.is_null(){return ptr::null_mut();}
        let mut window=ptr::null_mut();
        if unsafe{AImageReader_getWindow(reader,&mut window)}!=0 || window.is_null(){
            unsafe{AImageReader_delete(reader)};return ptr::null_mut();
        }
        Box::into_raw(Box::new(Reader(Arc::new(ReaderInner{reader,window,allow_p010:mode==1}))))
    })).unwrap_or(ptr::null_mut())
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_mediacodec_reader_destroy(reader:*mut Reader){
    if !reader.is_null(){unsafe{drop(Box::from_raw(reader))}}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_mediacodec_reader_window(reader:*const Reader)->*mut c_void{
    if reader.is_null(){return ptr::null_mut();}
    unsafe{(&*reader).0.window}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_mediacodec_reader_surface(reader:*const Reader,env:*mut JNIEnv)->jobject{
    if reader.is_null() || env.is_null(){return ptr::null_mut();}
    unsafe{ANativeWindow_toSurface(env,(&*reader).0.window)}
}
/// 1 frame, 0 queue empty, -1 unsupported/error, -2 backpressure.
/// No waiting, no CPU map, no acquireLatestImage dropping older frames.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_mediacodec_acquire(reader:*const Reader,out:*mut *mut Frame)->i32{
    if reader.is_null() || out.is_null(){return -1;}
    unsafe{*out=ptr::null_mut();}
    catch_unwind(AssertUnwindSafe(||{
        let r=unsafe{(&*reader).0.clone()};
        let mut image=ptr::null_mut();
        let mut fence=-1;
        let status=unsafe{AImageReader_acquireNextImageAsync(r.reader,&mut image,&mut fence)};
        if status!=0{
            // NDK image-reader status values: -30001 no buffer, -30002 max acquired.
            if !image.is_null(){unsafe{AImage_deleteAsync(image,fence)};}
            else if fence>=0{unsafe{libc::close(fence)};}
            return match status{-30001=>0,-30002=>-2,_=>-1};
        }
        if image.is_null(){if fence>=0{unsafe{libc::close(fence)};}return -1;}
        let allow_p010=r.allow_p010;
        let mut frame=Frame{_reader:Some(r),external:None,image,buffer:ptr::null_mut(),acquire_fd:fence,info:FrameInfo::default()};
        let mut crop=Crop::default();
        let mut timestamp=0;
        let mut format=0;
        if fence< -1 || unsafe{AImage_getHardwareBuffer(image,&mut frame.buffer)}!=0
            || frame.buffer.is_null() || unsafe{AImage_getCropRect(image,&mut crop)}!=0
            || unsafe{AImage_getTimestamp(image,&mut timestamp)}!=0
            || unsafe{AImage_getFormat(image,&mut format)}!=0{return -1;}
        let mut desc=Desc::default();
        unsafe{AHardwareBuffer_describe(frame.buffer,&mut desc)};
        let Some(info)=checked_info_format(crop,desc,format,timestamp,allow_p010)else{return -1;};
        frame.info=info;
        unsafe{*out=Box::into_raw(Box::new(frame));}
        1
    })).unwrap_or(-1)
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_mediacodec_frame_info(frame:*const Frame,out:*mut FrameInfo)->i32{
    if frame.is_null() || out.is_null(){return -1;}
    unsafe{*out=(*frame).info;}0
}
/// Only for an unsubmitted lease. The GPU submission API consumes the
/// handle on success, nulling the caller's slot.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn ambient_svp_mediacodec_frame_discard(frame:*mut Frame){
    if !frame.is_null(){unsafe{drop(Box::from_raw(frame))}}
}

#[cfg(test)]
mod tests{
    use super::*;
    #[test]
    fn rejects_protected_and_invalid_crop_without_misreading_padding(){
        let d=Desc{width:128,height:80,layers:1,format:PRIVATE as u32,usage:GPU_SAMPLED_IMAGE,..Default::default()};
        let c=Crop{left:2,top:4,right:98,bottom:70};
        let f=checked_info(c,d,PRIVATE,10).unwrap();
        assert_eq!((f.width,f.height,f.buffer_width,f.buffer_height),(96,66,128,80));
        assert!(checked_info(c,Desc{usage:d.usage|(1<<14),..d},PRIVATE,10).is_none());
        assert!(checked_info(Crop{left:1,..c},d,PRIVATE,10).is_none());
        assert!(checked_info(Crop{right:130,..c},d,PRIVATE,10).is_none());
        assert!(checked_info(c,Desc{format:0x36,..d},PRIVATE,10).is_none());
        assert!(checked_info_format(c,Desc{format:0x36,..d},PRIVATE,10,true).is_some());
        assert!(checked_info_format(c,Desc{format:0x36,..d},0x36,10,true).is_some());
        assert!(checked_info_format(c,d,0x36,10,true).is_none());
        assert!(checked_info(c,Desc{format:0x7fa30c04,..d},PRIVATE,10).is_some());
        assert!(checked_info(c,d,0x24,10).is_none());
    }
}
