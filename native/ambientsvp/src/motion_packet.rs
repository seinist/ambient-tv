//! Pack fully prepared level-zero motion fields for the pinned upstream
//! Smoother. This is a format bridge, not an independent search algorithm.
//! Keep the exact upstream header and signed vector packing (including
//! carry from negative vertical components).
use crate::motion_select::Choice;
use svpflow_core::metadata::{self,VectorData,VectorRecord};

#[derive(Clone, Copy, Debug)]
pub struct FinalVector {
    pub choice: Choice,
    /// Exact block luma from the same upstream search stage.
    pub luma: u8,
}

pub struct PacketLayout {
    header: [i32;15],
    data: VectorData,
    count: usize,
    diagonal: i32,
}
impl PacketLayout {
    pub fn from_header(header:&[i32])->Result<Self,String>{
        // Only full-resolution, bidirectional or backward-only fields.
        // GPU mode 2 uses reduced-level marker counts and is deliberately
        // unsupported here. No fabricated zero-padding for missing blocks.
        if header.len()!=16 || header[0]!=0xa0 || !matches!(header[1],2|3)
            || !matches!(header[2],8|16|32) || !matches!(header[3],8|16|32)
            || !matches!(header[4],1|2|4) || header[5]<1 || header[6]!=0
            || !(32..=3840).contains(&header[7]) || !(32..=2160).contains(&header[8])
            || header[7]%2!=0 || header[8]%2!=0
            || !(0..header[2]).contains(&header[9]) || !(0..header[3]).contains(&header[10])
            || header[11]<=0 || header[12]<=0 || !matches!(header[13],0|1)
            || header[14]!=1 || header[15]!=0
        {return Err("unsupported SVP vector header".into());}
        let (w,h)=(header[7],header[8]);
        let (bw,bh)=(header[2],header[3]);
        let (sx,sy)=(bw-header[9],bh-header[10]);
        if i64::from(header[11]-1)*i64::from(sx)+i64::from(bw)>i64::from(w)
            || i64::from(header[12]-1)*i64::from(sy)+i64::from(bh)>i64::from(h)
        {return Err("vector grid exceeds source".into());}
        let count=(header[11] as usize).checked_mul(header[12] as usize)
            .filter(|&n|n<=262144).ok_or("vector field exceeds budget")?;
        let bytes:Vec<u8>=header.iter().flat_map(|x|x.to_le_bytes()).collect();
        let VectorRecord::Ready(data)=metadata::vector_data(&bytes)else{return Err("invalid upstream header".into());};
        let diagonal=header[4]*(f64::from(w*w+h*h).sqrt() as i32);
        Ok(Self{header:header[..15].try_into().unwrap(),data,count,diagonal})
    }

    pub fn count(&self)->usize{self.count}

    /// Apply the upstream packed_score distance weighting. Do not use
    /// the penalized selection cost as the SAD score consumed by scene
    /// classification and interpolation.
    fn score(&self,v:FinalVector)->Result<u32,String>{
        let c=v.choice;
        if i16::try_from(c.dx).is_err() || i16::try_from(c.dy).is_err() || c.sad<0{
            return Err("invalid winning motion vector".into());
        }
        let relative=(c.dx.abs()+c.dy.abs())*100/self.diagonal;
        let sad=if relative>50{
            c.sad.wrapping_mul(20)
        }else if relative>15{
            c.sad.wrapping_add((c.sad.wrapping_mul(relative-15)/35).wrapping_mul(9))
        }else{c.sad};
        Ok((sad as u32)&0x00ff_ffff)
    }

    /// The two regions must already be in the upstream raster order:
    /// previous (next -> current), then current (current -> next).
    /// The complete payload is validated before it can enter the smoother.
    pub fn pack(&self,previous:Option<&[FinalVector]>,current:&[FinalVector])->Result<Vec<u8>,String>{
        if current.len()!=self.count || match self.data.flags{
            3=>previous.is_none_or(|v|v.len()!=self.count),
            2=>previous.is_some(),
            _=>true,
        }{return Err("incomplete or unexpected vector regions".into());}
        let convert=|vectors:&[FinalVector]|vectors.iter().map(|&v|{
            Ok(metadata::DecodedVector{dx:v.choice.dx as i16,dy:v.choice.dy as i16,
                score:self.score(v)?,luma:v.luma})
        }).collect::<Result<Vec<_>,String>>();
        let current=convert(current)?;
        let previous=previous.map(convert).transpose()?;
        // Use the upstream encoder for exact signed component/marker rules,
        // then restore all header words (the encoder omits pyramid levels).
        let mut payload=metadata::encode_vector_payload(self.data,previous.as_deref(),&current);
        for (out,word) in payload[4..64].chunks_exact_mut(4).zip(self.header){
            out.copy_from_slice(&word.to_le_bytes());
        }
        Ok(payload)
    }
}

#[cfg(test)]
mod tests{
    use super::*;
    use svpflow1_vs::{Analyser,SuperBuilder};
    fn vector(dx:i32,dy:i32,sad:i32,luma:u8)->FinalVector{
        FinalVector{choice:Choice{dx,dy,sad,cost:123456,raw_sad:-1,winner:0},luma}
    }
    #[test]
    fn exact_static_packet_matches_real_upstream_analyser(){
        for pel in [1,2,4]{
            let builder=SuperBuilder::new(96,66,pel).unwrap();
            let analyser=Analyser::with_options(&builder,"{block:{w:16,h:16,overlap:2},vectors:3,fast:1}").unwrap();
            let header=analyser.vector_header();
            let layout=PacketLayout::from_header(&header).unwrap();
            let raw=vec![109;builder.source_len()];
            let frame=builder.build(&raw).unwrap();
            let actual=analyser.analyse(&frame,&frame).unwrap();
            let vectors=vec![vector(0,0,0,109);layout.count()];
            let packet=layout.pack(Some(&vectors),&vectors).unwrap();
            assert_eq!(packet,actual,"static pel={pel}");
            let decoded=metadata::decode_vectors(&packet,layout.data.flags,layout.data.grid,3).unwrap();
            assert!(decoded.previous.unwrap().iter().all(|v|v.dx==0&&v.dy==0&&v.score==0&&v.luma==109));
            assert!(decoded.current.unwrap().iter().all(|v|v.dx==0&&v.dy==0&&v.score==0&&v.luma==109));
            // Exercise the real consumer with the complete packed payload.
            let mut reference=svpflow_wasm::Smoother::new(96,66,24,1,2,
                "{rate:{abs:true,num:60,den:1},algo:13,gpu:false}",&header).unwrap();
            reference.set_vectors(0,&packet);
            for k in 0..2{
                if reference.uses_super(){reference.set_super(k,&frame).unwrap();}
                else{reference.set_source(k,&raw).unwrap();}
            }
            let mut out=vec![0;raw.len()];
            reference.render_into(1,&mut out).unwrap();
            assert_eq!(out,raw);
        }
    }

    #[test]
    fn signed_packing_distance_penalties_and_incomplete_fields(){
        let builder=SuperBuilder::new(96,66,1).unwrap();
        let analyser=Analyser::new(&builder,16,16,2,3).unwrap();
        let layout=PacketLayout::from_header(&analyser.vector_header()).unwrap();
        let mut field=vec![vector(0,0,100,128);layout.count()];
        field[0]=vector(3,-2,100,200);
        field[1]=vector(100,0,100,64);
        let packed=layout.pack(Some(&field),&field).unwrap();
        let first=|offset:usize|u32::from_le_bytes(packed[offset..offset+4].try_into().unwrap());
        assert_eq!(first(68),((3i32<<16).wrapping_add(-2)) as u32);
        assert_eq!(first(72),(200<<24)|100);
        assert_eq!(first(80),(64<<24)|2000);
        assert!(layout.pack(None,&field).is_err());
        assert!(layout.pack(Some(&field[..1]),&field).is_err());
        field[0].choice.dx=i32::MAX;
        assert!(layout.pack(Some(&field),&field).is_err());
        let mut header=analyser.vector_header();
        header[13]=2;
        assert!(PacketLayout::from_header(&header).is_err());
    }
}
