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
    vec2 d=vec2(0.0,1.0/max(global.SourceSize.y,1.0));
    vec4 c=texture(Source,vTexCoord);
    vec3 bloom=(texture(Source,clamp(vTexCoord-d,vec2(0.0),vec2(1.0))).rgb +
                texture(Source,clamp(vTexCoord+d,vec2(0.0),vec2(1.0))).rgb)*0.5;
    float scan=0.84+0.16*cos(vTexCoord.y*max(global.SourceSize.y,1.0)*6.2831853);
    vec3 glow=max(bloom-vec3(0.30),vec3(0.0))*0.30;
    FragColor=vec4(clamp(c.rgb*scan+glow,0.0,1.0),c.a);
}
