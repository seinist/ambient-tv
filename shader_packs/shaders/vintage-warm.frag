#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D texture;
uniform vec2 textureSize;
varying highp vec2 coords;
void main() {
    vec4 c=texture2D(texture,coords);
    float l=dot(c.rgb,vec3(0.299,0.587,0.114));
    vec3 warm=vec3(c.r*1.04,c.g*0.99,c.b*0.91);
    warm=mix(vec3(l),warm,0.91);
    gl_FragColor=vec4(clamp(warm,0.0,1.0),c.a);
}
