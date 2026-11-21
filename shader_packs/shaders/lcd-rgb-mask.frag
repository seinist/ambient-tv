#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D texture;
uniform vec2 textureSize;
varying highp vec2 coords;
void main() {
    vec3 c=texture2D(texture,coords).rgb;
    float phase=mod(floor(gl_FragCoord.x),3.0);
    vec3 sub=phase<1.0?vec3(1.0,0.78,0.78):
             (phase<2.0?vec3(0.78,1.0,0.78):vec3(0.78,0.78,1.0));
    float row=0.92+0.08*cos(coords.y*max(textureSize.y,1.0)*6.2831853);
    gl_FragColor=vec4(c*sub*row,1.0);
}
