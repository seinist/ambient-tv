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
    vec2 d=vec2(1.0/max(global.SourceSize.x,1.0),0.0);
    vec4 c=texture(Source,vTexCoord);
    vec3 l=texture(Source,clamp(vTexCoord-d,vec2(0.0),vec2(1.0))).rgb;
    vec3 r=texture(Source,clamp(vTexCoord+d,vec2(0.0),vec2(1.0))).rgb;
    // First pass: low-cost horizontal phosphor diffusion at native source size.
    FragColor=vec4((c.rgb*0.64 + l*0.18 + r*0.18),c.a);
}
