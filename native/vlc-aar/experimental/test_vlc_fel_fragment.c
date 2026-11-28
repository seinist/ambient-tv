/* Exercise the actual staged VLC demux functions, with a deterministic
 * stream/allocator boundary. The linker discards unrelated VLC modules.
 * This is not a full libvlc playback test. */
#include "mp4.c"

static stream_t fixture_stream;
static uint8_t bytes[1024];
static size_t position, visible, blocks, peek_at_end, short_read_at;
static bool seekable, misdirect_seek, misdirect_read;
static uint64_t wrong_seek_at=UINT64_MAX;
static MP4_Box_t look_root,look_el,look_mid;
static unsigned root_frees,el_frees,mid_frees,chunk_reads;
static bool have_mid;
static MP4_Box_t look_extra[9];
static unsigned extra_count,extra_frees[9];

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
    position=(size_t)offset;
    if((misdirect_seek||offset==wrong_seek_at)&&position>0)--position;
    return VLC_SUCCESS;
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
/* The box parser is the boundary here: construct the exact parsed tree
 * while exercising VLC's real patched lookahead and dependent indexer. */
unsigned MP4_BoxCount(const MP4_Box_t *parent,const char *path)
{
    assert(strlen(path)==4);
    uint32_t type=VLC_FOURCC(path[0],path[1],path[2],path[3]);
    unsigned count=0;
    for(const MP4_Box_t *box=parent->p_first;box;box=box->p_next)
        count+=box->i_type==type;
    return count;
}
MP4_Box_t *MP4_BoxGetNextChunk(stream_t *stream)
{
    assert(stream==&fixture_stream);
    MP4_Box_t *next=have_mid&&position==look_mid.i_pos?&look_mid:&look_el;
    for(unsigned i=0;i<extra_count;i++)
        if(position==look_extra[i].i_pos){next=&look_extra[i];break;}
    assert(position==next->i_pos);
    ++chunk_reads;
    if(vlc_stream_Read(stream,NULL,next->i_size)!=(ssize_t)next->i_size)return NULL;
    look_root.p_first=next;
    return &look_root;
}
MP4_Box_t *MP4_BoxExtract(MP4_Box_t **chain,uint32_t type)
{
    for(;*chain;chain=&(*chain)->p_next){
        if((*chain)->i_type!=type)continue;
        MP4_Box_t *box=*chain;
        *chain=box->p_next;box->p_next=NULL;
        return box;
    }
    return NULL;
}
void MP4_BoxFree(MP4_Box_t *box)
{
    if(box==&look_root){assert(!box->p_first);++root_frees;}
    else if(box==&look_mid)++mid_frees;
    else if(box==&look_el)++el_frees;
    else{
        unsigned i=0;
        while(i<extra_count&&box!=&look_extra[i])++i;
        assert(i<extra_count);++extra_frees[i];
    }
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
    if(misdirect_read&&position>0)--position;
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
    short_read_at=SIZE_MAX;peek_at_end=0;misdirect_seek=misdirect_read=false;
    wrong_seek_at=UINT64_MAX;root_frees=el_frees=mid_frees=chunk_reads=0;
    have_mid=false;extra_count=0;memset(extra_frees,0,sizeof(extra_frees));
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

static void test_empty_mdat_gaps(demux_t *d,demux_sys_t *sys)
{
    for(unsigned mode=0;mode<2;mode++){
        reset_stream(mode!=0);sys->b_seekable=mode!=0;
        box(112,8,"mdat");
        box(120,1,"mdat");SetQWBE(bytes+128,16);
        box(136,8,"free");
        box(144,12,"mdat");memcpy(bytes+152,"WXYZ",4);visible=156;
        uint64_t end=0,padding=0,total=0;
        unsigned count=0,boxes=0;
        struct ambient_fel_byte_range ranges[AMBIENT_FEL_MAX_MDAT_BOXES];
        if(mode)
            assert(MP4_FEL_ScanMdats(d,ranges,&count,&end,156,&padding,&boxes));
        else
            assert(MP4_FEL_BufferMdats(d,ranges,sys->context.ambient_fel_bl_cache,
                &count,&end,&total,156,&padding,&boxes));
        assert(count==2&&end==156&&padding==32&&boxes==3&&peek_at_end==0);
        assert(ranges[0].start==108&&ranges[1].start==152);
        assert(!ambient_fel_payload_contains(ranges,count,120,1));
        assert(!ambient_fel_payload_contains(ranges,count,128,8));
        if(!mode){
            assert(total==8&&blocks==2);
            MP4_FEL_ReleaseCaches(sys);
        }
        assert(!blocks);
        position=112;padding=boxes=0;
        assert(MP4_FEL_SkipGap(d,&padding,&boxes,true));
        assert(position==144&&padding==32&&boxes==3);
        position=112;padding=AMBIENT_FEL_MAX_PADDING-7;boxes=0;
        assert(!MP4_FEL_SkipGap(d,&padding,&boxes,true)&&position==112);
        position=112;padding=boxes=0;
        assert(MP4_FEL_SkipPadding(d,&padding,&boxes)&&position==112);
        position=112;box(112,0,"mdat");padding=boxes=0;
        assert(!MP4_FEL_SkipGap(d,&padding,&boxes,true)&&position==112);
    }
}
static void test_leading_empty_mdats(demux_t *d,demux_sys_t *sys)
{
    for(unsigned mode=0;mode<2;mode++){
        reset_stream(mode!=0);sys->b_seekable=mode!=0;
        box(100,8,"mdat");box(108,1,"mdat");SetQWBE(bytes+116,16);
        box(124,8,"free");box(132,12,"mdat");
        memcpy(bytes+140,"DATA",4);visible=144;
        uint64_t end=0,padding=0,total=0;unsigned count=0,boxes=0;
        struct ambient_fel_byte_range ranges[AMBIENT_FEL_MAX_MDAT_BOXES];
        if(mode)
            assert(MP4_FEL_ScanMdats(d,ranges,&count,&end,144,&padding,&boxes));
        else
            assert(MP4_FEL_BufferMdats(d,ranges,sys->context.ambient_fel_bl_cache,
                &count,&end,&total,144,&padding,&boxes));
        assert(count==1&&end==144&&position==144&&padding==32&&boxes==3);
        assert(ranges[0].start==140&&ranges[0].end==144);
        assert(!ambient_fel_payload_contains(ranges,count,116,8));
        if(!mode){
            assert(total==4&&blocks==1);
            MP4_FEL_ReleaseCaches(sys);
        }
        assert(!blocks);
        position=100;count=boxes=0;padding=AMBIENT_FEL_MAX_PADDING-7;
        assert(!MP4_FEL_SkipGap(d,&padding,&boxes,true)&&position==100);
        position=100;box(100,0,"mdat");padding=boxes=0;
        assert(!MP4_FEL_SkipGap(d,&padding,&boxes,true));
    }
    reset_stream(true);sys->b_seekable=true;
    misdirect_seek=true;
    assert(!MP4_FEL_SeekExact(d->s,120)&&position==119);
    position=100;
    uint64_t end=0,padding=0;unsigned count=0,boxes=0;
    struct ambient_fel_byte_range ranges[AMBIENT_FEL_MAX_MDAT_BOXES];
    assert(!MP4_FEL_ScanMdats(d,ranges,&count,&end,132,&padding,&boxes));
    assert(!count&&position==111);
    misdirect_seek=false;
    assert(MP4_FEL_SeekExact(d->s,100));
}
static void test_exact_sample_read(demux_t *d)
{
    reset_stream(true);
    assert(MP4_FEL_SeekExact(d->s,108));
    assert(!MP4_FEL_ReadExact(d->s,109,3)&&position==108&&!blocks);
    assert(!MP4_FEL_ReadExact(d->s,108,0)&&position==108&&!blocks);
    block_t *sample=MP4_FEL_ReadExact(d->s,108,4);
    assert(sample&&sample->i_buffer==4&&!memcmp(sample->p_buffer,"ABCD",4));
    assert(position==112&&blocks==1);block_Release(sample);
    assert(MP4_FEL_SeekExact(d->s,128));short_read_at=128;
    assert(!MP4_FEL_ReadExact(d->s,128,4)&&position==131&&!blocks);
    assert(MP4_FEL_SeekExact(d->s,128));short_read_at=SIZE_MAX;misdirect_read=true;
    assert(!MP4_FEL_ReadExact(d->s,128,4)&&position==131&&!blocks);
    misdirect_read=false;
    assert(MP4_FEL_SeekExact(d->s,128));
    assert(!MP4_FEL_ReadExact(d->s,128,5)&&position==132&&!blocks);
}
/* Keep the actual generic VLC trun indexer in the host link, including
 * its unused clock-recovery branches. This fixture supplies explicit
 * clocks, so falling back to an external fragment index is a test error. */
stime_t MP4_Fragment_Index_GetTrackStartTime(mp4_fragments_index_t *index,
                                            unsigned track,uint64_t offset)
{
    (void)index;(void)track;(void)offset;
    assert(!"unexpected fragment index lookup");
    return 0;
}
/* The generic indexer emits debug messages in assertion-enabled builds.
 * Match the pinned VLC object-logging ABI, not the generic logger API. */
const char vlc_module_name[]="mp4";
void vlc_object_Log(vlc_object_t *obj,int priority,const char *module,
                    const char *file,unsigned line,const char *func,
                    const char *format,...)
{
    (void)obj;(void)priority;(void)module;(void)file;
    (void)line;(void)func;(void)format;
}
static void test_generic_trun_index(void)
{
    for(unsigned strict=0;strict<2;strict++){
        mp4_track_t track={.i_track_ID=1,.b_ok=true,.i_timescale=1000,
            .i_time=7,.ambient_fel_el_id=strict?2:0};
        demux_sys_t sys={.i_tracks=1,.track=&track};
        demux_t d={.s=&fixture_stream,.p_sys=&sys};
        MP4_Box_data_tfhd_t tfhd={.i_track_ID=1,
            .i_flags=MP4_TFHD_DEFAULT_BASE_IS_MOOF|MP4_TFHD_DFLT_SAMPLE_SIZE|
                MP4_TFHD_DFLT_SAMPLE_DURATION,
            .i_default_sample_size=4,.i_default_sample_duration=3};
        MP4_Box_data_trun_t tr[2]={{.i_flags=MP4_TRUN_DATA_OFFSET,
            .i_sample_count=1,.i_data_offset=108},{.i_sample_count=2}};
        MP4_Box_t run[2]={{.i_type=ATOM_trun,.data.p_trun=&tr[0]},
            {.i_type=ATOM_trun,.data.p_trun=&tr[1]}};
        run[0].p_next=&run[1];
        MP4_Box_t header={.i_type=ATOM_tfhd,.data.p_tfhd=&tfhd,.p_next=&run[0]};
        MP4_Box_t traf={.i_type=ATOM_traf,.p_first=&header};
        MP4_Box_t moof={.i_type=ATOM_moof,.i_pos=0,.i_size=100,.p_first=&traf};
        assert(FragCreateTrunIndex(&d,&moof,NULL,INVALID_SEGMENT_TIME)==VLC_SUCCESS);
        assert(track.context.runs.i_count==2&&track.context.runs.p_array);
        assert(track.context.i_default_sample_size==4);
        assert(track.context.i_default_sample_duration==3);
        assert(track.context.runs.p_array[0].i_offset==108);
        assert(track.context.runs.p_array[1].i_offset==112);
        assert(track.context.runs.p_array[0].i_first_dts==7);
        assert(track.context.runs.p_array[1].i_first_dts==10);
        assert(track.context.runs.p_array[1].p_trun==&run[1]);
        /* The indexer resets old runs on every new fragment. An unknown
         * track is ignored in ordinary MP4, but is invalid in FEL mode. */
        tfhd.i_track_ID=9;
        assert(FragCreateTrunIndex(&d,&moof,NULL,INVALID_SEGMENT_TIME)==
            (strict?VLC_EGENERIC:VLC_SUCCESS));
        assert(!track.context.runs.i_count&&!track.context.runs.p_array);
    }
}
/* Exercise the actual FragPrepareChunk transaction, not just its individual
 * arithmetic helpers. The independent layer timescales must map exactly. */
static void test_prepare_paired_fragment(void)
{
    reset_stream(true);
    mp4_track_t track[3]={{.i_track_ID=1,.b_ok=true,.i_timescale=1000,
        .ambient_fel_el_id=2},{.i_track_ID=2,.b_ok=true,.i_timescale=2000,
        .ambient_fel_hidden=true},{.i_track_ID=3,.b_ok=true,.i_timescale=48000}};
    demux_sys_t sys={.i_tracks=3,.track=track,.b_seekable=true};
    demux_t d={.s=&fixture_stream,.p_sys=&sys};
    MP4_Box_data_mfhd_t mh={.i_sequence_number=7};
    MP4_Box_data_tfhd_t tf[2]={{.i_track_ID=1,
        .i_flags=MP4_TFHD_DEFAULT_BASE_IS_MOOF|MP4_TFHD_DFLT_SAMPLE_SIZE|
            MP4_TFHD_DFLT_SAMPLE_DURATION,
        .i_default_sample_size=4,.i_default_sample_duration=3},
        {.i_track_ID=2,.i_flags=MP4_TFHD_DEFAULT_BASE_IS_MOOF|
            MP4_TFHD_DFLT_SAMPLE_SIZE|MP4_TFHD_DFLT_SAMPLE_DURATION,
        .i_default_sample_size=4,.i_default_sample_duration=6}};
    MP4_Box_data_tfdt_t dt[2]={{.i_base_media_decode_time=10},
        {.i_base_media_decode_time=20}};
    MP4_Box_data_trun_t tr[2]={{.i_flags=MP4_TRUN_DATA_OFFSET,
        .i_sample_count=1,.i_data_offset=108},
        {.i_flags=MP4_TRUN_DATA_OFFSET,.i_sample_count=1,.i_data_offset=128}};
    MP4_Box_t run[2]={{.i_type=ATOM_trun,.data.p_trun=&tr[0]},
        {.i_type=ATOM_trun,.data.p_trun=&tr[1]}};
    MP4_Box_t clock[2]={{.i_type=ATOM_tfdt,.data.p_tfdt=&dt[0],.p_next=&run[0]},
        {.i_type=ATOM_tfdt,.data.p_tfdt=&dt[1],.p_next=&run[1]}};
    MP4_Box_t header[2]={{.i_type=ATOM_tfhd,.data.p_tfhd=&tf[0],.p_next=&clock[0]},
        {.i_type=ATOM_tfhd,.data.p_tfhd=&tf[1],.p_next=&clock[1]}};
    MP4_Box_t traf[2]={{.i_type=ATOM_traf,.p_first=&header[0]},
        {.i_type=ATOM_traf,.p_first=&header[1]}};
    traf[0].p_next=&traf[1];
    MP4_Box_t mfhd={.i_type=ATOM_mfhd,.data.p_mfhd=&mh,.p_next=&traf[0]};
    MP4_Box_t moof={.i_type=ATOM_moof,.i_pos=0,.i_size=100,.p_first=&mfhd};
    assert(FragPrepareChunk(&d,&moof,NULL,INVALID_SEGMENT_TIME,true)==VLC_SUCCESS);
    assert(track[0].ambient_fel_clock_valid&&track[0].ambient_fel_sequence_proven);
    assert(track[0].i_time==10&&track[1].i_time==20);
    assert(track[0].ambient_fel_bl_end==13&&track[0].ambient_fel_el_end==26);
    assert(track[0].context.i_trun_sample_pos==108&&track[1].context.i_trun_sample_pos==128);
    assert(sys.context.ambient_fel_required_end==132);
    assert(track[0].i_next_block_flags&BLOCK_FLAG_DISCONTINUITY);

    /* A contiguous segment may omit both tfdt boxes and inherit the proven
     * independent clock ends, not the generic movie-time estimate. */
    ++mh.i_sequence_number;
    header[0].p_next=&run[0];header[1].p_next=&run[1];
    track[0].i_next_block_flags=0;
    assert(FragPrepareChunk(&d,&moof,NULL,INVALID_SEGMENT_TIME,false)==VLC_SUCCESS);
    assert(track[0].i_time==13&&track[1].i_time==26);
    assert(track[0].ambient_fel_bl_end==16&&track[0].ambient_fel_el_end==32);
    assert(!(track[0].i_next_block_flags&BLOCK_FLAG_DISCONTINUITY));

    /* Without explicit clocks a discontinuous fragment must fail closed. */
    ++mh.i_sequence_number;
    assert(FragPrepareChunk(&d,&moof,NULL,INVALID_SEGMENT_TIME,true)!=VLC_SUCCESS);
    assert(!track[0].ambient_fel_clock_valid);
    header[0].p_next=&clock[0];header[1].p_next=&clock[1];
    dt[0].i_base_media_decode_time=100;dt[1].i_base_media_decode_time=201;
    assert(FragPrepareChunk(&d,&moof,NULL,INVALID_SEGMENT_TIME,true)!=VLC_SUCCESS);
    dt[1].i_base_media_decode_time=200;
    tf[1].i_default_sample_duration=7;
    assert(FragPrepareChunk(&d,&moof,NULL,INVALID_SEGMENT_TIME,true)!=VLC_SUCCESS);
    tf[1].i_default_sample_duration=6;
    assert(FragPrepareChunk(&d,&moof,NULL,INVALID_SEGMENT_TIME,true)==VLC_SUCCESS);
    assert(track[0].ambient_fel_bl_end==103&&track[0].ambient_fel_el_end==206);
    assert(track[0].i_next_block_flags&BLOCK_FLAG_DISCONTINUITY);
    // A contiguous paired moof with independent audio must still honor
    // an explicit tfdt, even when the previous audio clock was valid.
    MP4_Box_data_tfhd_t atf={.i_track_ID=3,
        .i_flags=MP4_TFHD_DEFAULT_BASE_IS_MOOF|MP4_TFHD_DFLT_SAMPLE_SIZE|
            MP4_TFHD_DFLT_SAMPLE_DURATION,
        .i_default_sample_size=4,.i_default_sample_duration=1024};
    MP4_Box_data_tfdt_t adt={.i_base_media_decode_time=48000};
    MP4_Box_data_trun_t atr={.i_flags=MP4_TRUN_DATA_OFFSET,
        .i_sample_count=1,.i_data_offset=144};
    MP4_Box_t arun={.i_type=ATOM_trun,.data.p_trun=&atr};
    MP4_Box_t aclock={.i_type=ATOM_tfdt,.data.p_tfdt=&adt,.p_next=&arun};
    MP4_Box_t ah={.i_type=ATOM_tfhd,.data.p_tfhd=&atf,.p_next=&aclock};
    MP4_Box_t atraf={.i_type=ATOM_traf,.p_first=&ah};
    traf[1].p_next=&atraf;
    ++mh.i_sequence_number;dt[0].i_base_media_decode_time=103;dt[1].i_base_media_decode_time=206;
    track[2].i_time=7;track[2].context.b_resync_time_offset=false;
    assert(FragPrepareChunk(&d,&moof,NULL,INVALID_SEGMENT_TIME,false)==VLC_SUCCESS);
    assert(track[2].i_time==48000&&track[2].context.runs.i_count==1);
    assert(track[2].context.runs.p_array[0].i_first_dts==48000);
    assert(track[2].i_next_block_flags&BLOCK_FLAG_DISCONTINUITY);
    assert(sys.context.ambient_fel_required_end==148);
    ++mh.i_sequence_number;dt[0].i_base_media_decode_time=106;dt[1].i_base_media_decode_time=212;
    adt.i_base_media_decode_time=UINT64_MAX;
    assert(FragPrepareChunk(&d,&moof,NULL,INVALID_SEGMENT_TIME,false)!=VLC_SUCCESS);
    FragResetContext(&sys);
}

static void test_adjacent_lookahead(void)
{
    for(unsigned mode=0;mode<2;mode++){
        for(unsigned failure=0;failure<4;failure++){
            if(failure==3&&!mode)continue;
            reset_stream(mode!=0);
            mp4_track_t tracks[2]={{.i_track_ID=1,.b_ok=true,.i_timescale=1000,
                .ambient_fel_el_id=2},{.i_track_ID=2,.b_ok=true,.i_timescale=1000,
                .ambient_fel_hidden=true,.ambient_fel_separate=true}};
            demux_sys_t sys={.i_tracks=2,.track=tracks,.b_seekable=mode!=0};
            demux_t d={.s=&fixture_stream,.p_sys=&sys};
            MP4_Box_data_mfhd_t bm={.i_sequence_number=7},
                em={.i_sequence_number=failure==1?9:8};
            MP4_Box_data_tfhd_t tfhd={.i_track_ID=2,
                .i_flags=MP4_TFHD_DEFAULT_BASE_IS_MOOF|MP4_TFHD_DFLT_SAMPLE_SIZE|
                    MP4_TFHD_DFLT_SAMPLE_DURATION,
                .i_default_sample_size=4,.i_default_sample_duration=1};
            MP4_Box_data_trun_t tr={.i_flags=MP4_TRUN_DATA_OFFSET,
                .i_sample_count=1,.i_data_offset=56};
            MP4_Box_t bl_header={.i_type=ATOM_mfhd,.data.p_mfhd=&bm};
            MP4_Box_t bl={.i_type=ATOM_moof,.i_pos=0,.i_size=100,.p_first=&bl_header};
            MP4_Box_t run={.i_type=ATOM_trun,.data.p_trun=&tr};
            MP4_Box_t header={.i_type=ATOM_tfhd,.data.p_tfhd=&tfhd,.p_next=&run};
            MP4_Box_t traf={.i_type=ATOM_traf,.p_first=&header};
            MP4_Box_t el_header={.i_type=ATOM_mfhd,.data.p_mfhd=&em,.p_next=&traf};
            look_el=(MP4_Box_t){.i_type=ATOM_moof,.i_pos=136,.i_size=40,.p_first=&el_header};
            look_root=(MP4_Box_t){0};
            box(112,8,failure==2?"emsg":"free");
            box(120,16,"styp");memcpy(bytes+128,"msdh",4);SetDWBE(bytes+132,0);
            box(136,40,"moof");box(176,8,"mdat");
            box(184,12,"mdat");memcpy(bytes+192,"EL12",4);visible=196;
            sys.context.ambient_fel_required_end=112;
            if(failure==3)wrong_seek_at=100;
            const bool ok=MP4_FEL_Lookahead(&d,&bl);
            assert(ok==(failure==0));
            if(failure){
                assert(!sys.context.ambient_fel_el_moof&&!blocks);
                assert(!sys.context.ambient_fel_bl_extent_count&&!sys.context.ambient_fel_el_extent_count);
                assert(root_frees==(failure==2?0:1)&&el_frees==root_frees);
                assert(sys.b_error==(!mode||failure==3));
                if(mode)assert(position==(failure==3?99:100));
                continue;
            }
            assert(chunk_reads==1&&root_frees==1&&!el_frees);
            assert(sys.context.ambient_fel_el_moof==&look_el);
            assert(sys.context.ambient_fel_bl_extent_count==1&&sys.context.ambient_fel_el_extent_count==1);
            assert(sys.context.ambient_fel_bl_extents[0].start==108);
            assert(sys.context.ambient_fel_el_start==192&&sys.context.ambient_fel_el_end==196);
            assert(position==(mode?100:196));
            assert(blocks==(mode?0:2));
            assert(MP4_FEL_IndexAdjacent(&d));
            assert(tracks[1].context.runs.i_count==1);
            assert(tracks[1].context.runs.p_array[0].i_offset==192);
            assert(tracks[1].context.i_default_sample_size==4);
            if(!mode){
                block_t *sample=MP4_FEL_ReadCached(&sys,true,192,4);
                assert(sample&&!memcmp(sample->p_buffer,"EL12",4));
                block_Release(sample);
            }
            free(tracks[1].context.runs.p_array);
            MP4_FEL_ReleaseCaches(&sys);
            MP4_BoxFree(sys.context.ambient_fel_el_moof);
            assert(!blocks&&el_frees==1);
        }
    }
}
/* Exercise the actual lookahead transaction and queue ownership with a
 * timed audio-only moof between the base and enhancement layers. */
static void test_timed_intermediate_lookahead(void)
{
    for(unsigned mode=0;mode<2;mode++){
        for(unsigned failure=0;failure<3;failure++){
            reset_stream(mode!=0);have_mid=true;
            mp4_track_t tracks[3]={{.i_track_ID=1,.b_ok=true,.i_timescale=1000,
                .ambient_fel_el_id=2},{.i_track_ID=2,.b_ok=true,.i_timescale=1000,
                .ambient_fel_hidden=true,.ambient_fel_separate=true},
                {.i_track_ID=3,.b_ok=true,.i_timescale=48000}};
            demux_sys_t sys={.i_tracks=3,.track=tracks,.b_seekable=mode!=0};
            demux_t d={.s=&fixture_stream,.p_sys=&sys};
            MP4_Box_data_mfhd_t seq[3]={{.i_sequence_number=7},
                {.i_sequence_number=8},{.i_sequence_number=failure==1?10:9}};
            MP4_Box_data_tfhd_t tfhd[2]={{.i_track_ID=failure==2?1:3,
                .i_flags=MP4_TFHD_DEFAULT_BASE_IS_MOOF|MP4_TFHD_DFLT_SAMPLE_SIZE|
                    MP4_TFHD_DFLT_SAMPLE_DURATION,
                .i_default_sample_size=4,.i_default_sample_duration=1},
                {.i_track_ID=2,.i_flags=MP4_TFHD_DEFAULT_BASE_IS_MOOF|
                    MP4_TFHD_DFLT_SAMPLE_SIZE|MP4_TFHD_DFLT_SAMPLE_DURATION,
                .i_default_sample_size=4,.i_default_sample_duration=1}};
            MP4_Box_data_trun_t tr[2]={{.i_flags=MP4_TRUN_DATA_OFFSET,
                .i_sample_count=1,.i_data_offset=48},
                {.i_flags=MP4_TRUN_DATA_OFFSET,.i_sample_count=1,.i_data_offset=48}};
            MP4_Box_t run[2]={{.i_type=ATOM_trun,.data.p_trun=&tr[0]},
                {.i_type=ATOM_trun,.data.p_trun=&tr[1]}};
            MP4_Box_t header[2]={{.i_type=ATOM_tfhd,.data.p_tfhd=&tfhd[0],.p_next=&run[0]},
                {.i_type=ATOM_tfhd,.data.p_tfhd=&tfhd[1],.p_next=&run[1]}};
            MP4_Box_data_tfdt_t audio_time={.i_base_media_decode_time=48000};
            MP4_Box_t audio_clock={.i_type=ATOM_tfdt,.data.p_tfdt=&audio_time,.p_next=&run[0]};
            header[0].p_next=&audio_clock;
            MP4_Box_t traf[2]={{.i_type=ATOM_traf,.p_first=&header[0]},
                {.i_type=ATOM_traf,.p_first=&header[1]}};
            MP4_Box_t mfhd[3]={{.i_type=ATOM_mfhd,.data.p_mfhd=&seq[0]},
                {.i_type=ATOM_mfhd,.data.p_mfhd=&seq[1],.p_next=&traf[0]},
                {.i_type=ATOM_mfhd,.data.p_mfhd=&seq[2],.p_next=&traf[1]}};
            MP4_Box_t bl={.i_type=ATOM_moof,.i_pos=0,.i_size=100,.p_first=&mfhd[0]};
            look_mid=(MP4_Box_t){.i_type=ATOM_moof,.i_pos=112,.i_size=40,.p_first=&mfhd[1]};
            look_el=(MP4_Box_t){.i_type=ATOM_moof,.i_pos=164,.i_size=40,.p_first=&mfhd[2]};
            look_root=(MP4_Box_t){0};
            box(112,40,"moof");box(152,12,"mdat");memcpy(bytes+160,"AUD1",4);
            box(164,40,"moof");box(204,12,"mdat");memcpy(bytes+212,"EL12",4);
            visible=216;sys.context.ambient_fel_required_end=112;
            const bool ok=MP4_FEL_Lookahead(&d,&bl);
            assert(ok==(failure==0));
            if(failure){
                assert(!sys.ambient_fel_replay_count&&!sys.context.ambient_fel_el_moof);
                assert(!blocks&&mid_frees==1&&el_frees==(failure==1?1:0));
                assert(sys.b_error==!mode);
                if(mode)assert(position==100);
                continue;
            }
            assert(chunk_reads==2&&root_frees==2&&!mid_frees&&!el_frees);
            assert(sys.ambient_fel_replay_count==1&&!sys.context.ambient_fel_interleaved);
            assert(sys.ambient_fel_replay[0].moof==&look_mid);
            assert(sys.ambient_fel_replay[0].sequence==8);
            assert(sys.ambient_fel_replay[0].count==1&&sys.ambient_fel_replay[0].end==164);
            assert(sys.ambient_fel_replay[0].extents[0].start==160);
            assert(sys.ambient_fel_replay_end==216&&sys.ambient_fel_replay_sequence==9);
            assert(sys.context.ambient_fel_el_moof==&look_el);
            assert(position==(mode?100:216)&&blocks==(mode?0:3));
            if(!mode){
                block_t *audio=sys.ambient_fel_replay[0].cache[0];
                assert(audio&&audio->i_buffer==4&&!memcmp(audio->p_buffer,"AUD1",4));
            }
            assert(MP4_FEL_IndexAdjacent(&d));
            assert(tracks[1].context.runs.i_count==1);
            assert(tracks[1].context.runs.p_array[0].i_offset==212);
            sys.context.i_lastseqnumber=7;
            tracks[0].ambient_fel_clock_valid=tracks[0].ambient_fel_sequence_proven=true;
            tracks[0].ambient_fel_bl_end=tracks[0].ambient_fel_el_end=1;
            tracks[2].i_time=7;
            assert(MP4_FEL_InterleaveReplay(&d));
            assert(tracks[1].context.runs.i_count==1&&tracks[2].context.runs.i_count==1);
            mp4_run_t *r=&tracks[2].context.runs.p_array[0];
            assert(r->ambient_fel_source==1&&r->ambient_fel_discontinuity);
            assert(r->i_offset==160&&r->i_first_dts==48000);
            if(mode)assert(MP4_FEL_SeekExact(d.s,160));
            block_t *audio=MP4_FEL_ReadRun(&d,r,160,4);
            assert(audio&&audio->i_buffer==4&&!memcmp(audio->p_buffer,"AUD1",4));
            block_Release(audio);
            assert(MP4_FEL_FinishInterleave(&d));
            assert(position==216&&sys.context.i_lastseqnumber==9);
            FragResetContext(&sys);
            assert(!blocks&&mid_frees==1&&el_frees==1);
            assert(!sys.ambient_fel_replay_count&&!sys.ambient_fel_replay_end);
        }
    }
}
/* Fill the real replay queue to its boundary, then replay each independent
 * audio fragment with a changing duration and an implicit next clock. */
static void test_multiple_timed_intermediates(void)
{
    for(unsigned mode=0;mode<2;mode++){
        for(unsigned n=2;n<=9;n+=(n==2?6:1)){
            reset_stream(mode!=0);extra_count=n;
            mp4_track_t tracks[3]={{.i_track_ID=1,.b_ok=true,.i_timescale=1000,
                .ambient_fel_el_id=2},{.i_track_ID=2,.b_ok=true,.i_timescale=1000,
                .ambient_fel_hidden=true,.ambient_fel_separate=true},
                {.i_track_ID=3,.b_ok=true,.i_timescale=48000}};
            demux_sys_t sys={.i_tracks=3,.track=tracks,.b_seekable=mode!=0};
            demux_t d={.s=&fixture_stream,.p_sys=&sys};
            MP4_Box_data_mfhd_t seq[11]={0};
            MP4_Box_data_tfhd_t tf[10]={0};
            MP4_Box_data_trun_t tr[10]={0};
            MP4_Box_t run[10]={0},header[10]={0},traf[10]={0},mfhd[11]={0};
            MP4_Box_data_tfdt_t dt={.i_base_media_decode_time=48000};
            MP4_Box_t clock={.i_type=ATOM_tfdt,.data.p_tfdt=&dt};
            seq[0].i_sequence_number=7;
            mfhd[0]=(MP4_Box_t){.i_type=ATOM_mfhd,.data.p_mfhd=&seq[0]};
            MP4_Box_t bl={.i_type=ATOM_moof,.i_pos=0,.i_size=100,.p_first=&mfhd[0]};
            for(unsigned i=0;i<=n;i++){
                size_t pos=112+52*i;
                seq[i+1].i_sequence_number=8+i;
                tf[i]=(MP4_Box_data_tfhd_t){.i_track_ID=i<n?3:2,
                    .i_flags=MP4_TFHD_DEFAULT_BASE_IS_MOOF|MP4_TFHD_DFLT_SAMPLE_SIZE|
                        MP4_TFHD_DFLT_SAMPLE_DURATION,
                    .i_default_sample_size=4,.i_default_sample_duration=i+1};
                tr[i]=(MP4_Box_data_trun_t){.i_flags=MP4_TRUN_DATA_OFFSET,
                    .i_sample_count=1,.i_data_offset=48};
                run[i]=(MP4_Box_t){.i_type=ATOM_trun,.data.p_trun=&tr[i]};
                header[i]=(MP4_Box_t){.i_type=ATOM_tfhd,.data.p_tfhd=&tf[i],.p_next=&run[i]};
                traf[i]=(MP4_Box_t){.i_type=ATOM_traf,.p_first=&header[i]};
                mfhd[i+1]=(MP4_Box_t){.i_type=ATOM_mfhd,.data.p_mfhd=&seq[i+1],.p_next=&traf[i]};
                MP4_Box_t *m=i<n?&look_extra[i]:&look_el;
                *m=(MP4_Box_t){.i_type=ATOM_moof,.i_pos=pos,.i_size=40,.p_first=&mfhd[i+1]};
                box(pos,40,"moof");box(pos+40,12,"mdat");
                memset(bytes+pos+48,(int)(i+1),4);
            }
            clock.p_next=&run[0];header[0].p_next=&clock;
            visible=112+52*(n+1);sys.context.ambient_fel_required_end=112;
            look_root=(MP4_Box_t){0};
            const bool ok=MP4_FEL_Lookahead(&d,&bl);
            assert(ok==(n<=8));
            if(!ok){
                assert(!blocks&&!sys.ambient_fel_replay_count&&!sys.context.ambient_fel_el_moof);
                for(unsigned i=0;i<n;i++)assert(extra_frees[i]==1);
                assert(!el_frees&&sys.b_error==!mode);
                if(mode)assert(position==100);
                continue;
            }
            assert(sys.ambient_fel_replay_count==n&&blocks==(mode?0:n+2));
            sys.context.i_lastseqnumber=7;
            tracks[0].ambient_fel_clock_valid=tracks[0].ambient_fel_sequence_proven=true;
            tracks[0].ambient_fel_bl_end=tracks[0].ambient_fel_el_end=1;
            assert(MP4_FEL_IndexAdjacent(&d));
            assert(MP4_FEL_InterleaveReplay(&d));
            assert(!MP4_FEL_InterleaveReplay(&d));
            assert(tracks[1].context.runs.i_count==1);
            assert(tracks[2].context.runs.i_count==n&&tracks[2].i_time==48000);
            stime_t time=48000;
            for(unsigned i=0;i<n;i++){
                mp4_run_t *r=&tracks[2].context.runs.p_array[i];
                uint64_t offset=160+52*i;
                assert(r->ambient_fel_source==i+1&&r->i_first_dts==time);
                assert(r->ambient_fel_size==4&&r->ambient_fel_duration==i+1);
                assert(r->ambient_fel_discontinuity==(i==0));
                assert(MP4_FEL_RunContains(&sys,r,offset,4));
                assert(!MP4_FEL_RunContains(&sys,r,108,4));
                // Legacy nonseekable OverflowCheck uses the BL end.
                // Independent runs must use their own extent instead.
                sys.b_fragmented=true;
                sys.context.i_post_mdat_offset=112;
                assert(MP4_FEL_CheckedRunSize(&d,&tracks[2],r,offset,4)==4);
                assert(MP4_FEL_CheckedRunSize(&d,&tracks[2],r,offset,5)==0);
                assert(MP4_FEL_CheckedRunSize(&d,&tracks[2],r,108,4)==0);
                if(mode)assert(MP4_FEL_SeekExact(d.s,offset));
                block_t *audio=MP4_FEL_ReadRun(&d,r,offset,4);
                assert(audio&&audio->i_buffer==4&&audio->p_buffer[0]==i+1);
                block_Release(audio);
                time+=i+1;
                stime_t end;
                assert(MP4_FEL_RunEnd(&tracks[2],r,&end)&&end==time);
            }
            // Seek into the second retained fragment using its own defaults.
            assert(MP4_FEL_SeekIndexedTrack(&tracks[2],48001));
            assert(tracks[2].context.runs.i_current==1&&tracks[2].i_time==48001);
            assert(tracks[2].context.i_trun_sample_pos==212);
            assert(MP4_FEL_SeekIndexedTrack(&tracks[2],time));
            assert(tracks[2].context.runs.i_current==n&&tracks[2].i_time==time);
            assert(MP4_FEL_SeekIndexedTrack(&tracks[2],48000));
            assert(tracks[2].context.runs.i_current==0);
            // Re-enabling a track one sample later must not replay it.
            sys.i_nztime=(48001LL*CLOCK_FREQ+47999)/48000;
            tracks[2].i_next_block_flags=0;
            assert(MP4_FEL_ResumeIndependentTrack(&sys,&tracks[2]));
            assert(tracks[2].context.runs.i_current==1);
            assert(tracks[2].i_next_block_flags&BLOCK_FLAG_DISCONTINUITY);
            // If already ahead, a second enable must not rewind.
            sys.i_nztime=CLOCK_FREQ;
            assert(MP4_FEL_ResumeIndependentTrack(&sys,&tracks[2]));
            assert(tracks[2].context.runs.i_current==1);
            // The fixture did not consume the track cursor: retirement
            // still advances the implicit clock and marks the gap.
            tracks[2].i_next_block_flags=0;
            assert(MP4_FEL_FinishInterleave(&d));
            assert(tracks[2].i_time==time);
            assert(tracks[2].i_next_block_flags&BLOCK_FLAG_DISCONTINUITY);
            assert(position==visible&&sys.context.i_lastseqnumber==8+n);
            assert(!blocks&&el_frees==1&&!sys.ambient_fel_replay_count);
            for(unsigned i=0;i<n;i++)assert(extra_frees[i]==1);
        }
    }
}
/* A terminal EL moof may also carry audio. Its implicit base depends on
 * the preceding hidden traf, which must not be indexed a second time. */
static void test_mixed_terminal(void)
{
    for(unsigned mode=0;mode<2;mode++){
        for(unsigned overlap=0;overlap<2;overlap++){
            reset_stream(mode!=0);
            mp4_track_t track[3]={{.i_track_ID=1,.b_ok=true,.i_timescale=1000,
                .ambient_fel_el_id=2},{.i_track_ID=2,.b_ok=true,.i_timescale=1000,
                .ambient_fel_hidden=true,.ambient_fel_separate=true},
                {.i_track_ID=3,.b_ok=true,.i_timescale=48000}};
            demux_sys_t sys={.i_tracks=3,.track=track,.b_seekable=mode!=0};
            demux_t d={.s=&fixture_stream,.p_sys=&sys};
            MP4_Box_data_mfhd_t seq[2]={{.i_sequence_number=7},{.i_sequence_number=8}};
            MP4_Box_data_tfhd_t tf[2]={{.i_track_ID=2,
                .i_flags=MP4_TFHD_DEFAULT_BASE_IS_MOOF|MP4_TFHD_DFLT_SAMPLE_SIZE|
                    MP4_TFHD_DFLT_SAMPLE_DURATION,
                .i_default_sample_size=4,.i_default_sample_duration=1},
                {.i_track_ID=3,.i_flags=MP4_TFHD_DFLT_SAMPLE_SIZE|MP4_TFHD_DFLT_SAMPLE_DURATION,
                .i_default_sample_size=4,.i_default_sample_duration=1024}};
            MP4_Box_data_trun_t tr[2]={{.i_flags=MP4_TRUN_DATA_OFFSET,
                .i_sample_count=1,.i_data_offset=48},
                {.i_flags=overlap?MP4_TRUN_DATA_OFFSET:0,.i_sample_count=1,
                 .i_data_offset=overlap?-4:0}};
            MP4_Box_t run[2]={{.i_type=ATOM_trun,.data.p_trun=&tr[0]},
                {.i_type=ATOM_trun,.data.p_trun=&tr[1]}};
            MP4_Box_data_tfdt_t dt={.i_base_media_decode_time=48000};
            MP4_Box_t clock={.i_type=ATOM_tfdt,.data.p_tfdt=&dt,.p_next=&run[1]};
            MP4_Box_t header[2]={{.i_type=ATOM_tfhd,.data.p_tfhd=&tf[0],.p_next=&run[0]},
                {.i_type=ATOM_tfhd,.data.p_tfhd=&tf[1],.p_next=&clock}};
            MP4_Box_t traf[2]={{.i_type=ATOM_traf,.p_first=&header[0]},
                {.i_type=ATOM_traf,.p_first=&header[1]}};
            traf[0].p_next=&traf[1];
            MP4_Box_t mfhd[2]={{.i_type=ATOM_mfhd,.data.p_mfhd=&seq[0]},
                {.i_type=ATOM_mfhd,.data.p_mfhd=&seq[1],.p_next=&traf[0]}};
            MP4_Box_t bl={.i_type=ATOM_moof,.i_pos=0,.i_size=100,.p_first=&mfhd[0]};
            look_el=(MP4_Box_t){.i_type=ATOM_moof,.i_pos=112,.i_size=40,
                .p_first=&mfhd[1],.p_last=&traf[1]};
            look_root=(MP4_Box_t){0};
            box(112,40,"moof");box(152,16,"mdat");
            memcpy(bytes+160,"EL12AUD1",8);visible=168;
            sys.context.ambient_fel_required_end=112;
            assert(MP4_FEL_Lookahead(&d,&bl));
            assert(sys.ambient_fel_replay_count==1&&sys.ambient_fel_replay[0].mixed_el);
            assert(!sys.ambient_fel_replay[0].moof&&sys.context.ambient_fel_el_moof==&look_el);
            bool indexed=MP4_FEL_IndexAdjacent(&d);
            assert(indexed==!overlap);
            if(overlap){
                FragResetContext(&sys);MP4_FEL_ClearReplay(&sys);
                assert(!blocks&&el_frees==1);
                continue;
            }
            assert(track[1].context.runs.i_count==1);
            assert(track[1].context.runs.p_array[0].i_offset==160);
            assert(!track[2].context.runs.i_count);
            assert(tf[1].i_base_data_offset==164&&(tf[1].i_flags&MP4_TFHD_BASE_DATA_OFFSET));
            sys.context.i_lastseqnumber=7;
            track[0].ambient_fel_clock_valid=track[0].ambient_fel_sequence_proven=true;
            track[0].ambient_fel_bl_end=track[0].ambient_fel_el_end=1;
            assert(MP4_FEL_InterleaveReplay(&d));
            assert(track[1].context.runs.i_count==1&&track[2].context.runs.i_count==1);
            mp4_run_t *r=&track[2].context.runs.p_array[0];
            assert(r->ambient_fel_source==1&&r->ambient_fel_duration==1024);
            assert(r->i_offset==164&&r->i_first_dts==48000);
            sys.b_fragmented=true;
            sys.context.i_post_mdat_offset=112;
            assert(MP4_FEL_CheckedRunSize(&d,&track[2],r,164,4)==4);
            assert(MP4_FEL_CheckedRunSize(&d,&track[2],r,168,4)==0);
            if(mode)assert(MP4_FEL_SeekExact(d.s,164));
            block_t *audio=MP4_FEL_ReadRun(&d,r,164,4);
            assert(audio&&!memcmp(audio->p_buffer,"AUD1",4));
            block_Release(audio);
            assert(sys.context.ambient_fel_el_moof==&look_el);
            sys.context.ambient_fel_seek_pending=true;
            sys.context.ambient_fel_seek_time=CLOCK_FREQ;
            assert(MP4_FEL_ApplyInterleaveSeek(&sys));
            assert(!sys.context.ambient_fel_seek_pending&&track[2].i_time==48000);
            assert(track[2].context.i_trun_sample_pos==164);
            // A fully consumed audio track keeps its ordinary
            // continuity flag; only skipped samples mark a gap.
            track[2].context.runs.i_current=track[2].context.runs.i_count;
            track[2].i_next_block_flags=0;
            assert(MP4_FEL_FinishInterleave(&d)&&position==168&&sys.context.i_lastseqnumber==8);
            assert(track[2].i_time==49024&&!track[2].i_next_block_flags);
            assert(!blocks&&el_frees==1);
        }
    }
}
static void test_interleave_clock_limits(void)
{
    assert(!MP4_FEL_ClockFits(-1,1000));
    assert(!MP4_FEL_ClockFits(0,0));
    assert(MP4_FEL_ClockFits(48000,48000));
    mp4_track_t a={.i_time=1,.i_timescale=4000000},
                b={.i_time=1,.i_timescale=2000000};
    assert(MP4_FEL_DtsBefore(&a,&b)&&!MP4_FEL_DtsBefore(&b,&a));
    demux_sys_t sys={0};
    a.context.i_trun_sample_pos=200;b.context.i_trun_sample_pos=100;
    assert(MP4_FEL_PreferTrack(&sys,&b,&a));
    sys.context.ambient_fel_interleaved=true;
    assert(MP4_FEL_PreferTrack(&sys,&a,&b));
    assert(!MP4_FEL_PreferTrack(&sys,&b,&a));
    assert(MP4_FEL_PreferTrack(&sys,&a,NULL));
    a.i_time=2;
    assert(!MP4_FEL_DtsBefore(&a,&b)&&!MP4_FEL_DtsBefore(&b,&a));
    a.i_time=INT64_MAX-1;a.i_timescale=UINT32_MAX;
    b.i_time=INT64_MAX;b.i_timescale=UINT32_MAX;
    assert(MP4_FEL_DtsBefore(&a,&b));
    assert(!MP4_FEL_ClockFits(INT64_MAX,1));
    assert(!MP4_FEL_ClockFits(INT64_MAX,CLOCK_FREQ));
    const stime_t limit=VLC_TICK_MAX-VLC_TICK_0-2*DEMUX_INCREMENT;
    assert(MP4_FEL_ClockFits(limit,CLOCK_FREQ));
    assert(!MP4_FEL_ClockFits(limit+1,CLOCK_FREQ));
    mp4_track_t track={.i_timescale=CLOCK_FREQ,.context.i_default_sample_duration=2};
    MP4_Box_data_trun_t tr={.i_sample_count=1};
    MP4_Box_t box={.i_type=ATOM_trun,.data.p_trun=&tr};
    mp4_run_t run={.i_first_dts=limit-2,.p_trun=&box};
    stime_t end;
    assert(MP4_FEL_RunEnd(&track,&run,&end)&&end==limit);
    run.i_first_dts++;
    assert(!MP4_FEL_RunEnd(&track,&run,&end));
}
static void test_interleave_seek_gap(void)
{
    MP4_Box_data_trun_t tr[2]={{.i_sample_count=2},{.i_sample_count=2}};
    MP4_Box_t box[2]={{.i_type=ATOM_trun,.data.p_trun=&tr[0]},
                     {.i_type=ATOM_trun,.data.p_trun=&tr[1]}};
    mp4_run_t runs[2]={{.i_offset=100,.i_first_dts=10,.p_trun=&box[0]},
        {.i_offset=200,.i_first_dts=30,.p_trun=&box[1],.ambient_fel_source=1,
         .ambient_fel_size=7,.ambient_fel_duration=3,.ambient_fel_discontinuity=true}};
    mp4_track_t t={.i_timescale=1000,.context.i_default_sample_size=4,
        .context.i_default_sample_duration=5,.context.runs.p_array=runs,.context.runs.i_count=2};
    assert(MP4_FEL_SeekIndexedTrack(&t,15)&&t.context.runs.i_current==0);
    assert(t.context.i_trun_sample==1&&t.context.i_trun_sample_pos==104);
    assert(MP4_FEL_SeekIndexedTrack(&t,25)&&t.context.runs.i_current==1);
    assert(t.i_time==30&&t.context.i_trun_sample_pos==200);
    assert(MP4_FEL_SeekIndexedTrack(&t,33)&&t.context.i_trun_sample==1);
    assert(t.i_time==33&&t.context.i_trun_sample_pos==207);
    assert(MP4_FEL_SeekIndexedTrack(&t,36)&&t.context.runs.i_current==2);
    assert(t.i_time==36&&t.context.i_trun_sample_pos==214);
    assert(!MP4_FEL_SeekIndexedTrack(&t,-1));
}
static void test_split_el(void)
{
    for(unsigned mode=0;mode<2;mode++)for(unsigned bad=0;bad<2;bad++){
        reset_stream(mode!=0);have_mid=true;
        mp4_track_t tracks[2]={{.i_track_ID=1,.b_ok=true,.i_timescale=1000,.ambient_fel_el_id=2},
            {.i_track_ID=2,.b_ok=true,.i_timescale=1000,.ambient_fel_hidden=true}};
        demux_sys_t sys={.i_tracks=2,.track=tracks,.b_seekable=mode!=0};
        demux_t d={.s=&fixture_stream,.p_sys=&sys};
        MP4_Box_data_mfhd_t seq[3]={{.i_sequence_number=7},{.i_sequence_number=8},{.i_sequence_number=9}};
        MP4_Box_data_tfhd_t tf[3]={0};
        MP4_Box_data_trun_t tr[3]={0};
        MP4_Box_data_tfdt_t dt[3]={{.i_base_media_decode_time=10},{.i_base_media_decode_time=10},
            {.i_base_media_decode_time=bad?12:11}};
        MP4_Box_t run[3]={0},clock[3]={0},header[3]={0},traf[3]={0},mfhd[3]={0};
        for(unsigned i=0;i<3;i++){
            tf[i]=(MP4_Box_data_tfhd_t){.i_track_ID=i?2:1,
                .i_flags=MP4_TFHD_DEFAULT_BASE_IS_MOOF|MP4_TFHD_DFLT_SAMPLE_SIZE|MP4_TFHD_DFLT_SAMPLE_DURATION,
                .i_default_sample_size=i==2?5:2,.i_default_sample_duration=1};
            tr[i]=(MP4_Box_data_trun_t){.i_flags=MP4_TRUN_DATA_OFFSET,
                .i_sample_count=i?1:2,.i_data_offset=i?48:108};
            run[i]=(MP4_Box_t){.i_type=ATOM_trun,.data.p_trun=&tr[i]};
            clock[i]=(MP4_Box_t){.i_type=ATOM_tfdt,.data.p_tfdt=&dt[i],.p_next=&run[i]};
            header[i]=(MP4_Box_t){.i_type=ATOM_tfhd,.data.p_tfhd=&tf[i],.p_next=&clock[i]};
            traf[i]=(MP4_Box_t){.i_type=ATOM_traf,.p_first=&header[i]};
            mfhd[i]=(MP4_Box_t){.i_type=ATOM_mfhd,.data.p_mfhd=&seq[i],.p_next=&traf[i]};
        }
        MP4_Box_t bl={.i_type=ATOM_moof,.i_pos=0,.i_size=100,.p_first=&mfhd[0]};
        look_mid=(MP4_Box_t){.i_type=ATOM_moof,.i_pos=112,.i_size=40,.p_first=&mfhd[1]};
        look_el=(MP4_Box_t){.i_type=ATOM_moof,.i_pos=162,.i_size=40,.p_first=&mfhd[2]};
        look_root=(MP4_Box_t){0};
        box(112,40,"moof");box(152,10,"mdat");memcpy(bytes+160,"EL",2);
        box(162,40,"moof");box(202,13,"mdat");memcpy(bytes+210,"EL222",5);
        visible=215;
        int ret=FragPrepareChunk(&d,&bl,NULL,INVALID_SEGMENT_TIME,true);
        assert((ret==VLC_SUCCESS)==!bad);
        if(!bad){
            assert(sys.ambient_fel_replay_count==1&&sys.ambient_fel_replay[0].contains_el);
            assert(tracks[1].context.runs.i_count==2);
            mp4_run_t *e=tracks[1].context.runs.p_array;
            assert(e[0].ambient_fel_source==1&&e[0].i_first_dts==10&&e[0].ambient_fel_size==2);
            assert(e[1].ambient_fel_source==UINT_MAX&&e[1].i_first_dts==11&&e[1].ambient_fel_size==5);
            assert(tracks[0].ambient_fel_bl_end==12&&tracks[0].ambient_fel_el_end==12);
            sys.context.i_lastseqnumber=7;
            assert(MP4_FEL_InterleaveReplay(&d));
            assert(MP4_FEL_FinishInterleave(&d));
            assert(position==215&&sys.context.i_lastseqnumber==9);
        }else FragResetContext(&sys);
        assert(!blocks&&mid_frees==1&&el_frees==1);
    }
}
static void test_split_bl(void)
{
    for(unsigned mode=0;mode<2;mode++)for(unsigned bad=0;bad<2;bad++){
        reset_stream(mode!=0);have_mid=true;
        mp4_track_t tracks[2]={{.i_track_ID=1,.b_ok=true,.i_timescale=1000,.ambient_fel_el_id=2},
            {.i_track_ID=2,.b_ok=true,.i_timescale=1000,.ambient_fel_hidden=true}};
        demux_sys_t sys={.i_tracks=2,.track=tracks,.b_seekable=mode!=0};
        demux_t d={.s=&fixture_stream,.p_sys=&sys};
        MP4_Box_data_mfhd_t seq[3]={{.i_sequence_number=7},{.i_sequence_number=8},{.i_sequence_number=9}};
        MP4_Box_data_tfhd_t tf[3]={0};
        MP4_Box_data_trun_t tr[3]={0};
        MP4_Box_data_tfdt_t dt[3]={{.i_base_media_decode_time=10},{.i_base_media_decode_time=bad?12:11},
            {.i_base_media_decode_time=10}};
        MP4_Box_t run[3]={0},clock[3]={0},header[3]={0},traf[3]={0},mfhd[3]={0};
        for(unsigned i=0;i<3;i++){
            tf[i]=(MP4_Box_data_tfhd_t){.i_track_ID=i==2?2:1,
                .i_flags=MP4_TFHD_DEFAULT_BASE_IS_MOOF|MP4_TFHD_DFLT_SAMPLE_SIZE|MP4_TFHD_DFLT_SAMPLE_DURATION,
                .i_default_sample_size=i==1?5:4,.i_default_sample_duration=1};
            tr[i]=(MP4_Box_data_trun_t){.i_flags=MP4_TRUN_DATA_OFFSET,
                .i_sample_count=i==2?2:1,.i_data_offset=i?48:108};
            run[i]=(MP4_Box_t){.i_type=ATOM_trun,.data.p_trun=&tr[i]};
            clock[i]=(MP4_Box_t){.i_type=ATOM_tfdt,.data.p_tfdt=&dt[i],.p_next=&run[i]};
            header[i]=(MP4_Box_t){.i_type=ATOM_tfhd,.data.p_tfhd=&tf[i],.p_next=&clock[i]};
            traf[i]=(MP4_Box_t){.i_type=ATOM_traf,.p_first=&header[i]};
            mfhd[i]=(MP4_Box_t){.i_type=ATOM_mfhd,.data.p_mfhd=&seq[i],.p_next=&traf[i]};
        }
        MP4_Box_t bl={.i_type=ATOM_moof,.i_pos=0,.i_size=100,.p_first=&mfhd[0]};
        look_mid=(MP4_Box_t){.i_type=ATOM_moof,.i_pos=112,.i_size=40,.p_first=&mfhd[1]};
        look_el=(MP4_Box_t){.i_type=ATOM_moof,.i_pos=165,.i_size=40,.p_first=&mfhd[2]};
        look_root=(MP4_Box_t){0};
        box(112,40,"moof");box(152,13,"mdat");memcpy(bytes+160,"BL222",5);
        box(165,40,"moof");box(205,16,"mdat");memcpy(bytes+213,"EL11EL22",8);
        visible=221;
        int ret=FragPrepareChunk(&d,&bl,NULL,INVALID_SEGMENT_TIME,true);
        assert((ret==VLC_SUCCESS)==!bad);
        if(!bad){
            assert(sys.ambient_fel_replay_count==1&&sys.ambient_fel_replay[0].contains_bl);
            assert(tracks[0].context.runs.i_count==2);
            mp4_run_t *e=tracks[0].context.runs.p_array;
            assert(e[0].ambient_fel_source==0&&e[0].i_first_dts==10);
            assert(e[1].ambient_fel_source==1&&e[1].i_first_dts==11&&e[1].ambient_fel_size==5);
            assert(tracks[0].ambient_fel_bl_end==12&&tracks[0].ambient_fel_el_end==12);
            sys.context.i_lastseqnumber=7;
            sys.context.ambient_fel_mdat_start=108;sys.context.i_post_mdat_offset=112;
            assert(MP4_FEL_ValidatePayloads(&sys));
            assert(MP4_FEL_InterleaveReplay(&d));
            if(mode)assert(MP4_FEL_SeekExact(d.s,160));
            block_t *sample=MP4_FEL_ReadRun(&d,&e[1],160,5);
            assert(sample&&!memcmp(sample->p_buffer,"BL222",5));
            block_Release(sample);
            assert(MP4_FEL_FinishInterleave(&d));
            assert(position==221&&sys.context.i_lastseqnumber==9);
        }else FragResetContext(&sys);
        assert(!blocks&&mid_frees==1&&el_frees==1);
    }
}
static void test_mixed_bl_el(void)
{
    for(unsigned mode=0;mode<2;mode++)for(unsigned bad=0;bad<2;bad++){
        reset_stream(mode!=0);
        mp4_track_t tracks[2]={{.i_track_ID=1,.b_ok=true,.i_timescale=1000,.ambient_fel_el_id=2},
            {.i_track_ID=2,.b_ok=true,.i_timescale=1000,.ambient_fel_hidden=true}};
        demux_sys_t sys={.i_tracks=2,.track=tracks,.b_seekable=mode!=0};
        demux_t d={.s=&fixture_stream,.p_sys=&sys};
        MP4_Box_data_mfhd_t seq[3]={{.i_sequence_number=7},{.i_sequence_number=8},{.i_sequence_number=9}};
        MP4_Box_data_tfhd_t tf[3]={0};
        MP4_Box_data_trun_t tr[3]={0};
        MP4_Box_data_tfdt_t dt[3]={{.i_base_media_decode_time=10},{.i_base_media_decode_time=bad?12:11},
            {.i_base_media_decode_time=10}};
        MP4_Box_t run[3]={0},clock[3]={0},header[3]={0},traf[3]={0},mfhd[3]={0};
        for(unsigned i=0;i<3;i++){
            tf[i]=(MP4_Box_data_tfhd_t){.i_track_ID=i==2?2:1,
                .i_flags=MP4_TFHD_DEFAULT_BASE_IS_MOOF|MP4_TFHD_DFLT_SAMPLE_SIZE|MP4_TFHD_DFLT_SAMPLE_DURATION,
                .i_default_sample_size=i==1?5:4,.i_default_sample_duration=1};
            tr[i]=(MP4_Box_data_trun_t){.i_flags=MP4_TRUN_DATA_OFFSET,
                .i_sample_count=i==2?2:1,.i_data_offset=i?48:108};
            run[i]=(MP4_Box_t){.i_type=ATOM_trun,.data.p_trun=&tr[i]};
            clock[i]=(MP4_Box_t){.i_type=ATOM_tfdt,.data.p_tfdt=&dt[i],.p_next=&run[i]};
            header[i]=(MP4_Box_t){.i_type=ATOM_tfhd,.data.p_tfhd=&tf[i],.p_next=&clock[i]};
            traf[i]=(MP4_Box_t){.i_type=ATOM_traf,.p_first=&header[i]};
            mfhd[i]=(MP4_Box_t){.i_type=ATOM_mfhd,.data.p_mfhd=&seq[i],.p_next=&traf[i]};
        }
        MP4_Box_t bl={.i_type=ATOM_moof,.i_pos=0,.i_size=100,.p_first=&mfhd[0]};
        traf[1].p_next=&traf[2];
        tr[2].i_data_offset=53;
        look_el=(MP4_Box_t){.i_type=ATOM_moof,.i_pos=112,.i_size=40,.p_first=&mfhd[1]};
        look_root=(MP4_Box_t){0};
        box(112,40,"moof");box(152,21,"mdat");memcpy(bytes+160,"BL222EL11EL22",13);
        visible=173;
        int ret=FragPrepareChunk(&d,&bl,NULL,INVALID_SEGMENT_TIME,true);
        assert((ret==VLC_SUCCESS)==!bad);
        if(!bad){
            assert(sys.ambient_fel_replay_count==1&&sys.ambient_fel_replay[0].contains_bl&&sys.ambient_fel_replay[0].contains_el&&sys.ambient_fel_replay[0].mixed_el);
            assert(tracks[0].context.runs.i_count==2);
            mp4_run_t *e=tracks[0].context.runs.p_array;
            assert(e[0].ambient_fel_source==0&&e[0].i_first_dts==10);
            assert(e[1].ambient_fel_source==1&&e[1].i_first_dts==11&&e[1].ambient_fel_size==5);
            assert(tracks[0].ambient_fel_bl_end==12&&tracks[0].ambient_fel_el_end==12);
            sys.context.i_lastseqnumber=7;
            sys.context.ambient_fel_mdat_start=108;sys.context.i_post_mdat_offset=112;
            assert(MP4_FEL_ValidatePayloads(&sys));
            assert(MP4_FEL_InterleaveReplay(&d));
            if(mode)assert(MP4_FEL_SeekExact(d.s,160));
            block_t *sample=MP4_FEL_ReadRun(&d,&e[1],160,5);
            assert(sample&&!memcmp(sample->p_buffer,"BL222",5));
            block_Release(sample);
            assert(MP4_FEL_FinishInterleave(&d));
            assert(position==173&&sys.context.i_lastseqnumber==8);
        }else FragResetContext(&sys);
        assert(!blocks&&mid_frees==0&&el_frees==1);
    }
}
static unsigned meta_added,meta_sent;
static uint8_t meta_ids[2];
static vlc_tick_t meta_last_pts;
void es_format_Init(es_format_t *fmt,int cat,vlc_fourcc_t codec)
{
    memset(fmt,0,sizeof(*fmt));fmt->i_cat=cat;fmt->i_codec=codec;
}
void es_format_Clean(es_format_t *fmt){(void)fmt;}
static es_out_id_t *meta_add(es_out_t *out,input_source_t *in,const es_format_t *fmt)
{
    (void)out;(void)in;
    assert(fmt->i_cat==DATA_ES);++meta_added;
    if(fmt->i_codec==VLC_FOURCC('e','m','s','g'))return (es_out_id_t *)&meta_ids[0];
    assert(fmt->i_codec==VLC_FOURCC('p','r','f','t'));
    return (es_out_id_t *)&meta_ids[1];
}
static int meta_send(es_out_t *out,es_out_id_t *id,block_t *b)
{
    (void)out;
    assert(b->i_pts>=meta_last_pts&&b->i_dts==b->i_pts);
    assert(b->i_buffer>=8);
    if(id==(es_out_id_t *)&meta_ids[0])assert(!memcmp(b->p_buffer+4,"emsg",4));
    else {assert(id==(es_out_id_t *)&meta_ids[1]);assert(!memcmp(b->p_buffer+4,"prft",4));}
    meta_last_pts=b->i_pts;++meta_sent;
    block_Release(b);
    return VLC_SUCCESS;
}
static const struct es_out_callbacks meta_callbacks={.add=meta_add,.send=meta_send};
static void test_hierarchical_sidx(void)
{
    reset_stream(true);visible=1024;
    demux_sys_t s={0};demux_t d={.s=&fixture_stream,.p_sys=&s};
    uint8_t boxdata[36]={0};vlc_tick_t pts,dur;bool relative;
    SetDWBE(boxdata+4,1);SetDWBE(boxdata+8,1000);SetDWBE(boxdata+12,3000);
    boxdata[23]=1;SetDWBE(boxdata+24,0x8000002c);SetDWBE(boxdata+28,1000);
    assert(MP4_FEL_ParseTimedBox(&d,ATOM_sidx,boxdata,sizeof(boxdata),100,144,&pts,&dur,&relative));
    assert(s.ambient_fel_sidx_count==1&&s.ambient_fel_sidx[0].index);
    SetDWBE(boxdata+24,100);SetDWBE(boxdata+28,999);
    assert(!MP4_FEL_ParseTimedBox(&d,ATOM_sidx,boxdata,sizeof(boxdata),144,188,&pts,&dur,&relative));
    SetDWBE(boxdata+28,1000);
    assert(MP4_FEL_ParseTimedBox(&d,ATOM_sidx,boxdata,sizeof(boxdata),144,188,&pts,&dur,&relative));
    assert(s.ambient_fel_sidx_count==2&&s.ambient_fel_sidx[0].seen&&s.ambient_fel_sidx[1].depth==1);
    uint8_t event[24]={0};event[4]='s';event[6]='v';
    SetDWBE(event+8,1000);SetDWBE(event+12,250);
    assert(MP4_FEL_ParseTimedBox(&d,VLC_FOURCC('e','m','s','g'),event,sizeof(event),188,220,&pts,&dur,&relative));
    assert(!relative&&pts==13*CLOCK_FREQ/4);
    MP4_FEL_ClearReplay(&s);
}

static void test_nested_metadata(void)
{
    demux_sys_t s={0};demux_t d={.s=&fixture_stream,.p_sys=&s};
    uint8_t payload[28]={1};SetDWBE(payload+4,1000);SetQWBE(payload+8,2000);
    payload[24]='x';payload[26]='y';
    MP4_Box_data_binary_t raw={.p_blob=payload,.i_blob=sizeof(payload)};
    MP4_Box_t event={.i_type=VLC_FOURCC('e','m','s','g'),.i_pos=160,.i_size=36,.i_shortsize=36,.data.p_binary=&raw};
    MP4_Box_t traf={.i_type=ATOM_traf,.p_first=&event};
    MP4_Box_t moof={.i_type=ATOM_moof,.p_first=&traf};
    assert(MP4_FEL_CollectNested(&d,&moof,0));
    assert(s.ambient_fel_meta_count==1&&s.ambient_fel_meta[0].block->i_pts==VLC_TICK_0+2*CLOCK_FREQ);
    assert(!memcmp(s.ambient_fel_meta[0].block->p_buffer+8,payload,sizeof(payload)));
    MP4_FEL_ClearReplay(&s);assert(!blocks);
    raw.i_blob--;
    assert(!MP4_FEL_CollectNested(&d,&moof,0));
}
static void test_timed_boxes(void)
{
    mp4_track_t track={.i_track_ID=1,.b_ok=true,.i_timescale=1000};
    demux_sys_t s={.track=&track,.i_tracks=1};
    es_out_t output={.cbs=&meta_callbacks};
    demux_t d={.s=&fixture_stream,.p_sys=&s,.out=&output};
    for(unsigned mode=0;mode<2;mode++){
        reset_stream(mode!=0);s.b_seekable=mode!=0;s.ambient_fel_collect_meta=true;
        memset(s.ambient_fel_meta_es,0,sizeof(s.ambient_fel_meta_es));
        meta_added=meta_sent=0;meta_last_pts=0;
        // EMSG v1: 1000 Hz, 2 s absolute presentation, arbitrary scheme.
        box(100,36,"emsg");memset(bytes+108,0,28);bytes[108]=1;
        SetDWBE(bytes+112,1000);SetQWBE(bytes+116,2000);SetDWBE(bytes+124,100);
        bytes[132]='x';bytes[134]='y';
        // PRFT v0 precedes the event on the media timeline.
        box(136,28,"prft");memset(bytes+144,0,20);
        SetDWBE(bytes+148,1);SetDWBE(bytes+160,1000);
        // SIDX v0 direct segment reference, EPT 3 s.
        box(164,44,"sidx");memset(bytes+172,0,36);
        SetDWBE(bytes+176,1);SetDWBE(bytes+180,1000);SetDWBE(bytes+184,3000);
        bytes[195]=1;SetDWBE(bytes+196,40);SetDWBE(bytes+200,1000);
        // EMSG v0: scheme/value then delta 250 ms inside indexed segment.
        box(208,32,"emsg");memset(bytes+216,0,24);
        bytes[220]='s';bytes[222]='v';
        SetDWBE(bytes+224,1000);SetDWBE(bytes+228,250);SetDWBE(bytes+232,50);
        box(240,8,"moof");visible=248;
        uint64_t padding=0;unsigned count=0;
        assert(MP4_FEL_SkipGap(&d,&padding,&count,true));
        assert(position==240&&s.ambient_fel_meta_count==3&&s.ambient_fel_meta_boxes==4);
        assert(s.ambient_fel_meta[0].kind==1&&s.ambient_fel_meta[0].block->i_pts==VLC_TICK_0+CLOCK_FREQ);
        assert(s.ambient_fel_meta[1].block->i_pts==VLC_TICK_0+2*CLOCK_FREQ);
        assert(s.ambient_fel_meta[2].block->i_pts==VLC_TICK_0+13*CLOCK_FREQ/4);
        assert(s.ambient_fel_meta[1].block->i_length==CLOCK_FREQ/10);
        assert(s.ambient_fel_meta[2].block->i_length==CLOCK_FREQ/20);
        assert(!memcmp(s.ambient_fel_meta[1].block->p_buffer,bytes+100,36));
        s.i_nztime=0;assert(MP4_FEL_EmitMetadata(&d,false)&&meta_sent==0);
        s.i_nztime=CLOCK_FREQ;assert(MP4_FEL_EmitMetadata(&d,false)&&meta_sent==1);
        s.i_nztime=2*CLOCK_FREQ;assert(MP4_FEL_EmitMetadata(&d,false)&&meta_sent==2);
        assert(MP4_FEL_EmitMetadata(&d,true)&&meta_sent==3&&meta_added==2);
        assert(MP4_FEL_EmitMetadata(&d,true)&&meta_sent==3);
        MP4_FEL_ClearReplay(&s);assert(!blocks);
        position=208;s.ambient_fel_collect_meta=true;
        assert(MP4_FEL_SkipGap(&d,&padding,&count,true));
        assert(s.ambient_fel_meta_count==1&&s.ambient_fel_meta[0].relative);
        assert(!MP4_FEL_ResolveMetadata(&s)); // no proven fragment yet
        MP4_Box_t moof={.i_type=ATOM_moof,.i_pos=240,.i_size=8};
        MP4_Box_data_trun_t tr={.i_sample_count=2};
        MP4_Box_t trun={.i_type=ATOM_trun,.data.p_trun=&tr};
        mp4_run_t run={.i_first_dts=3000,.p_trun=&trun};
        s.context.p_fragment_atom=&moof;s.context.i_post_mdat_offset=248;
        track.context.runs.p_array=&run;track.context.runs.i_count=1;
        track.context.i_default_sample_duration=40;
        assert(MP4_FEL_ResolveMetadata(&s));
        assert(!s.ambient_fel_meta[0].relative&&
            s.ambient_fel_meta[0].block->i_pts==VLC_TICK_0+13*CLOCK_FREQ/4);
        track.context.runs.p_array=NULL;track.context.runs.i_count=0;
        s.context.p_fragment_atom=NULL;
        MP4_FEL_ClearReplay(&s);assert(!blocks);
        position=100;s.ambient_fel_collect_meta=true;bytes[108]=2;
        assert(!MP4_FEL_SkipGap(&d,&padding,&count,true));
        MP4_FEL_ClearReplay(&s);assert(!blocks);
        position=240;s.ambient_fel_collect_meta=true;box(240,8,"uuid");
        assert(MP4_FEL_SkipGap(&d,&padding,&count,true)&&position==240);
        MP4_FEL_ClearReplay(&s);
    }
}
int main(void)
{
    test_timed_boxes();
    test_hierarchical_sidx();
    test_nested_metadata();
    test_mixed_bl_el();
    test_split_bl();
    test_split_el();
    test_interleave_clock_limits();
    test_interleave_seek_gap();
    mp4_track_t track={.i_track_ID=1,.b_ok=true,.i_timescale=1000};
    demux_sys_t sys={.i_tracks=1,.track=&track};
    demux_t demux={.s=&fixture_stream,.p_sys=&sys};
    test_segment_header(&demux,&sys);
    test_empty_mdat_gaps(&demux,&sys);
    test_leading_empty_mdats(&demux,&sys);
    test_exact_sample_read(&demux);
    test_generic_trun_index();
    test_prepare_paired_fragment();
    test_adjacent_lookahead();
    test_timed_intermediate_lookahead();
    test_multiple_timed_intermediates();
    test_mixed_terminal();
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
    uint64_t budget_runs=65534,budget_samples=1048574;
    assert(MP4_FEL_PreflightBudget(&demux,&moof,&required,&budget_runs,&budget_samples));
    assert(budget_runs==65536&&budget_samples==1048576);
    assert(!MP4_FEL_PreflightBudget(&demux,&moof,&required,&budget_runs,&budget_samples));
    assert(budget_runs==65536&&budget_samples==1048576);
    budget_runs=0;budget_samples=1048575;
    assert(!MP4_FEL_PreflightBudget(&demux,&moof,&required,&budget_runs,&budget_samples));
    assert(budget_runs==0&&budget_samples==1048575);
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
