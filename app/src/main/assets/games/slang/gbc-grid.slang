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
    vec2 p=fract(vTexCoord*max(global.SourceSize.xy,vec2(1.0)));
    float edge=smoothstep(0.0,0.15,p.x)*(1.0-smoothstep(0.85,1.0,p.x))*
               smoothstep(0.0,0.15,p.y)*(1.0-smoothstep(0.85,1.0,p.y));
    FragColor=vec4(floor(c.rgb*31.0+0.5)/31.0*mix(0.7,1.0,edge),c.a);
}
