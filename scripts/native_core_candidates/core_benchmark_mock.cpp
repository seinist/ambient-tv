// SPDX-License-Identifier: GPL-2.0-or-later
// Deterministic synthetic core; no emulator assets or external ROM required.
#include <libretro.h>
#include <array>
#include <cstdint>
#include <cstring>
#include <cassert>
static retro_environment_t env;
static retro_video_refresh_t video;
static retro_audio_sample_batch_t batch;
static retro_input_poll_t poll;
static retro_input_state_t input;
static uint32_t counter;
static unsigned second_controller;
static retro_system_av_info av{};
static void update_av() {
    av.geometry={2,2,2,2,1.0f};
    av.timing={counter<30?60.0:50.0,counter<30?48000.0:44100.0};
}
unsigned retro_api_version(){return RETRO_API_VERSION;}
void retro_set_environment(retro_environment_t cb){env=cb;}
void retro_set_video_refresh(retro_video_refresh_t cb){video=cb;}
void retro_set_audio_sample(retro_audio_sample_t){}
void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb){batch=cb;}
void retro_set_input_poll(retro_input_poll_t cb){poll=cb;}
void retro_set_input_state(retro_input_state_t cb){input=cb;}
void retro_init(){
    counter=0;update_av();
    unsigned options_version=0;
    assert(env(RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION,&options_version) && options_version==2);
    static retro_core_option_v2_definition defs[2]{};
    defs[0].key="mock_mode";defs[0].desc="Mode";
    defs[0].values[0].value="default";defs[0].values[1].value="selected";
    defs[0].default_value="default";
    retro_core_options_v2 opts{nullptr,defs};
    env(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2,&opts);
}
void retro_deinit(){}
void retro_get_system_info(retro_system_info* info){*info={"Mock","1","rom",false,false};}
void retro_get_system_av_info(retro_system_av_info* out){*out=av;}
void retro_set_controller_port_device(unsigned port,unsigned device){
    if(port==1)second_controller=device;
}
bool retro_load_game(const retro_game_info* game){
    retro_variable var{"mock_mode",nullptr};
    if(!game || !game->data || !env(RETRO_ENVIRONMENT_GET_VARIABLE,&var) ||
       !var.value || std::strcmp(var.value,"selected")!=0)return false;
    retro_pixel_format format=RETRO_PIXEL_FORMAT_XRGB8888;
    return env(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT,&format);
}
void retro_unload_game(){}
void retro_run(){
    poll();
    assert(second_controller==RETRO_DEVICE_JOYPAD);
    const auto pad=static_cast<uint16_t>(input(1,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_MASK));
    assert(pad==4);
    assert(input(0,RETRO_DEVICE_KEYBOARD,0,RETROK_a)==1);
    const uint32_t pixel=(counter*131+pad)&0x00ffffffu;
    std::array<uint32_t,6> pixels{pixel,pixel,0xdeadbeef,pixel,pixel,0x12345678};
    video(pixels.data(),2,2,3*sizeof(uint32_t));
    std::array<int16_t,4> sound{static_cast<int16_t>(counter),static_cast<int16_t>(pad),
        static_cast<int16_t>(counter+1),static_cast<int16_t>(pad+1)};
    batch(sound.data(),2);
    ++counter;
    if(counter==30){update_av();assert(env(RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO,&av));}
}
size_t retro_serialize_size(){return sizeof(counter);}
bool retro_serialize(void* data,size_t size){
    if(size!=sizeof(counter))return false;
    std::memcpy(data,&counter,size);return true;
}
bool retro_unserialize(const void* data,size_t size){
    if(size!=sizeof(counter))return false;
    std::memcpy(&counter,data,size);update_av();return true;
}
