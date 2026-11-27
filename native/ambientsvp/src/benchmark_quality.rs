// Exact synthetic 4-pixel translation per source frame. No external
// ground-truth clip, decoder timing or scene labels are required.
pub(crate) fn translation_pixel(w:u32,x:u32,y:u32,shift:u64)->u8{
    let x=((u64::from(x)+shift)%u64::from(w)) as u32;
    let triangle=|v:u32,period:u32|{let r=v%period;r.min(period-r)};
    let a=triangle(x*3+y*2,62);
    let b=triangle(x*7+y*5,94);
    let c=triangle(x*13+y*11,46);
    (24+a*3+b*2+c).min(235) as u8
}

#[derive(Default)]
pub(crate) struct Quality{
    pub(crate) frames:u64,
    pub(crate) pixels:u64,
    pub(crate) squared:u128,
    pub(crate) absolute:u128,
    pub(crate) nearest_squared:u128,
}
impl Quality{
    pub(crate) fn observe(&mut self,out:&[u8],w:u32,h:u32,index:u64,count:u32){
        self.observe_with(out,w,h,index,count,|x,y,t|translation_pixel(w,x,y,t*2));
    }

    /// The generator receives time in half-source-frame units, so a
    /// synthetic clip can specify an exact image at every output tick.
    pub(crate) fn observe_with(&mut self,out:&[u8],w:u32,h:u32,index:u64,count:u32,
        sample:impl Fn(u32,u32,u64)->u8){
        // Measure only genuine halfway positions, before the final
        // source frame. Exclude the frame edges where motion is clipped.
        if index%2==0 || count<2 || index>=2*u64::from(count-1){return;}
        let (mut before,mut after)=(0u128,0u128);
        for y in 16..h-16{
            for x in 16..w-16{
                let target=i32::from(sample(x,y,index));
                let actual=i32::from(out[(y*w+x) as usize]);
                let d=(actual-target).unsigned_abs() as u128;
                self.squared+=d*d;
                self.absolute+=d;
                let b=(i32::from(sample(x,y,index-1))-target).unsigned_abs() as u128;
                let a=(i32::from(sample(x,y,index+1))-target).unsigned_abs() as u128;
                before+=b*b;after+=a*a;
                self.pixels+=1;
            }
        }
        self.nearest_squared+=before.min(after);
        self.frames+=1;
    }

    pub(crate) fn psnr(&self,squared:u128)->f64{
        if self.pixels==0{return f64::NAN;}
        if squared==0{return f64::INFINITY;}
        10.0*(255.0f64.powi(2)*self.pixels as f64/squared as f64).log10()
    }
}


#[cfg(test)]
mod tests{
    use super::*;
    #[test]
    fn exact_halfway_translation_has_zero_error_and_skips_source_positions(){
        let (w,h)=(96,66);
        let mut out=vec![128;(w*h*3/2) as usize];
        let mut q=Quality::default();
        for y in 0..h{for x in 0..w{out[(y*w+x) as usize]=translation_pixel(w,x,y,2);}}
        q.observe(&out,w,h,0,3);
        q.observe(&out,w,h,1,3);
        q.observe(&out,w,h,5,3);
        assert_eq!(q.frames,1);
        assert_eq!(q.pixels,u64::from((w-32)*(h-32)));
        assert_eq!(q.squared,0);
        assert_eq!(q.absolute,0);
        assert!(q.psnr(q.squared).is_infinite());
        assert!(q.nearest_squared>0);
        assert!(q.psnr(q.nearest_squared).is_finite());
    }
}
