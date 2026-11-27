/* Exercise the actual staged VLC demux functions, with a deterministic
 * stream/allocator boundary. The linker discards unrelated VLC modules.
 * This is not a full libvlc playback test. */
#include "mp4.c"

static stream_t fixture_stream;
static uint8_t bytes[256];
static size_t position, visible, blocks, peek_at_end, short_read_at;
static bool seekable;

uint64_t vlc_stream_Tell(const stream_t *stream)
{
    assert(stream==&fixture_stream);
    return position;
}
ssize_t vlc_stream_Peek(stream_t *stream,const uint8_t **out,size_t len)
{
    assert(stream==&fixture_stream && position<=visible);
    if(position==visible)++peek_at_end;
    *out=bytes+position;
    return len<visible-position?len:visible-position;
}
ssize_t vlc_stream_Read(stream_t *stream,void *out,size_t len)
{
    assert(stream==&fixture_stream && position<=visible);
    if(len>visible-position)len=visible-position;
    if(out)memcpy(out,bytes+position,len);
    position+=len;return len;
}
int vlc_stream_Seek(stream_t *stream,uint64_t offset)
{
    assert(stream==&fixture_stream);
    if(!seekable||offset>visible)return VLC_EGENERIC;
    position=(size_t)offset;return VLC_SUCCESS;
}
int vlc_stream_vaControl(stream_t *stream,int query,va_list args)
{
    assert(stream==&fixture_stream);
    if(query==STREAM_GET_SIZE){*va_arg(args,uint64_t *)=visible;return VLC_SUCCESS;}
    if(query==STREAM_CAN_SEEK){*va_arg(args,bool *)=seekable;return VLC_SUCCESS;}
    return VLC_EGENERIC;
}
int MP4_Seek(stream_t *stream,uint64_t offset)
{
    return vlc_stream_Seek(stream,offset);
}
MP4_Box_t *MP4_BoxGet(const MP4_Box_t *box,const char *path)
{
    if(!box)return NULL;
    assert(strlen(path)==4);
    uint32_t type=VLC_FOURCC(path[0],path[1],path[2],path[3]);
    for(MP4_Box_t *child=box->p_first;child;child=child->p_next)
        if(child->i_type==type)return child;
    return NULL;
}
vlc_frame_t *vlc_frame_Alloc(size_t size)
{
    vlc_frame_t *b=calloc(1,sizeof(*b));
    if(!b)return NULL;
    b->p_start=b->p_buffer=malloc(size?size:1);
    if(!b->p_buffer){free(b);return NULL;}
    b->i_size=b->i_buffer=size;
    ++blocks;return b;
}
void vlc_frame_Release(vlc_frame_t *b)
{
    assert(b&&blocks);
    --blocks;free(b->p_start);free(b);
}
block_t *vlc_stream_Block(stream_t *stream,size_t len)
{
    assert(stream==&fixture_stream);
    if(position==short_read_at&&len)len--;
    if(len>visible-position)len=visible-position;
    block_t *b=block_Alloc(len);
    if(b)assert(vlc_stream_Read(stream,b->p_buffer,len)==(ssize_t)len);
    return b;
}

static void box(size_t pos,uint32_t size,const char type[4])
{
    SetDWBE(bytes+pos,size);
    memcpy(bytes+pos+4,type,4);
}
static void reset_stream(bool can_seek)
{
    memset(bytes,0,sizeof(bytes));
    box(100,12,"mdat");memcpy(bytes+108,"ABCD",4);
    box(112,8,"free");
    box(120,12,"mdat");memcpy(bytes+128,"WXYZ",4);
    position=100;visible=132;seekable=can_seek;
    short_read_at=SIZE_MAX;peek_at_end=0;
}

static void test_segment_header(demux_t *d,demux_sys_t *sys)
{
    for(unsigned mode=0;mode<2;mode++){
        reset_stream(mode!=0);sys->b_seekable=mode!=0;
        box(100,8,"free");
        box(108,20,"styp");
        memcpy(bytes+116,"msdh",4);SetDWBE(bytes+120,0);
        memcpy(bytes+124,"msix",4);
        box(128,8,"skip");box(136,8,"moof");visible=144;
        uint64_t padding=0;unsigned count=0;
        assert(MP4_FEL_SkipSegmentHeader(d,&padding,&count));
        assert(position==136&&padding==36&&count==3);
        uint64_t payload,end;
        assert(MP4_FEL_PeekBox(d->s,ATOM_moof,&payload,&end)&&end==144);

        position=108;box(108,15,"styp");padding=count=0;
        assert(!MP4_FEL_SkipSegmentHeader(d,&padding,&count)&&position==108);
        box(108,17,"styp");padding=count=0;
        assert(!MP4_FEL_SkipSegmentHeader(d,&padding,&count)&&position==108);
        box(108,20,"styp");padding=AMBIENT_FEL_MAX_PADDING-19;count=0;
        assert(!MP4_FEL_SkipSegmentHeader(d,&padding,&count)&&position==108);
        box(108,20,"emsg");padding=count=0;
        assert(MP4_FEL_SkipSegmentHeader(d,&padding,&count)&&position==108);
        assert(!MP4_FEL_PeekBox(d->s,ATOM_moof,&payload,&end));
        box(108,20,"styp");box(128,16,"styp");visible=144;
        assert(MP4_FEL_SkipSegmentHeader(d,&padding,&count)&&position==128);
        assert(!MP4_FEL_PeekBox(d->s,ATOM_moof,&payload,&end));
    }
}

int main(void)
{
    mp4_track_t track={.i_track_ID=1,.b_ok=true,.i_timescale=1000};
    demux_sys_t sys={.i_tracks=1,.track=&track};
    demux_t demux={.s=&fixture_stream,.p_sys=&sys};
    test_segment_header(&demux,&sys);
    MP4_Box_data_tfhd_t tfhd={.i_track_ID=1,
        .i_flags=MP4_TFHD_DEFAULT_BASE_IS_MOOF|MP4_TFHD_DFLT_SAMPLE_SIZE|
            MP4_TFHD_DFLT_SAMPLE_DURATION,
        .i_default_sample_size=4,.i_default_sample_duration=1};
    MP4_Box_data_trun_t tr[2]={{.i_flags=MP4_TRUN_DATA_OFFSET,
        .i_sample_count=1,.i_data_offset=108},
        {.i_flags=MP4_TRUN_DATA_OFFSET,.i_sample_count=1,.i_data_offset=128}};
    MP4_Box_t run[2]={{.i_type=ATOM_trun,.data.p_trun=&tr[0]},
        {.i_type=ATOM_trun,.data.p_trun=&tr[1]}};
    run[0].p_next=&run[1];
    MP4_Box_t header={.i_type=ATOM_tfhd,.data.p_tfhd=&tfhd,.p_next=&run[0]};
    MP4_Box_t traf={.i_type=ATOM_traf,.p_first=&header};
    MP4_Box_t moof={.i_type=ATOM_moof,.i_pos=0,.i_size=100,.p_first=&traf};
    uint64_t required;
    assert(MP4_FEL_PreflightMoof(&demux,&moof,&required)&&required==132);
    tr[1].i_data_offset=-1;
    assert(!MP4_FEL_PreflightMoof(&demux,&moof,&required));
    tr[1].i_data_offset=128;
    tfhd.i_track_ID=9;
    assert(!MP4_FEL_PreflightMoof(&demux,&moof,&required));
    tfhd.i_track_ID=1;
    assert(MP4_FEL_PreflightMoof(&demux,&moof,&required)&&required==132);
    /* Repeated traf would overwrite the track's defaults; repeated
     * tfhd/tfdt/mfhd boxes create ambiguous identity or clock anchors. */
    MP4_Box_t dup_traf=traf;
    traf.p_next=&dup_traf;
    assert(!MP4_FEL_PreflightMoof(&demux,&moof,&required));
    traf.p_next=NULL;
    MP4_Box_t dup_header=header;
    header.p_next=&dup_header;
    assert(!MP4_FEL_PreflightMoof(&demux,&moof,&required));
    header.p_next=&run[0];
    MP4_Box_data_tfdt_t dt={.i_base_media_decode_time=0};
    MP4_Box_t dt1={.i_type=ATOM_tfdt,.data.p_tfdt=&dt},dt2=dt1;
    dt1.p_next=&dt2;dt2.p_next=&run[0];header.p_next=&dt1;
    assert(!MP4_FEL_PreflightMoof(&demux,&moof,&required));
    dt1.p_next=&run[0];
    assert(MP4_FEL_PreflightMoof(&demux,&moof,&required));
    header.p_next=&run[0];
    MP4_Box_data_mfhd_t mh={.i_sequence_number=1};
    MP4_Box_t mh1={.i_type=ATOM_mfhd,.data.p_mfhd=&mh},mh2=mh1;
    mh1.p_next=&mh2;mh2.p_next=&traf;moof.p_first=&mh1;
    assert(!MP4_FEL_PreflightMoof(&demux,&moof,&required));
    mh1.p_next=&traf;
    assert(MP4_FEL_PreflightMoof(&demux,&moof,&required)&&required==132);

    reset_stream(true);sys.b_seekable=true;
    uint64_t end=0,padding=0,total=0;
    unsigned count=0,boxes=0;
    assert(MP4_FEL_ScanMdats(&demux,sys.context.ambient_fel_bl_extents,
        &count,&end,required,&padding,&boxes));
    assert(count==2&&end==132&&padding==8&&boxes==1&&peek_at_end==0);
    assert(position==132&&blocks==0);
    sys.context.ambient_fel_bl_extent_count=count;
    assert(MP4_FEL_PayloadContains(&sys,false,108,4));
    assert(MP4_FEL_PayloadContains(&sys,false,128,4));
    assert(!MP4_FEL_PayloadContains(&sys,false,111,18));

    reset_stream(false);sys.b_seekable=false;
    padding=total=boxes=count=end=0;
    assert(MP4_FEL_BufferMdats(&demux,sys.context.ambient_fel_bl_extents,
        sys.context.ambient_fel_bl_cache,&sys.context.ambient_fel_bl_extent_count,
        &end,&total,required,&padding,&boxes));
    assert(end==132&&total==8&&padding==8&&boxes==1&&blocks==2&&peek_at_end==0);
    sys.context.ambient_fel_mdat=true;
    block_t *sample=MP4_FEL_ReadCached(&sys,false,128,4);
    assert(sample&&sample->i_buffer==4&&!memcmp(sample->p_buffer,"WXYZ",4));
    block_Release(sample);
    assert(!MP4_FEL_ReadCached(&sys,false,110,4));
    assert(!MP4_FEL_ReadCached(&sys,false,112,1));

    mp4_run_t indexed[2]={{.i_offset=108,.i_first_dts=0,.p_trun=&run[0]},
        {.i_offset=128,.i_first_dts=1,.p_trun=&run[1]}};
    track.context.runs.p_array=indexed;track.context.runs.i_count=2;
    track.context.i_default_sample_size=4;track.context.i_default_sample_duration=1;
    sys.context.ambient_fel_mdat_start=108;sys.context.i_post_mdat_offset=end;
    assert(MP4_FEL_ValidatePayloads(&sys));
    indexed[1].i_offset=108;
    assert(!MP4_FEL_ValidatePayloads(&sys));
    indexed[1].i_offset=112;
    assert(!MP4_FEL_ValidatePayloads(&sys));
    MP4_FEL_ReleaseCaches(&sys);
    assert(!blocks&&!sys.context.ambient_fel_mdat);

    reset_stream(false);short_read_at=128;
    padding=total=boxes=end=0;
    sys.context.ambient_fel_bl_extent_count=0;
    assert(!MP4_FEL_BufferMdats(&demux,sys.context.ambient_fel_bl_extents,
        sys.context.ambient_fel_bl_cache,&sys.context.ambient_fel_bl_extent_count,
        &end,&total,required,&padding,&boxes));
    assert(blocks==2&&sys.context.ambient_fel_bl_extent_count==0);
    MP4_FEL_ReleaseCaches(&sys);
    assert(!blocks);
    return 0;
}
