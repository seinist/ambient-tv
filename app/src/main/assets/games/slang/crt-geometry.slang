#version 450
layout(std140, set = 0, binding = 0) uniform UBO {
    mat4 MVP;
    vec4 OutputSize;
    vec4 OriginalSize;
    vec4 SourceSize;
} global;
#pragma stage vertex
layout(location=0) in vec4 Position;
layout(location=1) in vec2 TexCoord;
layout(location=0) out vec2 vTexCoord;
void main() {
    gl_Position=global.MVP*Position;
    vTexCoord=TexCoord;
}
#pragma stage fragment
layout(location=0) in vec2 vTexCoord;
layout(location=0) out vec4 FragColor;
layout(set=0,binding=2) uniform sampler2D Source;
void main() {
vec2 p=vTexCoord*2.0-1.0;
    vec2 uv=p*(1.0+0.09*dot(p,p))*0.5+0.5;
    if (uv.x<0.0||uv.x>1.0||uv.y<0.0||uv.y>1.0) {FragColor=vec4(0.0,0.0,0.0,1.0);return;}
    float scan=0.83+0.17*cos(uv.y*max(global.SourceSize.y,1.0)*6.2831853);
    FragColor=vec4(texture(Source,uv).rgb*scan*max(0.0,1.0-0.15*dot(p,p)),1.0);
}
