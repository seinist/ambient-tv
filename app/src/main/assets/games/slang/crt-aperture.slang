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
vec3 c=texture(Source,vTexCoord).rgb;
    float slot=mod(floor(gl_FragCoord.x),3.0);
    vec3 mask=slot<1.0?vec3(1.0,0.72,0.72):(slot<2.0?vec3(0.72,1.0,0.72):vec3(0.72,0.72,1.0));
    float scan=0.87+0.13*cos(vTexCoord.y*max(global.SourceSize.y,1.0)*6.2831853);
    FragColor=vec4(c*mask*scan,1.0);
}
