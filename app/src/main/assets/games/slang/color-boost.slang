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
vec4 c=texture(Source,vTexCoord);
    float lum=dot(c.rgb,vec3(0.299,0.587,0.114));
    FragColor=vec4(clamp(vec3(lum)+(c.rgb-vec3(lum))*1.16,0.0,1.0),c.a);
}
