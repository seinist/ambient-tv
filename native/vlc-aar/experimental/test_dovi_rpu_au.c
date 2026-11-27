#include "dovi_rpu_au.h"
#include "dovi_fel_dual.h"
#include <assert.h>
#include <stdlib.h>
static unsigned freed;
static void release(void *m) { free(m); ++freed; }
int main(void) {
    // Ordinary slice, RPU, suffix SEI; both 3- and 4-byte start codes.
    const uint8_t annexb[]={0,0,1,0x26,1,0x80,0,0,0,1,0x7c,1,25,8,0,0,3,1,0x80,0,0,1,0x50,1,0x80};
    struct ambient_rpu_au au;
    assert(ambient_rpu_find(annexb,sizeof annexb,0,&au)==AMBIENT_RPU_FOUND);
    assert(!au.has_nonbase_layer);
    assert(au.has_vcl && au.nal_start==6 && au.nal_end==19 && au.payload_start==12);
    uint8_t rbsp[64]; size_t n;
    assert(ambient_rpu_unescape(annexb+au.payload_start,au.payload_size,rbsp,sizeof rbsp,&n));
    assert(n==6 && rbsp[4]==1);
    const uint8_t prefixed[]={0,0,0,3,0x26,1,0x80,0,0,0,8,0x7c,1,25,8,0,0,3,1};
    assert(ambient_rpu_find(prefixed,sizeof prefixed,4,&au)==AMBIENT_RPU_FOUND);
    assert(au.nal_start==7 && au.nal_end==sizeof prefixed);
    assert(ambient_rpu_find(prefixed,sizeof prefixed-1,4,&au)==AMBIENT_RPU_INVALID);
    assert(ambient_rpu_find(annexb,6,0,&au)==AMBIENT_RPU_NONE);
    assert(ambient_rpu_find(annexb,sizeof annexb,3,&au)==AMBIENT_RPU_INVALID);
    const uint8_t multilayer[]={0,0,1,0x26,9,0x80,0,0,1,0x7e,1,0x80};
    assert(ambient_rpu_find(multilayer,sizeof multilayer,0,&au)==AMBIENT_RPU_NONE);
    assert(au.has_nonbase_layer && !au.has_vcl);
    const uint8_t duplicate[]={0,0,1,0x7c,1,25,8,1,2,3,0,0,1,0x7c,1,25,8,1,2,3};
    assert(ambient_rpu_find(duplicate,sizeof duplicate,0,&au)==AMBIENT_RPU_INVALID);
    const uint8_t invalid_escape[]={25,8,0,0,3,4};
    assert(!ambient_rpu_unescape(invalid_escape,sizeof invalid_escape,rbsp,sizeof rbsp,&n));
    uint8_t combined[]={0,0,1,0x26,1,0x80,0,0,1,0x7e,1,0x02,1,0x90,0,0,1,0x50,1,0x80};
    const uint8_t expected_bl[]={0,0,1,0x26,1,0x80,0,0,1,0x50,1,0x80};
    size_t combined_size=sizeof(combined);
    assert(ambient_fel_strip_el(combined,&combined_size));
    assert(combined_size==sizeof(expected_bl)&&!memcmp(combined,expected_bl,combined_size));
    uint8_t bad[]={0,0,1,0x26,1,0x80,0,0,1,0x7e,1,0x02,0,0x90};
    uint8_t saved[sizeof(bad)];memcpy(saved,bad,sizeof(bad));
    size_t bad_size=sizeof(bad);
    assert(!ambient_fel_strip_el(bad,&bad_size));
    assert(bad_size==sizeof(bad)&&!memcmp(saved,bad,sizeof(bad)));
    // hvcE uses an HEVC configuration record with complete VPS/SPS/PPS arrays.
    uint8_t hvce[47]={0};
    hvce[0]=1;hvce[21]=3;hvce[22]=3;
    for(unsigned i=0;i<3;i++){
        size_t off=23+8*i;
        hvce[off]=0x80|(32+i);hvce[off+2]=1;hvce[off+4]=3;
        hvce[off+5]=(32+i)<<1;hvce[off+6]=1;hvce[off+7]=0x80;
    }
    assert(ambient_fel_hvcc_length(hvce,sizeof(hvce))==4);
    size_t config_size;
    assert(ambient_fel_hvce_prefix(hvce,sizeof(hvce),4,NULL,0,&config_size));
    assert(config_size==27);
    uint8_t config[27];
    assert(ambient_fel_hvce_prefix(hvce,sizeof(hvce),4,config,sizeof(config),&config_size));
    for(unsigned i=0;i<3;i++){
        assert(config[9*i+3]==5&&config[9*i+4]==0x7e&&config[9*i+5]==1);
        assert(config[9*i+6]==((32+i)<<1));
    }
    for(size_t i=0;i<sizeof(hvce);i++)
        assert(!ambient_fel_hvce_prefix(hvce,i,4,NULL,0,&config_size));
    assert(!ambient_fel_hvce_prefix(hvce,sizeof(hvce),4,config,26,&config_size));
    assert(ambient_fel_hvce_prefix(hvce,sizeof(hvce),2,NULL,0,&config_size)&&config_size==21);
    uint8_t sample[]={0,0,0,3,0x26,1,0x80,0,0,0,5,0x7e,1,0x26,1,0x80};
    bool has_el,irap;size_t el_offset;
    assert(ambient_fel_mp4_sample(sample,sizeof(sample),4,&has_el,&irap,&el_offset));
    assert(has_el&&irap&&el_offset==7);
    assert(!ambient_fel_mp4_sample(sample+7,sizeof(sample)-7,4,&has_el,&irap,&el_offset));
    assert(!ambient_fel_mp4_sample(sample,sizeof(sample)-1,4,&has_el,&irap,&el_offset));
    // Dual-track sample: different NAL prefix widths, RPU stays unwrapped.
    const uint8_t bl4[]={0,0,0,3,0x26,1,0x80};
    const uint8_t el2[]={0,3,0x26,1,0x90,0,3,0x7c,1,0xa0};
    const uint8_t merged_expected[]={0,0,0,3,0x26,1,0x80,
        0,0,0,5,0x7e,1,0x26,1,0x90,0,0,0,3,0x7c,1,0xa0};
    uint8_t merged[128];size_t merged_size;
    assert(ambient_fel_dual_sample(bl4,sizeof(bl4),4,el2,sizeof(el2),2,NULL,0,
        false,NULL,0,&merged_size));
    assert(merged_size==sizeof(merged_expected));
    assert(ambient_fel_dual_sample(bl4,sizeof(bl4),4,el2,sizeof(el2),2,NULL,0,
        false,merged,sizeof(merged),&merged_size));
    assert(!memcmp(merged,merged_expected,merged_size));
    assert(ambient_fel_dual_sample(bl4,sizeof(bl4),4,el2,sizeof(el2),2,hvce,sizeof(hvce),
        true,merged,sizeof(merged),&merged_size));
    assert(merged_size==sizeof(merged_expected)+27);
    assert(!memcmp(merged,bl4,sizeof(bl4)));
    assert(!memcmp(merged+sizeof(bl4)+27,merged_expected+sizeof(bl4),
        sizeof(merged_expected)-sizeof(bl4)));
    assert(!ambient_fel_dual_sample(bl4,sizeof(bl4),4,el2,5,2,NULL,0,
        false,NULL,0,&merged_size)); // no RPU
    for(size_t i=0;i<sizeof(el2);i++)
        assert(!ambient_fel_dual_sample(bl4,sizeof(bl4),4,el2,i,2,NULL,0,
            false,NULL,0,&merged_size));
    assert(!ambient_fel_dual_sample(bl4,sizeof(bl4),4,el2,sizeof(el2),2,NULL,0,
        false,merged,20,&merged_size));
    // Sequential offsets, chunk switch, backward/forward seek and overflow.
    const uint32_t sizes[]={3,7,9,11,15,20};
    struct ambient_fel_offset_cursor cursor={0};
    uint64_t offset; uint32_t bytes;
    assert(ambient_fel_sample_offset(&cursor,0,0,4,100,0,6,0,sizes,&offset,&bytes));
    assert(offset==100&&bytes==3&&cursor.sample==1);
    assert(ambient_fel_sample_offset(&cursor,0,0,4,100,1,6,0,sizes,&offset,&bytes));
    assert(offset==103&&bytes==7);
    assert(ambient_fel_sample_offset(&cursor,0,0,4,100,3,6,0,sizes,&offset,&bytes));
    assert(offset==119&&bytes==11);
    assert(ambient_fel_sample_offset(&cursor,0,0,4,100,1,6,0,sizes,&offset,&bytes));
    assert(offset==103&&bytes==7);
    assert(ambient_fel_sample_offset(&cursor,1,4,2,1000,5,6,0,sizes,&offset,&bytes));
    assert(offset==1015&&bytes==20);
    assert(ambient_fel_sample_offset(&cursor,2,0,6,1000,5,6,8,NULL,&offset,&bytes));
    assert(offset==1040&&bytes==8);
    assert(!ambient_fel_sample_offset(&cursor,3,4,3,100,5,6,0,sizes,&offset,&bytes));
    assert(!ambient_fel_sample_offset(&cursor,3,4,2,UINT64_MAX-2,5,6,0,sizes,&offset,&bytes));
    assert(!ambient_fel_sample_offset(&cursor,3,0,6,UINT64_MAX-3,1,6,8,NULL,&offset,&bytes));
    uint8_t wrong_rap[sizeof(el2)];memcpy(wrong_rap,el2,sizeof(el2));wrong_rap[2]=0x02;
    assert(!ambient_fel_dual_sample(bl4,sizeof(bl4),4,wrong_rap,sizeof(wrong_rap),2,
        NULL,0,false,NULL,0,&merged_size));
    wrong_rap[2]=0x26;wrong_rap[4]=0x10;
    assert(!ambient_fel_dual_sample(bl4,sizeof(bl4),4,wrong_rap,sizeof(wrong_rap),2,
        NULL,0,false,NULL,0,&merged_size));
    // Fragmented MP4 raw timestamp and byte-range contracts, including
    // negative composition offsets used by reordered pictures.
    assert(ambient_fel_fragment_timing(9000,-3000,3000,9000,6000,3000));
    assert(ambient_fel_fragment_timing(9000,0,3000,9000,9000,3000));
    assert(!ambient_fel_fragment_timing(9001,-3001,3000,9000,6000,3000));
    assert(!ambient_fel_fragment_timing(9000,-2999,3000,9000,6000,3000));
    assert(!ambient_fel_fragment_timing(9000,-3000,3001,9000,6000,3000));
    assert(!ambient_fel_fragment_timing(INT64_MAX,1,1,INT64_MAX,0,1));
    assert(!ambient_fel_fragment_timing(0,0,0,0,0,0));
    assert(ambient_fel_fragment_range(1008,2000,1100,900));
    assert(!ambient_fel_fragment_range(1008,2000,1007,100));
    assert(!ambient_fel_fragment_range(1008,2000,1100,901));
    assert(!ambient_fel_fragment_range(2000,1008,1100,1));
    assert(!ambient_fel_fragment_range(0,UINT64_MAX,UINT64_MAX-1,2));
    // A nonseekable fragment may store the EL before OR after the BL.
    // Immutable cached payload supports either order and all other tracks.
    const uint8_t mdat[]={10,11,12,13,14,15,16,17};
    const uint8_t *slice=NULL;
    assert(ambient_fel_mdat_slice(mdat,sizeof(mdat),100,104,4,&slice));
    assert(slice[0]==14&&slice[3]==17);
    assert(ambient_fel_mdat_slice(mdat,sizeof(mdat),100,100,4,&slice));
    assert(slice[0]==10&&slice[3]==13);
    assert(!ambient_fel_mdat_slice(mdat,sizeof(mdat),100,99,1,&slice));
    assert(!ambient_fel_mdat_slice(mdat,sizeof(mdat),100,108,1,&slice));
    assert(!ambient_fel_mdat_slice(mdat,sizeof(mdat),100,107,2,&slice));
    assert(!ambient_fel_mdat_slice(mdat,sizeof(mdat),UINT64_MAX-2,UINT64_MAX-1,1,&slice));
    assert(!ambient_fel_mdat_slice(mdat,AMBIENT_FEL_MAX_MDAT+1u,100,100,1,&slice));
    assert(!ambient_fel_mdat_slice(mdat,sizeof(mdat),100,100,0,&slice));
    // Identical AU with each legal explicit prefix width. Payloads
    // retain their order and original vendor framing after EL stripping.
    for(unsigned width=1;width<=4;width*=2){
        uint8_t framed[32]={0},annex[32]={0},original[32]={0};
        size_t pos=0,converted=0;
        const uint8_t base_nal[]={0x26,1,0x80};
        const uint8_t el_nal[]={0x7e,1,0x26,1,0x80};
        const uint8_t expected[]={0,0,0,1,0x26,1,0x80,0,0,0,1,0x7e,1,0x26,1,0x80};
        framed[pos+width-1]=sizeof(base_nal);pos+=width;
        memcpy(framed+pos,base_nal,sizeof(base_nal));pos+=sizeof(base_nal);
        size_t base_size=pos;
        framed[pos+width-1]=sizeof(el_nal);pos+=width;
        memcpy(framed+pos,el_nal,sizeof(el_nal));pos+=sizeof(el_nal);
        memcpy(original,framed,pos);
        assert(ambient_fel_to_annexb(framed,pos,width,NULL,0,&converted)&&converted==sizeof(expected));
        assert(ambient_fel_to_annexb(framed,pos,width,annex,sizeof(annex),&converted));
        assert(!memcmp(annex,expected,sizeof(expected)));
        assert(!ambient_fel_to_annexb(framed,pos,width,annex,converted-1,&converted));
        assert(!ambient_fel_to_annexb(framed,pos-1,width,NULL,0,&converted));
        size_t broken=pos-1;
        assert(!ambient_fel_strip_el_framed(framed,&broken,width));
        assert(broken==pos-1&&!memcmp(original,framed,pos));
        assert(ambient_fel_strip_el_framed(framed,&pos,width));
        assert(pos==base_size&&!memcmp(original,framed,pos));
    }
    struct ambient_rpu_pts_queue q={0};
    int a=1,b=2,c=3;
    assert(ambient_rpu_pts_insert(&q,30,&a));
    assert(ambient_rpu_pts_insert(&q,10,&b));
    assert(ambient_rpu_pts_insert(&q,20,&c));
    assert(!ambient_rpu_pts_insert(&q,20,&a));
    assert(!ambient_rpu_pts_take(&q,15));
    assert(ambient_rpu_pts_take(&q,10)==&b);
    assert(ambient_rpu_pts_take(&q,30)==&a);
    assert(ambient_rpu_pts_take(&q,20)==&c);
    assert(!ambient_rpu_pts_take(&q,20));
    for(unsigned i=1;i<=AMBIENT_RPU_PTS_CAPACITY;++i) {
        void *m=malloc(1); assert(m); assert(ambient_rpu_pts_insert(&q,i,m));
    }
    assert(!ambient_rpu_pts_insert(&q,100,&a));
    ambient_rpu_pts_clear(&q,release);
    assert(freed==AMBIENT_RPU_PTS_CAPACITY);
    assert(!ambient_rpu_pts_take(&q,1));
}
