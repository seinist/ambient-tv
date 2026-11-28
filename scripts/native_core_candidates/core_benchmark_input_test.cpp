// SPDX-License-Identifier: GPL-2.0-or-later
#include "core_benchmark_input.h"
#include <cassert>
int main() {
    auto legacy=BenchmarkInput::parse("8001 1 -2 3 -4 5 -6 1");
    assert(static_cast<uint16_t>(legacy.state(0,RETRO_DEVICE_JOYPAD,0,256))==0x8001);
    assert(legacy.state(0,RETRO_DEVICE_ANALOG,1,1)==-4);
    assert(legacy.state(0,RETRO_DEVICE_POINTER,0,RETRO_DEVICE_ID_POINTER_COUNT)==1);
    auto extended=BenchmarkInput::parse("0 | 1:1:0:256:32768 1:1:0:2:1 0:3:0:97:1 0:2:0:0:-23 0:4:0:13:321");
    assert(extended.ports==2);
    assert(static_cast<uint16_t>(extended.state(1,RETRO_DEVICE_JOYPAD,0,256))==0x8004);
    assert(extended.state(1,RETRO_DEVICE_JOYPAD,0,15)==1);
    assert(extended.state(0,RETRO_DEVICE_MOUSE,0,0)==-23);
    assert(extended.state(0,RETRO_DEVICE_LIGHTGUN,0,13)==321);
    unsigned events=0;
    extended.keyboard_events(legacy,[&](bool down,unsigned key,uint32_t,uint16_t){
        assert(down && key==97);++events;
    });
    legacy.keyboard_events(extended,[&](bool down,unsigned key,uint32_t,uint16_t){
        assert(!down && key==97);++events;
    });
    assert(events==2);
    for(const char* bad:{"0 | 8:1:0:1:1","0 | 0:3:0:97:2","0 | 0:2:0:0:1 0:2:0:0:2","0 | 0:1:0:17:1"}) {
        bool rejected=false;
        try{BenchmarkInput::parse(bad);}catch(const std::invalid_argument&){rejected=true;}
        assert(rejected);
    }
}
