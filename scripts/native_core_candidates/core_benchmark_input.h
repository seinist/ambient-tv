// SPDX-License-Identifier: GPL-2.0-or-later
#pragma once
#include <libretro.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdlib>
#include <sstream>
#include <stdexcept>
#include <string>
#include <tuple>
#include <vector>

struct BenchmarkInput {
    using Key=std::tuple<unsigned,unsigned,unsigned,unsigned>;
    uint16_t mask=0;
    std::array<int16_t,7> extra{};
    std::vector<std::pair<Key,int16_t>> values;
    unsigned ports=1;

    const int16_t* find(unsigned port,unsigned device,unsigned index,unsigned id) const {
        const Key key{port,device,index,id};
        const auto it=std::lower_bound(values.begin(),values.end(),key,
            [](const auto& entry,const Key& wanted){return entry.first<wanted;});
        return it!=values.end() && it->first==key?&it->second:nullptr;
    }
    int16_t state(unsigned port,unsigned device,unsigned index,unsigned id) const {
        if(port>=ports)return 0;
        device &= RETRO_DEVICE_MASK;
        if(device==RETRO_DEVICE_JOYPAD && !index) {
            uint16_t result=port==0?mask:0;
            if(auto* value=find(port,device,0,RETRO_DEVICE_ID_JOYPAD_MASK))
                result=static_cast<uint16_t>(*value);
            for(unsigned bit=0;bit<16;++bit) {
                if(auto* value=find(port,device,0,bit)) {
                    const uint16_t bitmask=uint16_t(1u<<bit);
                    result=*value?uint16_t(result|bitmask):uint16_t(result&~bitmask);
                }
            }
            if(id==RETRO_DEVICE_ID_JOYPAD_MASK)return static_cast<int16_t>(result);
            return id<16?((result>>id)&1u):0;
        }
        if(auto* value=find(port,device,index,id))return *value;
        if(port==0 && device==RETRO_DEVICE_ANALOG &&
           index<=RETRO_DEVICE_INDEX_ANALOG_RIGHT && id<=RETRO_DEVICE_ID_ANALOG_Y)
            return extra[index*2+id];
        if(device==RETRO_DEVICE_POINTER && !index) {
            if(id==RETRO_DEVICE_ID_POINTER_COUNT)
                return state(port,device,0,RETRO_DEVICE_ID_POINTER_PRESSED)?1:0;
            if(port==0 && id<=RETRO_DEVICE_ID_POINTER_PRESSED)return extra[4+id];
        }
        return 0;
    }
    static BenchmarkInput parse(const std::string& line) {
        BenchmarkInput frame;
        const auto delimiter=line.find('|');
        std::istringstream legacy(line.substr(0,delimiter));
        std::string value;
        if(!(legacy>>value) || value.empty() || value.front()=='-' || value.front()=='+')
            throw std::invalid_argument("Invalid joypad mask");
        char* end=nullptr;
        const unsigned long n=std::strtoul(value.c_str(),&end,16);
        if(*end || n>65535)throw std::invalid_argument("Invalid joypad mask");
        frame.mask=static_cast<uint16_t>(n);
        legacy>>std::ws;
        if(!legacy.eof()) {
            for(size_t i=0;i<frame.extra.size();++i) {
                int sample;
                if(!(legacy>>sample) || sample< -32767 || sample>32767 ||
                   (i==6 && sample!=0 && sample!=1))
                    throw std::invalid_argument("Invalid analog/pointer fixture");
                frame.extra[i]=static_cast<int16_t>(sample);
            }
            legacy>>std::ws;
            if(!legacy.eof())throw std::invalid_argument("Unexpected input fixture fields");
        }
        if(delimiter!=std::string::npos) {
            std::istringstream extended(line.substr(delimiter+1));
            while(extended>>value) {
                std::replace(value.begin(),value.end(),':',' ');
                std::istringstream token(value);
                unsigned port,device,index,id;int sample;
                if(!(token>>port>>device>>index>>id>>sample) || port>=8 ||
                   device<RETRO_DEVICE_JOYPAD || device>RETRO_DEVICE_POINTER ||
                   index>255 || id>511 || sample< -32768 ||
                   (sample>32767 && !(device==RETRO_DEVICE_JOYPAD &&
                     !index && id==RETRO_DEVICE_ID_JOYPAD_MASK && sample<=65535)) ||
                   frame.values.size()>=256)
                    throw std::invalid_argument("Invalid extended input fixture");
                token>>std::ws;
                if(!token.eof())throw std::invalid_argument("Unexpected extended input fields");
                if((device==RETRO_DEVICE_KEYBOARD && (port!=0 || index!=0 || id>=RETROK_LAST ||
                    (sample!=0 && sample!=1))) ||
                   (device==RETRO_DEVICE_JOYPAD && (index!=0 ||
                     (id!=RETRO_DEVICE_ID_JOYPAD_MASK && (id>=16 || (sample!=0 && sample!=1))))))
                    throw std::invalid_argument("Invalid keyboard/joypad input fixture");
                frame.ports=std::max(frame.ports,port+1);
                frame.values.emplace_back(Key{port,device,index,id},static_cast<int16_t>(sample));
            }
            std::sort(frame.values.begin(),frame.values.end(),
                      [](const auto& a,const auto& b){return a.first<b.first;});
            for(size_t i=1;i<frame.values.size();++i)
                if(frame.values[i-1].first==frame.values[i].first)
                    throw std::invalid_argument("Duplicate extended input");
        }
        return frame;
    }
    uint16_t modifiers() const {
        const auto down=[&](unsigned key){return state(0,RETRO_DEVICE_KEYBOARD,0,key)!=0;};
        return (down(RETROK_LSHIFT)||down(RETROK_RSHIFT)?RETROKMOD_SHIFT:0) |
               (down(RETROK_LCTRL)||down(RETROK_RCTRL)?RETROKMOD_CTRL:0) |
               (down(RETROK_LALT)||down(RETROK_RALT)?RETROKMOD_ALT:0) |
               (down(RETROK_LMETA)||down(RETROK_RMETA)?RETROKMOD_META:0);
    }
    template<class Callback> void keyboard_events(const BenchmarkInput& previous, Callback callback) const {
        for(unsigned key=0;key<RETROK_LAST;++key) {
            const bool before=previous.state(0,RETRO_DEVICE_KEYBOARD,0,key)!=0;
            const bool after=state(0,RETRO_DEVICE_KEYBOARD,0,key)!=0;
            if(before!=after)callback(after,key,0,modifiers());
        }
    }
};
