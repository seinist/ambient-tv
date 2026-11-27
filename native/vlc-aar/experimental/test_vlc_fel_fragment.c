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
    mp4_track_t track[2]={{.i_track_ID=1,.b_ok=true,.i_timescale=1000,
        .ambient_fel_el_id=2},{.i_track_ID=2,.b_ok=true,.i_timescale=2000,
        .ambient_fel_hidden=true}};
    demux_sys_t sys={.i_tracks=2,.track=track,.b_seekable=true};
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
    free(track[0].context.runs.p_array);free(track[1].context.runs.p_array);
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
            assert(sys.ambient_fel_replay_count==1&&!sys.ambient_fel_replay_ready);
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
            // Finish the BL, then execute the actual replay preparation.
            sys.context.i_lastseqnumber=7;
            tracks[0].ambient_fel_clock_valid=true;
            tracks[0].ambient_fel_sequence_proven=true;
            tracks[0].ambient_fel_bl_end=tracks[0].ambient_fel_el_end=1;
            tracks[2].i_time=7;
            assert(MP4_FEL_AdvanceReplay(&d));
            MP4_FEL_ReleaseCaches(&sys);
            assert(MP4_FEL_ReplayNext(&d));
            assert(el_frees==1&&!mid_frees);
            assert(sys.context.i_lastseqnumber==8&&sys.context.p_fragment_atom==&look_mid);
            assert(sys.context.i_current_box_type==ATOM_mdat);
            assert(tracks[0].ambient_fel_clock_valid&&tracks[0].ambient_fel_bl_end==1);
            assert(!tracks[0].context.runs.i_count&&!tracks[1].context.runs.i_count);
            assert(tracks[2].context.runs.i_count==1&&tracks[2].i_time==48000);
            assert(tracks[2].context.i_trun_sample_pos==160);
            assert(tracks[2].i_next_block_flags&BLOCK_FLAG_DISCONTINUITY);
            assert(MP4_FEL_ValidatePayloads(&sys));
            block_t *audio=mode?MP4_FEL_ReadExact(d.s,160,4):MP4_FEL_ReadCached(&sys,false,160,4);
            assert(audio&&audio->i_buffer==4&&!memcmp(audio->p_buffer,"AUD1",4));
            block_Release(audio);
            assert(MP4_FEL_AdvanceReplay(&d));
            assert(position==216&&sys.context.i_lastseqnumber==9);
            FragResetContext(&sys);
            MP4_FEL_ClearReplay(&sys);MP4_FEL_ClearReplay(&sys);
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
            assert(!MP4_FEL_ReplayNext(&d));
            assert(MP4_FEL_AdvanceReplay(&d));
            assert(!MP4_FEL_AdvanceReplay(&d));
            MP4_FEL_ReleaseCaches(&sys);
            stime_t time=48000;
            for(unsigned i=0;i<n;i++){
                assert(MP4_FEL_ReplayNext(&d));
                assert(!MP4_FEL_ReplayNext(&d));
                assert(sys.ambient_fel_replay_active&&!sys.ambient_fel_replay_ready);
                assert(sys.context.i_lastseqnumber==8+i&&tracks[2].i_time==time);
                assert(tracks[2].context.i_default_sample_duration==i+1);
                uint64_t offset=160+52*i;
                assert(tracks[2].context.i_trun_sample_pos==offset);
                assert(MP4_FEL_ValidatePayloads(&sys));
                block_t *audio=mode?MP4_FEL_ReadExact(d.s,offset,4):
                    MP4_FEL_ReadCached(&sys,false,offset,4);
                assert(audio&&audio->i_buffer==4&&audio->p_buffer[0]==i+1);
                block_Release(audio);
                // Emulate the normal track reader's committed sample advance.
                time+=i+1;tracks[2].i_time=time;
                assert(MP4_FEL_AdvanceReplay(&d));
                MP4_FEL_ReleaseCaches(&sys);
                if(i+1<n)assert(sys.ambient_fel_replay_ready&&!sys.ambient_fel_replay_active);
            }
            assert(position==visible&&sys.context.i_lastseqnumber==8+n);
            assert(!sys.ambient_fel_replay_count&&!sys.ambient_fel_replay_active);
            assert(!MP4_FEL_AdvanceReplay(&d)&&!MP4_FEL_ReplayNext(&d));
            FragResetContext(&sys);MP4_FEL_ClearReplay(&sys);
            assert(!blocks&&el_frees==1);
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
            assert(MP4_FEL_AdvanceReplay(&d));
            assert(!sys.context.ambient_fel_el_moof&&sys.ambient_fel_replay[0].moof==&look_el);
            MP4_FEL_ReleaseCaches(&sys);
            assert(MP4_FEL_ReplayNext(&d)&&!el_frees);
            assert(!track[1].context.runs.i_count&&track[2].context.runs.i_count==1);
            assert(track[2].context.i_trun_sample_pos==164&&track[2].i_time==48000);
            assert(MP4_FEL_ValidatePayloads(&sys));
            if(mode)assert(MP4_FEL_SeekExact(d.s,164));
            block_t *audio=mode?MP4_FEL_ReadExact(d.s,164,4):
                MP4_FEL_ReadCached(&sys,false,164,4);
            assert(audio&&!memcmp(audio->p_buffer,"AUD1",4));
            block_Release(audio);
            assert(MP4_FEL_AdvanceReplay(&d)&&position==168&&sys.context.i_lastseqnumber==8);
            FragResetContext(&sys);MP4_FEL_ClearReplay(&sys);
            assert(!blocks&&el_frees==1);
        }
    }
}
int main(void)
{
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
