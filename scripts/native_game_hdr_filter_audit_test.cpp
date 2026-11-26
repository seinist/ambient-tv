// SPDX-License-Identifier: GPL-3.0-or-later
// Exhaustive catalog audit through the production Video/Renderer and real EGL.
#define main ambient_filter_fixture_main
#include "native_game_filter_renderer_test.cpp"
#undef main
#include "game_filter_audit_presets.h"
#include "renderers/es2/imagerendereres2.h"
#include "renderers/es3/imagerendereres3.h"
#include <cmath>
#include <cstdlib>
#include <fstream>
#include <functional>
#include <iomanip>
#include <map>
#include <regex>
#include <sstream>

namespace {
using Image = std::vector<Rgba>;
struct Pattern { std::string name; Image pixels; bool flat = false; bool gray = false; };

std::string jsonQuoted(const std::string& value) {
    std::string result = "\"";
    for (char c : value) {
        if (c == '\\' || c == '"') result += '\\';
        if (c == '\n') result += "\\n";
        else result += c;
    }
    return result + '"';
}

struct Audit {
    int version, passed = 0, failed = 0;
    std::ofstream report;
    explicit Audit(int version) : version(version) {
        if (const char* path = std::getenv("AMBIENT_FILTER_AUDIT_REPORT")) report.open(path, std::ios::app);
    }
    void run(const std::string& phase, const std::string& name, const std::function<void()>& test) {
        std::string error;
        try { test(); ++passed; }
        catch (const std::exception& failure) { error = failure.what(); ++failed; }
        const std::string row = "{\"gles\":" + std::to_string(version) + ",\"phase\":" + jsonQuoted(phase) +
            ",\"name\":" + jsonQuoted(name) + ",\"ok\":" + (error.empty() ? "true" : "false") +
            ",\"fallback_expected\":" + ((version==2 && name.rfind("SCALEFX",0)==0) ? "true" : "false") +
            ",\"error\":" + jsonQuoted(error) + "}";
        if (report) { report << row << '\n'; report.flush(); }
        if (!error.empty()) std::fprintf(stderr,"AUDIT FAIL GLES%d [%s] %s: %s\n",version,phase.c_str(),name.c_str(),error.c_str());
        while (glGetError() != GL_NO_ERROR) {}
    }
};

Image readImage() {
    Image result(WIDTH * HEIGHT);
    glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, result.data());
    require(glGetError() == GL_NO_ERROR, "pixel readback generated GL error");
    return result;
}

void uploadImage(Video& video, const Image& image, int alpha = -1) {
    require(image.size() == SOURCE * SOURCE, "bad source fixture dimensions");
    std::vector<uint32_t> packed(image.size());
    for (size_t i = 0; i < image.size(); ++i) {
        auto c = image[i];
        packed[i] = (uint32_t(alpha < 0 ? c[3] : alpha) << 24) |
            (uint32_t(c[0]) << 16) | (uint32_t(c[1]) << 8) | c[2];
    }
    // A fresh packed buffer makes the reference independent of previous uploads;
    // the separate ownership phase also checks reuse and partial core redraws.
    video.onNewFrame(packed.data(), SOURCE, SOURCE, SOURCE * sizeof(uint32_t));
}

std::vector<Pattern> patterns() {
    std::vector<Pattern> result;
    for (Rgba color : {Rgba{0,0,0,255}, Rgba{1,1,1,255}, Rgba{32,32,32,255},
         Rgba{128,128,128,255}, Rgba{238,238,238,255}, Rgba{255,255,255,255},
         Rgba{255,0,0,255}, Rgba{0,255,0,255}, Rgba{0,0,255,255}, Rgba{193,71,142,255}}) {
        result.push_back({"flat/"+std::to_string(color[0])+","+std::to_string(color[1])+","+
            std::to_string(color[2]), Image(SOURCE*SOURCE,color), true,
            color[0] == color[1] && color[1] == color[2]});
    }
    Pattern chart{"color-chart",Image(SOURCE*SOURCE)};
    Pattern ramp{"gray-ramp",Image(SOURCE*SOURCE),false,true};
    const std::array<Rgba,8> colors = {{{255,0,0,255},{0,255,0,255},{0,0,255,255},
        {255,255,0,255},{0,255,255,255},{255,0,255,255},{0,0,0,255},{255,255,255,255}}};
    for(int y=0;y<SOURCE;++y) for(int x=0;x<SOURCE;++x) {
        chart.pixels[y*SOURCE+x]=colors[(x/4+y/8)%8];
        unsigned char c=static_cast<unsigned char>((x*255)/(SOURCE-1));
        ramp.pixels[y*SOURCE+x]={c,c,c,255};
    }
    result.push_back(chart);result.push_back(ramp);
    return result;
}

std::array<double,3> pqCodes(const std::array<double,3>& sdr, double white) {
    std::array<double,3> linear{};
    for(size_t c=0;c<3;++c) {
        double x=std::clamp(sdr[c],0.0,1.0);
        linear[c]=x <= 0.04045 ? x/12.92 : std::pow((x+0.055)/1.055,2.4);
    }
    std::array<double,3> wide={
        0.6274*linear[0]+0.3293*linear[1]+0.0433*linear[2],
        0.0691*linear[0]+0.9195*linear[1]+0.0114*linear[2],
        0.0164*linear[0]+0.0880*linear[1]+0.8956*linear[2]};
    for(auto& x:wide) {
        const double p=std::pow(std::max(0.0,x*white/10000.0),2610.0/16384.0);
        x=255.0*std::pow((3424.0/4096.0+(2413.0/128.0)*p)/(1.0+(2392.0/128.0)*p),2523.0/32.0);
    }
    return wide;
}

void comparePq(const Image& sdr,const Image& pq,int white,const std::string& name) {
    for(int y=0;y<HEIGHT;++y) for(int x=16;x<80;++x) {
        const auto& a=sdr[y*WIDTH+x];const auto& b=pq[y*WIDTH+x];
        std::array<double,3> low{},high{};
        for(size_t c=0;c<3;++c) {
            low[c]=(double(a[c])-0.501)/255.0;
            high[c]=(double(a[c])+0.501)/255.0;
        }
        low=pqCodes(low,white);high=pqCodes(high,white);
        // SDR is read back as RGBA8. Compare against the whole half-LSB source
        // interval, especially near black where one SDR code is many PQ codes.
        for(size_t c=0;c<3;++c) if(b[c] < low[c]-2.1 || b[c] > high[c]+2.1)
            throw std::runtime_error(name+" PQ differs from same-filter SDR at "+std::to_string(x)+","+
                std::to_string(y)+" channel="+std::to_string(c)+" SDR="+std::to_string(a[c])+
                " PQ="+std::to_string(b[c])+" expected interval="+std::to_string(low[c])+".."+std::to_string(high[c]));
        require(a[3] == b[3],name+" PQ wrapper changed alpha");
    }
}

void compareImages(const Image& a,const Image& b,int tolerance,const std::string& label) {
    for(int y=0;y<HEIGHT;++y) for(int x=16;x<80;++x) for(size_t c=0;c<4;++c) {
        int delta=std::abs(int(a[y*WIDTH+x][c])-int(b[y*WIDTH+x][c]));
        if(delta>tolerance) throw std::runtime_error(label+" at "+std::to_string(x)+","+std::to_string(y)+
            " channel="+std::to_string(c)+" reference="+std::to_string(a[y*WIDTH+x][c])+
            " actual="+std::to_string(b[y*WIDTH+x][c])+" delta="+std::to_string(delta));
    }
}

bool expectedFloatFallback(const AuditFilter& filter,int version) {
    return version==2 && filter.name=="SCALEFX";
}

bool sourceLinear(const ShaderManager::Config& config) {
    using Type=ShaderManager::Type;
    return config.type==Type::SHADER_SHARP || config.type==Type::SHADER_CRT || config.type==Type::SHADER_LCD;
}

ShaderManager::Config prefixed(ShaderManager::Config filter,ShaderManager::Pass prefix) {
    filter.params["AMBIENT_PREPEND"]="1";
    filter.customPasses.insert(filter.customPasses.begin(),std::move(prefix));
    return filter;
}

std::vector<AuditFilter> variants() {
    auto result=gameAuditFilters();
    for(float intensity:gameAuditIntensities()) if(intensity!=1.0f)
        for(auto filter:gameAuditFilters(intensity)) if(filter.compact) {
        std::ostringstream label;label<<intensity;
        filter.name += "/"+label.str();
        result.push_back(std::move(filter));
    }
    return result;
}

void auditOutput(Audit& audit) {
    const auto samples=patterns();
    for(const auto& filter:variants()) for(int white:{100,203,400}) {
        audit.run("sdr-to-hdr",filter.name+"/"+std::to_string(white),[&] {
            int before=ambientShaderFallbacks;
            Video plain(options(audit.version),filter.config,false,0,false,false,Rect(0,0,1,1),{});
            Video hdr(options(audit.version),filter.config,false,0,false,false,Rect(0,0,1,1),{});
            setLayout(plain);setLayout(hdr);hdr.setSdrToHdr(white);
            for(const auto& sample:samples) {
                uploadImage(plain,sample.pixels);plain.renderFrame(true);auto sdr=readImage();
                expectBorders(filter.name+" SDR");
                uploadImage(hdr,sample.pixels);hdr.renderFrame(true);auto pq=readImage();
                expectBorders(filter.name+" HDR");
                comparePq(sdr,pq,white,sample.name);
                if(sample.flat && sample.gray && filter.achromatic) for(int y=8;y<HEIGHT-8;++y) for(int x=24;x<72;++x) {
                    const auto c=sdr[y*WIDTH+x];
                    require(std::max({c[0],c[1],c[2]})-std::min({c[0],c[1],c[2]})<=2,
                        sample.name+" neutral source acquired unintended tint");
                }
                if(sample.flat && filter.flatPreserving) {
                    const auto c=sdr[32*WIDTH+48];const auto wanted=sample.pixels.front();
                    for(size_t k=0;k<3;++k) require(std::abs(int(c[k])-int(wanted[k]))<=2,
                        sample.name+" scaling filter changed a flat color");
                }
            }
            const int fallbackDelta=ambientShaderFallbacks-before;
            require(expectedFloatFallback(filter,audit.version) ? fallbackDelta>=2 : fallbackDelta==0,
                "unexpected filter fallback count="+std::to_string(fallbackDelta));
        });
    }
    std::printf("AUDIT: GLES%d SDR/HDR parity complete for 36 filters and %zu intensity variants\n",
        audit.version,variants().size()-36);
}

void auditAlpha(Audit& audit) {
    const Image image(SOURCE*SOURCE,Rgba{193,71,142,255});
    for(const auto& filter:gameAuditFilters()) for(int white:{0,203}) {
        audit.run("xrgb-alpha",filter.name+(white ? "/HDR" : "/SDR"),[&] {
            Video video(options(audit.version),filter.config,false,0,false,false,Rect(0,0,1,1),{});
            setLayout(video);video.setSdrToHdr(white);uploadImage(video,image,0);video.renderFrame(true);
            auto actual=readImage();expectBorders(filter.name+" ignored X byte");
            for(int y=0;y<HEIGHT;++y) for(int x=16;x<80;++x)
                if(actual[y*WIDTH+x][3]!=255) throw std::runtime_error("XRGB unused X=0 became alpha="+
                    std::to_string(actual[y*WIDTH+x][3])+" at "+std::to_string(x)+","+std::to_string(y));
        });
    }
    std::printf("AUDIT: GLES%d XRGB ignored-alpha contract complete\n",audit.version);
}

void auditSoftwareUpload(Audit& audit) {
    const std::vector<int> formats={RETRO_PIXEL_FORMAT_XRGB8888,
        RETRO_PIXEL_FORMAT_HDR10_2101010,RETRO_PIXEL_FORMAT_0RGB1555,RETRO_PIXEL_FORMAT_RGB565};
    for(int format:formats) {
        audit.run("source-upload","format/"+std::to_string(format),[&] {
            std::unique_ptr<Renderer> renderer=audit.version==2 ? std::unique_ptr<Renderer>(new ImageRendererES2()) :
                std::unique_ptr<Renderer>(new ImageRendererES3());
            renderer->setPixelFormat(format);renderer->setShaders(ShaderManager::getShader(pixel()));
            for(unsigned width:{1u,32u,33u,7u,64u}) {
                const unsigned height=width==1 ? 1:17;
                const size_t bpp=(format==RETRO_PIXEL_FORMAT_0RGB1555 || format==RETRO_PIXEL_FORMAT_RGB565) ? 2:4;
                for(size_t padding:{0u,7u,8u}) {
                    const size_t pitch=width*bpp+padding;
                    // No padding after the last row, and an intentionally unaligned base.
                    std::vector<uint8_t> frame(1+(height-1)*pitch+width*bpp,0xa5);
                    Image expected(width*height);
                    for(unsigned y=0;y<height;++y)for(unsigned x=0;x<width;++x) {
                        Rgba color{static_cast<unsigned char>((x*71+y*31+193)%256),
                            static_cast<unsigned char>((x*29+y*53+71)%256),
                            static_cast<unsigned char>((x*17+y*11+142)%256),255};
                        uint32_t packed=(uint32_t(color[0])<<16)|(uint32_t(color[1])<<8)|color[2];
                        if(format==RETRO_PIXEL_FORMAT_HDR10_2101010) {
                            const unsigned r=(x*71+y*31+773)%1024,g=(x*29+y*53+287)%1024,b=(x*17+y*11+569)%1024;
                            packed=(r<<20)|(g<<10)|b;
                            color={static_cast<unsigned char>((r*255+511)/1023),
                                static_cast<unsigned char>((g*255+511)/1023),static_cast<unsigned char>((b*255+511)/1023),255};
                        } else if(format==RETRO_PIXEL_FORMAT_RGB565) {
                            const unsigned r=color[0]>>3,g=color[1]>>2,b=color[2]>>3;
                            packed=(r<<11)|(g<<5)|b;
                            color={static_cast<unsigned char>(std::lround(r*255.0/31.0)),
                                static_cast<unsigned char>(std::lround(g*255.0/63.0)),
                                static_cast<unsigned char>(std::lround(b*255.0/31.0)),255};
                        } else if(format==RETRO_PIXEL_FORMAT_0RGB1555) {
                            const unsigned r=color[0]>>3,g=color[1]>>3,b=color[2]>>3;
                            packed=(r<<10)|(g<<5)|b;
                            color={static_cast<unsigned char>(std::lround(r*255.0/31.0)),
                                static_cast<unsigned char>(std::lround(std::lround(g*63.0/31.0)*255.0/63.0)),
                                static_cast<unsigned char>(std::lround(b*255.0/31.0)),255};
                        }
                        if(audit.version==3 && bpp==4) {
                            // FBO readback sees raw storage, not the texture's sampling swizzle.
                            // Set the ignored X bits opaque for this raw-storage comparison.
                            packed |= format==RETRO_PIXEL_FORMAT_HDR10_2101010 ? 0xc0000000u : 0xff000000u;
                            std::swap(color[0],color[2]);
                        }
                        std::memcpy(frame.data()+1+y*pitch+x*bpp,&packed,bpp);expected[y*width+x]=color;
                    }
                    for(int repeat=0;repeat<3;++repeat) {
                    if(repeat==2) {
                        // A core may redraw only the final pixel and reuse every other byte.
                        uint32_t white=format==RETRO_PIXEL_FORMAT_RGB565 ? 0xffffu :
                            format==RETRO_PIXEL_FORMAT_0RGB1555 ? 0x7fffu :
                            format==RETRO_PIXEL_FORMAT_HDR10_2101010 ? 0x3fffffffu : 0x00ffffffu;
                        if(audit.version==3 && bpp==4)
                            white |= format==RETRO_PIXEL_FORMAT_HDR10_2101010 ? 0xc0000000u : 0xff000000u;
                        std::memcpy(frame.data()+1+(height-1)*pitch+(width-1)*bpp,&white,bpp);
                        expected.back()={255,255,255,255};
                    }
                    const auto original=frame;
                    renderer->onNewFrame(frame.data()+1,width,height,pitch);
                    require(frame==original,"upload modified core pixel bytes or row padding");
                    GLuint framebuffer=0;glGenFramebuffers(1,&framebuffer);glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
                    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,renderer->getTexture(),0);
                    require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"uploaded source unreadable");
                    Image actual(width*height);glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,actual.data());
                    glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(1,&framebuffer);
                    require(glGetError()==GL_NO_ERROR,"source-upload readback GL error");
                    for(size_t i=0;i<actual.size();++i)for(size_t c=0;c<4;++c)
                        if(std::abs(int(actual[i][c])-int(expected[i][c]))>1)
                            throw std::runtime_error("uploaded channel mismatch at pixel="+std::to_string(i)+
                                "/"+std::to_string(actual.size())+" channel="+std::to_string(c)+
                                " width="+std::to_string(width)+" padding="+std::to_string(padding));
                    }
                }
            }
        });
    }
    audit.run("source-upload","same-size-format-and-sampler-switch",[&] {
        std::unique_ptr<Renderer> renderer=audit.version==2 ? std::unique_ptr<Renderer>(new ImageRendererES2()) :
            std::unique_ptr<Renderer>(new ImageRendererES3());
        const uint16_t rgb565[4]={0xf800,0x07e0,0x001f,0xffff};
        const uint16_t rgb1555[4]={0x7c00,0x03e0,0x001f,0x7fff};
        const uint32_t xrgb[4]={0xffff0000,0xff00ff00,0xff0000ff,0xffffffff};
        const uint32_t hdr10[4]={0xfff00000,0xc00ffc00,0xc00003ff,0xffffffff};
        const Image expected={{255,0,0,255},{0,255,0,255},{0,0,255,255},{255,255,255,255}};
        auto chain=ShaderManager::getShader(pixel());
        for (int format:{RETRO_PIXEL_FORMAT_RGB565,RETRO_PIXEL_FORMAT_XRGB8888,
                         RETRO_PIXEL_FORMAT_HDR10_2101010,RETRO_PIXEL_FORMAT_XRGB8888,
                         RETRO_PIXEL_FORMAT_0RGB1555,RETRO_PIXEL_FORMAT_RGB565}) {
            renderer->setPixelFormat(format);
            const bool wide=format==RETRO_PIXEL_FORMAT_XRGB8888 || format==RETRO_PIXEL_FORMAT_HDR10_2101010;
            const void* data=format==RETRO_PIXEL_FORMAT_XRGB8888 ? static_cast<const void*>(xrgb) :
                format==RETRO_PIXEL_FORMAT_HDR10_2101010 ? static_cast<const void*>(hdr10) :
                format==RETRO_PIXEL_FORMAT_0RGB1555 ? static_cast<const void*>(rgb1555) :
                static_cast<const void*>(rgb565);
            chain.linearTexture=wide;
            renderer->setShaders(chain);
            for(int repeat=0;repeat<2;++repeat) {
                renderer->onNewFrame(data,2,2,wide?8:4);
                renderer->prepareShaderPasses();
                glBindTexture(GL_TEXTURE_2D,renderer->getTexture());
                GLint minFilter=0,magFilter=0;
                glGetTexParameteriv(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,&minFilter);
                glGetTexParameteriv(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,&magFilter);
                require(minFilter==(wide?GL_LINEAR:GL_NEAREST)&&magFilter==minFilter,"sampler not updated");
                Image expectedStorage=expected;
                if(audit.version==3) {
                    GLint red=0,blue=0;
                    glGetTexParameteriv(GL_TEXTURE_2D,GL_TEXTURE_SWIZZLE_R,&red);
                    glGetTexParameteriv(GL_TEXTURE_2D,GL_TEXTURE_SWIZZLE_B,&blue);
                    require(red==(wide?GL_BLUE:GL_RED)&&blue==(wide?GL_RED:GL_BLUE),"format swizzle not updated");
                    if(wide) for(auto& color:expectedStorage) std::swap(color[0],color[2]);
                }
                glBindTexture(GL_TEXTURE_2D,0);
                GLuint framebuffer=0;glGenFramebuffers(1,&framebuffer);glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
                glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,renderer->getTexture(),0);
                require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"format switch texture incomplete");
                Image actual(4);glReadPixels(0,0,2,2,GL_RGBA,GL_UNSIGNED_BYTE,actual.data());
                glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(1,&framebuffer);
                require(glGetError()==GL_NO_ERROR,"format switch GL error");
                require(actual==expectedStorage,"same-size format switch corrupted source colors");
            }
        }
        // A paused filter switch must update the existing texture without a core frame.
        chain.linearTexture=true;renderer->setShaders(chain);renderer->prepareShaderPasses();
        glBindTexture(GL_TEXTURE_2D,renderer->getTexture());
        GLint filter=0;glGetTexParameteriv(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,&filter);
        glBindTexture(GL_TEXTURE_2D,0);
        require(filter==GL_LINEAR && glGetError()==GL_NO_ERROR,"paused sampler switch failed");
    });
    std::printf("AUDIT: GLES%d immutable/reused/partially redrawn source, final texel, pitched rows and buffer growth complete\n",audit.version);
}

Rgba referenceToneMapUnit(const std::array<double,3>& code,bool hlg) {
    std::array<double,3> nits{};
    for(size_t c=0;c<3;++c) {
        const double value=code[c];
        if(hlg)nits[c]=value<=0.5 ? value*value/3.0 : (std::exp((value-0.55991073)/0.17883277)+0.28466892)/12.0;
        else {
            const double p=std::pow(value,1.0/78.84375);
            nits[c]=10000.0*std::pow(std::max(p-0.8359375,0.0)/std::max(18.8515625-18.6875*p,0.00001),1.0/0.1593017578125);
        }
    }
    if(hlg) {
        const double scale=std::pow(std::max(nits[0]*0.2627+nits[1]*0.6780+nits[2]*0.0593,0.00001),0.2)*1000.0;
        for(auto& channel:nits)channel*=scale;
    }
    const double luma=nits[0]*0.2627+nits[1]*0.6780+nits[2]*0.0593;
    const double scale=((luma/(luma+203.0))/(1000.0/1203.0))/std::max(luma,0.00001);
    for(auto& channel:nits)channel*=scale;
    std::array<double,3> linear={1.6605*nits[0]-0.5876*nits[1]-0.0728*nits[2],
        -0.1246*nits[0]+1.1329*nits[1]-0.0083*nits[2],-0.0182*nits[0]-0.1006*nits[1]+1.1187*nits[2]};
    Rgba result{0,0,0,255};
    for(size_t c=0;c<3;++c) {
        const double value=std::clamp(linear[c],0.0,1.0);
        const double encoded=value<=0.0031308 ? 12.92*value : 1.055*std::pow(value,1.0/2.4)-0.055;
        require(std::isfinite(encoded),"non-finite CPU transfer reference");result[c]=static_cast<unsigned char>(std::lround(encoded*255.0));
    }
    return result;
}

Rgba referenceToneMap(const Rgba& code,bool hlg) {
    return referenceToneMapUnit({code[0]/255.0,code[1]/255.0,code[2]/255.0},hlg);
}

Image decodedReference(const Image& raw,bool hlg,int version) {
    Video decode(options(version),prefixed(pixel(),gameAuditHdrPass(hlg,false)),false,0,false,false,Rect(0,0,1,1),{});
    decode.setSourceTransfer(hlg ? 2u : 1u);
    setLayout(decode);uploadImage(decode,raw);decode.renderFrame(true);
    auto output=readImage();Image decoded(SOURCE*SOURCE);
    for(int y=0;y<SOURCE;++y) for(int x=0;x<SOURCE;++x)
        decoded[y*SOURCE+x]=output[(HEIGHT-2-2*y)*WIDTH+16+2*x+1];
    return decoded;
}

void auditInput(Audit& audit) {
    const auto samples=patterns();
    for(bool hlg:{false,true}) {
        std::vector<Image> decoded;
        for(const auto& sample:samples) decoded.push_back(decodedReference(sample.pixels,hlg,audit.version));
        audit.run("transfer-reference",hlg ? "HLG" : "PQ",[&] {
            for(size_t i=0;i<samples.size();++i)for(size_t p=0;p<decoded[i].size();++p) {
                const auto expected=referenceToneMap(samples[i].pixels[p],hlg);
                for(size_t c=0;c<3;++c)require(std::abs(int(decoded[i][p][c])-int(expected[c]))<=2,
                    samples[i].name+" production transfer disagrees with double-precision CPU reference");
            }
        });
        for(const auto& filter:variants()) for(int white:{0,203}) {
            audit.run(hlg ? "hlg-input" : "pq-input",filter.name+(white ? "/HDR" : "/SDR"),[&] {
                auto inputConfig=prefixed(filter.config,gameAuditHdrPass(hlg,sourceLinear(filter.config)));
                int before=ambientShaderFallbacks;
                // Unsupported float chains retain the required prefix's nearest
                // output. Compare that documented safe picture with Pixel.
                const auto referenceConfig=expectedFloatFallback(filter,audit.version) ? pixel() : filter.config;
                Video plain(options(audit.version),referenceConfig,false,0,false,false,Rect(0,0,1,1),{});
                Video converted(options(audit.version),inputConfig,false,0,false,false,Rect(0,0,1,1),{});
                converted.setSourceTransfer(hlg ? 2u : 1u);
                setLayout(plain);setLayout(converted);plain.setSdrToHdr(white);converted.setSdrToHdr(white);
                for(size_t i=0;i<samples.size();++i) {
                    uploadImage(plain,decoded[i]);plain.renderFrame(true);const auto reference=readImage();
                    uploadImage(converted,samples[i].pixels);converted.renderFrame(true);const auto actual=readImage();
                    expectBorders(filter.name+" decoded HDR input");
                    compareImages(reference,actual,2,samples[i].name+" decode-before-filter composition");
                }
                require(expectedFloatFallback(filter,audit.version) || ambientShaderFallbacks==before,
                    "unexpected fallback behind required HDR conversion");
            });
        }
    }
    std::printf("AUDIT: GLES%d actual PQ/HLG prefix composition complete\n",audit.version);
}

void auditUnconfirmedInput(Audit& audit) {
    const auto samples=patterns();
    for(bool hlg:{false,true}) for(const auto& filter:gameAuditFilters()) {
        audit.run("unconfirmed-input",filter.name+(hlg ? "/HLG-setting" : "/PQ-setting"),[&] {
            auto config=prefixed(filter.config,gameAuditHdrPass(hlg,sourceLinear(filter.config)));
            const auto referenceConfig=expectedFloatFallback(filter,audit.version) ? pixel() : filter.config;
            Video plain(options(audit.version),referenceConfig,false,0,false,false,Rect(0,0,1,1),{});
            Video unconfirmed(options(audit.version),config,false,0,false,false,Rect(0,0,1,1),{});
            setLayout(plain);setLayout(unconfirmed);unconfirmed.setSourceTransfer(0);
            for(const auto& sample:samples) {
                uploadImage(plain,sample.pixels);plain.renderFrame(true);auto reference=readImage();
                uploadImage(unconfirmed,sample.pixels);unconfirmed.renderFrame(true);auto actual=readImage();
                compareImages(reference,actual,2,sample.name+" unconfirmed HDR must preserve SDR colors");
            }
        });
    }
    std::printf("AUDIT: GLES%d unconfirmed HDR input bypass complete\n",audit.version);
}

void auditTransferSwitch(Audit& audit) {
    const auto sample=patterns().at(10); // Channel-sensitive structured color chart.
    for(bool hlg:{false,true}) {
        const auto decoded=decodedReference(sample.pixels,hlg,audit.version);
        for(const auto& filter:gameAuditFilters())for(int white:{0,203}) {
            audit.run("transfer-switch",filter.name+(hlg ? "/HLG" : "/PQ")+(white ? "/HDR" : "/SDR"),[&] {
                const auto referenceConfig=expectedFloatFallback(filter,audit.version) ? pixel():filter.config;
                auto config=prefixed(filter.config,gameAuditHdrPass(hlg,sourceLinear(filter.config)));
                Video plain(options(audit.version),referenceConfig,false,0,false,false,Rect(0,0,1,1),{});
                Video changed(options(audit.version),config,false,0,false,false,Rect(0,0,1,1),{});
                setLayout(plain);setLayout(changed);plain.setSdrToHdr(white);changed.setSdrToHdr(white);
                const unsigned confirmed=hlg ? 2u:1u;
                for(unsigned transfer:{0u,confirmed,0u,hlg ? 1u:2u,confirmed,99u}) {
                    uploadImage(plain,transfer==confirmed ? decoded:sample.pixels);plain.renderFrame(true);
                    auto reference=readImage();
                    changed.setSourceTransfer(transfer);uploadImage(changed,sample.pixels);changed.renderFrame(true);
                    auto actual=readImage();
                    compareImages(reference,actual,2,"live source transfer="+std::to_string(transfer));
                }
            });
        }
    }
    std::printf("AUDIT: GLES%d confirmed/unconfirmed/mismatched source transfer changes complete\n",audit.version);
}

void uploadHardware(Video& video,const Image& image,bool fp16=false) {
    video.bindHWContext();
    glBindFramebuffer(GL_FRAMEBUFFER,video.getCurrentFramebuffer());
    GLint texture=0;
    glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME,&texture);
    require(texture!=0,"hardware fixture has no shared texture");
    glBindTexture(GL_TEXTURE_2D,texture);
    Image padded(64*64,Rgba{255,0,255,255});
    for(int y=0;y<SOURCE;++y) for(int x=0;x<SOURCE;++x) padded[y*64+x]=image[(SOURCE-1-y)*SOURCE+x];
    if(fp16) {
        std::vector<float> values(padded.size()*4);
        for(size_t i=0;i<padded.size();++i) for(size_t c=0;c<4;++c)values[i*4+c]=padded[i][c]/255.0f;
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA16F,64,64,0,GL_RGBA,GL_FLOAT,values.data());
    } else glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,64,64,0,GL_RGBA,GL_UNSIGNED_BYTE,padded.data());
    require(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE,"hardware fixture FBO incomplete");
    require(glGetError()==GL_NO_ERROR,"hardware fixture texture upload failed");
    glBindTexture(GL_TEXTURE_2D,0);glBindFramebuffer(GL_FRAMEBUFFER,0);glFinish();video.bindMainContext();
    video.onNewFrame(RETRO_HW_FRAME_BUFFER_VALID,SOURCE,SOURCE,0);
}

void auditHardwareCrop(Audit& audit) {
    if(audit.version<3)return;
    auto samples=patterns();
    samples.erase(samples.begin(),samples.begin()+3);
    for(int transfer:{0,1,2}) for(const auto& filter:gameAuditFilters()) {
        audit.run("hardware-crop",filter.name+(transfer==0 ? "/SDR" : transfer==1 ? "/PQ" : "/HLG"),[&] {
            auto config=transfer==0 ? filter.config : prefixed(filter.config,gameAuditHdrPass(transfer==2,sourceLinear(filter.config)));
            const int before=ambientShaderFallbacks;
            // Keep the same input orientation in both renderers. Some authentic
            // edge interpolators are intentionally not reflection-invariant.
            Video plain(options(3),config,true,0,false,false,Rect(0,0,1,1),{});
            Video hardware(options(3,true),config,true,0,false,false,Rect(0,0,1,1),{});
            setLayout(plain);setLayout(hardware);plain.setSourceTransfer(transfer);hardware.setSourceTransfer(transfer);
            for(const auto& sample:samples) {
                Image reversed(SOURCE*SOURCE);
                for(int y=0;y<SOURCE;++y)for(int x=0;x<SOURCE;++x)
                    reversed[y*SOURCE+x]=sample.pixels[(SOURCE-1-y)*SOURCE+x];
                uploadImage(plain,reversed);plain.renderFrame(true);auto reference=readImage();
                uploadHardware(hardware,sample.pixels);hardware.renderFrame(true);auto actual=readImage();
                expectBorders(filter.name+" cropped hardware input");
                compareImages(reference,actual,2,sample.name+" padded/oriented hardware parity");
            }
            require(ambientShaderFallbacks==before,"unexpected hardware filter fallback");
        });
    }
    std::printf("AUDIT: GLES3 padded/cropped hardware parity complete\n");
}

void auditPreciseInput(Audit& audit,bool fp16) {
    if(fp16&&audit.version<3)return;
    auto samples=patterns();
    samples.erase(std::remove_if(samples.begin(),samples.end(),[](const auto& item){return !item.flat;}),samples.end());
    const auto inputOptions=[&] {
        auto result=options(audit.version,fp16);
        if(!fp16)result.pixelFormat=RETRO_PIXEL_FORMAT_HDR10_2101010;
        return result;
    };
    const auto uploadPrecise=[&](Video& video,const Pattern& sample) {
        if(fp16) { uploadHardware(video,sample.pixels,true);return; }
        const auto color=sample.pixels.front();
        const unsigned r=std::min(1023u,unsigned(color[0])*4u+unsigned(color[0]%4));
        const unsigned g=std::min(1023u,unsigned(color[1])*4u+unsigned(color[1]%4));
        const unsigned b=std::min(1023u,unsigned(color[2])*4u+unsigned(color[2]%4));
        std::vector<uint32_t> pixels(SOURCE*SOURCE,(r<<20)|(g<<10)|b);
        const auto original=pixels;
        video.onNewFrame(pixels.data(),SOURCE,SOURCE,SOURCE*4u);
        require(pixels==original,"HDR10 upload modified the core input");
    };
    for(bool hlg:{false,true}) {
        std::vector<Image> decoded;
        audit.run("precise-transfer-reference",std::string(fp16 ? "FP16/" : "RGB10/")+(hlg ? "HLG" : "PQ"),[&] {
            Video decode(inputOptions(),prefixed(pixel(),gameAuditHdrPass(hlg,false)),fp16,0,false,false,Rect(0,0,1,1),{});
            setLayout(decode);decode.setSourceTransfer(hlg ? 2:1);
            for(const auto& sample:samples) {
                uploadPrecise(decode,sample);decode.renderFrame(true);auto actual=at(48,32);
                decoded.push_back(Image(SOURCE*SOURCE,actual));
                const auto color=sample.pixels.front();std::array<double,3> unit{};
                for(size_t c=0;c<3;++c) {
                    unsigned value=std::min(1023u,unsigned(color[c])*4u+unsigned(color[c]%4));
                    unit[c]=fp16 ? color[c]/255.0 : audit.version==2 ? ((value*255u+511u)/1023u)/255.0 : value/1023.0;
                }
                auto expected=referenceToneMapUnit(unit,hlg);
                for(size_t c=0;c<3;++c)require(std::abs(int(actual[c])-int(expected[c]))<=2,
                    sample.name+" precise transfer/channel order differs from CPU reference");
            }
        });
        if(decoded.size()!=samples.size())continue;
        for(const auto& filter:gameAuditFilters())for(int white:{0,203}) {
            audit.run(fp16 ? "fp16-input" : "rgb10-input",filter.name+(hlg ? "/HLG" : "/PQ")+(white ? "/HDR" : "/SDR"),[&] {
                const auto referenceConfig=expectedFloatFallback(filter,audit.version) ? pixel():filter.config;
                auto config=prefixed(filter.config,gameAuditHdrPass(hlg,sourceLinear(filter.config)));
                Video plain(options(audit.version),referenceConfig,fp16,0,false,false,Rect(0,0,1,1),{});
                Video precise(inputOptions(),config,fp16,0,false,false,Rect(0,0,1,1),{});
                setLayout(plain);setLayout(precise);plain.setSdrToHdr(white);precise.setSdrToHdr(white);
                precise.setSourceTransfer(hlg ? 2:1);
                for(size_t i=0;i<samples.size();++i) {
                    uploadImage(plain,decoded[i]);plain.renderFrame(true);auto reference=readImage();
                    uploadPrecise(precise,samples[i]);precise.renderFrame(true);auto actual=readImage();
                    compareImages(reference,actual,2,samples[i].name+" precise-input filter composition");
                    expectBorders(filter.name+" precise input");
                }
            });
        }
    }
    std::printf("AUDIT: GLES%d %s input across all filters complete\n",audit.version,fp16 ? "FP16" : "RGB10");
}

std::string finiteProbe(const std::string& source) {
    const auto body=std::regex_replace(source,std::regex("\\bmain\\s*\\("),"ambient_audit_filter_main(");
    return body+R"(
        void main() {
            ambient_audit_filter_main();
            // Comparisons are false for NaN; infinities also exceed this bound.
            bool finite = all(lessThanEqual(abs(gl_FragColor), vec4(65504.0)));
            gl_FragColor = finite ? vec4(0.0,1.0,0.0,1.0) : vec4(1.0,0.0,0.0,1.0);
        }
    )";
}

void auditFinite(Audit& audit) {
    const auto samples=patterns();
    for(const auto& filter:variants())if(filter.config.type==ShaderManager::Type::SHADER_CUSTOM &&
        !expectedFloatFallback(filter,audit.version)) {
        audit.run("finite-output",filter.name,[&] {
            auto config=filter.config;
            config.customPasses.back().fragment=finiteProbe(config.customPasses.back().fragment);
            const int before=ambientShaderFallbacks;
            Video video(options(audit.version),config,false,0,false,false,Rect(0,0,1,1),{});setLayout(video);
            for(const auto& sample:samples) {
                uploadImage(video,sample.pixels);video.renderFrame(true);auto output=readImage();
                for(int y=0;y<HEIGHT;++y)for(int x=16;x<80;++x)
                    require(output[y*WIDTH+x]==Rgba{0,255,0,255},sample.name+" shader emitted NaN or infinity before framebuffer clamping");
            }
            require(ambientShaderFallbacks==before,"finite-output probe unexpectedly fell back");
        });
    }
    std::printf("AUDIT: GLES%d unclamped final shader values are finite\n",audit.version);
}

// A core may leave shared GL state configured for an unrelated readback/draw.
// Odd capture widths make PACK_ALIGNMENT=8 observable; ROW_LENGTH/SKIP also
// exercise the CPU destination's exact allocation under AddressSanitizer.
struct CaptureStateProbe {
    const std::array<GLenum,13> keys {{GL_PACK_ALIGNMENT,GL_PACK_ROW_LENGTH,GL_PACK_SKIP_ROWS,
        GL_PACK_SKIP_PIXELS,GL_PIXEL_PACK_BUFFER_BINDING,GL_PIXEL_UNPACK_BUFFER_BINDING,
        GL_READ_FRAMEBUFFER_BINDING,GL_DRAW_FRAMEBUFFER_BINDING,GL_CURRENT_PROGRAM,
        GL_ARRAY_BUFFER_BINDING,GL_VERTEX_ARRAY_BINDING,GL_ACTIVE_TEXTURE,GL_TEXTURE_BINDING_2D}};
    const std::array<GLenum,6> caps {{GL_BLEND,GL_DEPTH_TEST,GL_SCISSOR_TEST,GL_CULL_FACE,
        GL_STENCIL_TEST,GL_RASTERIZER_DISCARD}};
    std::map<GLenum,GLint> original;
    std::array<GLboolean,6> enabled {};
    GLint viewport[4] {},scissor[4] {},texture0=0,texture3=0,sampler0=0;
    GLboolean mask[4] {};
    GLuint buffers[3] {},textures[2] {},fbo=0,vao=0,sampler=0;

    explicit CaptureStateProbe(bool bindPbos) {
        for(auto key:keys)glGetIntegerv(key,&original[key]);
        glGetIntegerv(GL_VIEWPORT,viewport);glGetIntegerv(GL_SCISSOR_BOX,scissor);
        glGetBooleanv(GL_COLOR_WRITEMASK,mask);
        for(size_t i=0;i<caps.size();++i)enabled[i]=glIsEnabled(caps[i]);
        glActiveTexture(GL_TEXTURE3);glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture3);
        glActiveTexture(GL_TEXTURE0);glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture0);
        glGetIntegerv(GL_SAMPLER_BINDING,&sampler0);
        glGenTextures(2,textures);glBindTexture(GL_TEXTURE_2D,textures[0]);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
        glGenFramebuffers(1,&fbo);glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[0],0);
        glGenSamplers(1,&sampler);glSamplerParameteri(sampler,GL_TEXTURE_MIN_FILTER,GL_NEAREST_MIPMAP_NEAREST);
        glBindSampler(0,sampler);
        glGenVertexArrays(1,&vao);glBindVertexArray(vao);glGenBuffers(3,buffers);
        glBindBuffer(GL_ARRAY_BUFFER,buffers[0]);glBufferData(GL_ARRAY_BUFFER,64,nullptr,GL_STATIC_DRAW);
        glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,16,reinterpret_cast<void*>(4));glEnableVertexAttribArray(0);
        glVertexAttribPointer(1,2,GL_SHORT,GL_TRUE,4,reinterpret_cast<void*>(2));glDisableVertexAttribArray(1);
        if(bindPbos) {
            glBindBuffer(GL_PIXEL_PACK_BUFFER,buffers[1]);glBufferData(GL_PIXEL_PACK_BUFFER,64,nullptr,GL_STATIC_DRAW);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER,buffers[2]);glBufferData(GL_PIXEL_UNPACK_BUFFER,64,nullptr,GL_STATIC_DRAW);
        }
        glPixelStorei(GL_PACK_ALIGNMENT,8);glPixelStorei(GL_PACK_ROW_LENGTH,45);
        glPixelStorei(GL_PACK_SKIP_ROWS,2);glPixelStorei(GL_PACK_SKIP_PIXELS,3);
        for(auto cap:caps)glEnable(cap);
        glViewport(4,7,11,13);glScissor(1,2,1,1);glColorMask(GL_FALSE,GL_TRUE,GL_FALSE,GL_FALSE);
        glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D,textures[1]);
    }

    std::vector<int64_t> state() const {
        std::vector<int64_t> result;
        for(auto key:keys) { GLint value=0;glGetIntegerv(key,&value);result.push_back(value); }
        GLint values[4] {};
        for(auto key:{GL_VIEWPORT,GL_SCISSOR_BOX,GL_COLOR_WRITEMASK}) {
            glGetIntegerv(key,values);result.insert(result.end(),values,values+4);
        }
        for(auto cap:caps)result.push_back(glIsEnabled(cap));
        GLint active=0;glGetIntegerv(GL_ACTIVE_TEXTURE,&active);glActiveTexture(GL_TEXTURE0);
        for(auto key:{GL_TEXTURE_BINDING_2D,GL_SAMPLER_BINDING}) {
            GLint value=0;glGetIntegerv(key,&value);result.push_back(value);
        }
        glActiveTexture(active);
        for(GLuint index:{0u,1u}) {
            for(auto key:{GL_VERTEX_ATTRIB_ARRAY_ENABLED,GL_VERTEX_ATTRIB_ARRAY_SIZE,
                GL_VERTEX_ATTRIB_ARRAY_TYPE,GL_VERTEX_ATTRIB_ARRAY_NORMALIZED,
                GL_VERTEX_ATTRIB_ARRAY_STRIDE,GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING}) {
                GLint value=0;glGetVertexAttribiv(index,key,&value);result.push_back(value);
            }
            void* pointer=nullptr;glGetVertexAttribPointerv(index,GL_VERTEX_ATTRIB_ARRAY_POINTER,&pointer);
            result.push_back(reinterpret_cast<intptr_t>(pointer));
        }
        return result;
    }

    ~CaptureStateProbe() {
        glPixelStorei(GL_PACK_ALIGNMENT,original[GL_PACK_ALIGNMENT]);
        glPixelStorei(GL_PACK_ROW_LENGTH,original[GL_PACK_ROW_LENGTH]);
        glPixelStorei(GL_PACK_SKIP_ROWS,original[GL_PACK_SKIP_ROWS]);
        glPixelStorei(GL_PACK_SKIP_PIXELS,original[GL_PACK_SKIP_PIXELS]);
        glBindBuffer(GL_PIXEL_PACK_BUFFER,original[GL_PIXEL_PACK_BUFFER_BINDING]);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER,original[GL_PIXEL_UNPACK_BUFFER_BINDING]);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,original[GL_DRAW_FRAMEBUFFER_BINDING]);
        glBindFramebuffer(GL_READ_FRAMEBUFFER,original[GL_READ_FRAMEBUFFER_BINDING]);
        glBindVertexArray(original[GL_VERTEX_ARRAY_BINDING]);glBindBuffer(GL_ARRAY_BUFFER,original[GL_ARRAY_BUFFER_BINDING]);
        glUseProgram(original[GL_CURRENT_PROGRAM]);
        glActiveTexture(GL_TEXTURE3);glBindTexture(GL_TEXTURE_2D,texture3);
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture0);glBindSampler(0,sampler0);
        glActiveTexture(original[GL_ACTIVE_TEXTURE]);glBindTexture(GL_TEXTURE_2D,original[GL_TEXTURE_BINDING_2D]);
        glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);glScissor(scissor[0],scissor[1],scissor[2],scissor[3]);
        glColorMask(mask[0],mask[1],mask[2],mask[3]);
        for(size_t i=0;i<caps.size();++i) { if(enabled[i])glEnable(caps[i]);else glDisable(caps[i]); }
        glDeleteBuffers(3,buffers);glDeleteTextures(2,textures);glDeleteFramebuffers(1,&fbo);
        glDeleteVertexArrays(1,&vao);glDeleteSamplers(1,&sampler);
    }
};

void auditRawCapture(Audit& audit) {
    if(audit.version<3)return;
    for(bool fp16:{false,true})for(int white:{0,203})for(const auto& filter:gameAuditFilters()) {
        if(filter.name!="PIXEL"&&filter.name!="CRT"&&filter.name!="SCALEFX")continue;
        audit.run("raw-capture",filter.name+"/"+(fp16 ? "FP16-PQ-input" : "RGBA8")+(white ? "/PQ-output" : "/SDR-output"),[&] {
            auto config=fp16 ? prefixed(filter.config,gameAuditHdrPass(false,sourceLinear(filter.config))) : filter.config;
            Video video(options(3,true),config,true,0,false,false,Rect(0,0,1,1),{});setLayout(video);
            video.setSourceTransfer(fp16 ? 1:0);
            const Rgba source{193,71,142,0};uploadHardware(video,Image(SOURCE*SOURCE,source),fp16);
            video.setSdrToHdr(white);
            for(int stateMode:{0,1,2}) {
                const int wantedWidth=stateMode ? 31:SOURCE,wantedHeight=stateMode ? 17:SOURCE;
                video.onNewFrame(RETRO_HW_FRAME_BUFFER_VALID,wantedWidth,wantedHeight,0);video.renderFrame(true);
                const auto previous=readImage();
                int width=0,height=0;std::vector<uint8_t> raw;
                if(stateMode) {
                    CaptureStateProbe probe(stateMode==2);const auto state=probe.state();
                    require(glGetError()==GL_NO_ERROR,"shared GL state fixture generated GL error");
                    raw=video.captureRawFrame(width,height);
                    require(glGetError()==GL_NO_ERROR,"capture with foreign pack/draw state generated GL error");
                    require(probe.state()==state,"capture did not restore shared GL state exactly");
                } else raw=video.captureRawFrame(width,height);
                require(glGetError()==GL_NO_ERROR,"raw source capture generated GL error (readback type/format)");
                require(width==wantedWidth&&height==wantedHeight&&raw.size()==size_t(width*height*4),"wrong raw capture dimensions");
                for(size_t i=0;i<raw.size();i+=4)for(size_t c=0;c<3;++c)
                    require(std::abs(int(raw[i+c])-int(source[c]))<=1,"raw capture contains processed/output-encoded or corrupted source RGB");
                for(size_t i=3;i<raw.size();i+=4)require(raw[i]==255,"XRGB/hardware snapshot must be opaque");
                video.renderFrame(true);const auto after=readImage();
                compareImages(previous,after,0,"capture must leave the next filtered frame unchanged");
                expectBorders("post-capture frame");
            }
        });
    }
    std::puts("AUDIT: GLES3 raw RGBA8/FP16 capture preserves filtered output and foreign GL pack/draw state");
}

void auditEffects(Audit& audit) {
    const auto samples=patterns();const auto base=gameAuditFilters();
    for(const auto& effectName:gameAuditEffects()) {
        const auto effect=std::find_if(base.begin(),base.end(),[&](const auto& item){return item.name==effectName;});
        require(effect!=base.end(),"effect missing from production catalog");
        for(const auto& filter:variants()) if(!filter.pack) {
            audit.run("effect-to-hdr",filter.name+"+"+effectName,[&] {
                auto config=filter.config;
                auto pass=effect->config.customPasses.at(0);pass.linear=sourceLinear(filter.config);
                if(config.type==ShaderManager::Type::SHADER_CUSTOM) config.customPasses.insert(config.customPasses.begin(),pass);
                else config=prefixed(config,pass);
                int before=ambientShaderFallbacks;
                Video plain(options(audit.version),config,false,0,false,false,Rect(0,0,1,1),{});
                Video hdr(options(audit.version),config,false,0,false,false,Rect(0,0,1,1),{});
                setLayout(plain);setLayout(hdr);hdr.setSdrToHdr(203);
                for(const auto& sample:samples) {
                    uploadImage(plain,sample.pixels);plain.renderFrame(true);auto reference=readImage();
                    uploadImage(hdr,sample.pixels);hdr.renderFrame(true);auto actual=readImage();
                    expectBorders(filter.name+" effect/HDR");comparePq(reference,actual,203,sample.name);
                }
                require(expectedFloatFallback(filter,audit.version) || ambientShaderFallbacks==before,
                    "unexpected fallback for effect + filter + HDR");
            });
        }
    }
    std::printf("AUDIT: GLES%d all four effect prefixes and filter/intensity HDR combinations complete\n",audit.version);
}
}

int main(int argc,char** argv) {
    const int version=argc>1 && std::string(argv[1])=="--gles2" ? 2:3;
    try {
        Context context(version);Audit audit(version);
        const std::string phase=std::getenv("AMBIENT_FILTER_AUDIT_PHASE") ? std::getenv("AMBIENT_FILTER_AUDIT_PHASE") : "all";
        if(phase=="all"||phase=="output")auditOutput(audit);
        if(phase=="all"||phase=="input")auditInput(audit);
        if(phase=="all"||phase=="unconfirmed")auditUnconfirmedInput(audit);
        if(phase=="all"||phase=="switch")auditTransferSwitch(audit);
        if(phase=="all"||phase=="effects")auditEffects(audit);
        if(phase=="all"||phase=="alpha")auditAlpha(audit);
        if(phase=="all"||phase=="upload")auditSoftwareUpload(audit);
        if(phase=="all"||phase=="hardware")auditHardwareCrop(audit);
        if(phase=="all"||phase=="rgb10")auditPreciseInput(audit,false);
        if(phase=="all"||phase=="fp16")auditPreciseInput(audit,true);
        if(phase=="all"||phase=="finite")auditFinite(audit);
        if(phase=="all"||phase=="capture")auditRawCapture(audit);
        std::printf("AUDIT SUMMARY GLES%d: %d passed, %d failed\n",version,audit.passed,audit.failed);
        return audit.failed ? 1:0;
    } catch(const std::exception& error) {
        std::fprintf(stderr,"AUDIT HARNESS ERROR GLES%d: %s\n",version,error.what());return 2;
    }
}
