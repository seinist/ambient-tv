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
vec2 size=max(global.SourceSize.xy,vec2(1.0));
    vec2 p=vTexCoord*size-0.5;
    vec2 f=fract(p);
    vec2 ratio=max(global.OutputSize.xy/size,vec2(1.0));
    vec2 sharp=clamp((f-0.5)*ratio+0.5,0.0,1.0);
    FragColor=texture(Source,clamp((floor(p)+sharp+0.5)/size,0.0,1.0));
}
