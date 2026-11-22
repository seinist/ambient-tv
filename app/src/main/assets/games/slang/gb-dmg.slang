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
    float q=floor(clamp(dot(c.rgb,vec3(0.299,0.587,0.114)),0.0,1.0)*3.0+0.5);
    vec3 p=q<0.5?vec3(0.06,0.18,0.08):(q<1.5?vec3(0.22,0.37,0.13):
        (q<2.5?vec3(0.53,0.68,0.25):vec3(0.75,0.83,0.49)));
    FragColor=vec4(p,c.a);
}
