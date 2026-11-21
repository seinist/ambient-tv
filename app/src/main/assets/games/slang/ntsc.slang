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
    vec3 center=texture(Source,vTexCoord).rgb;
    vec3 left=texture(Source,vTexCoord-d).rgb;
    vec3 right=texture(Source,vTexCoord+d).rgb;
    vec3 soft=center*0.6+(left+right)*0.2;
    FragColor=vec4(vec3(soft.r*0.9+right.r*0.1,soft.g,soft.b*0.9+left.b*0.1),1.0);
}
