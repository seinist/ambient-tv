#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D texture;
uniform vec2 textureSize;
varying highp vec2 coords;
void main() {
    vec2 d=vec2(1.0/max(textureSize.x,1.0),0.0);
    vec3 c=texture2D(texture,coords).rgb;
    vec3 l=texture2D(texture,coords-d).rgb;
    vec3 r=texture2D(texture,coords+d).rgb;
    float phase=mod(floor(coords.y*max(textureSize.y,1.0)),2.0);
    vec3 softened=c*0.64+(l+r)*0.18;
    vec3 color=vec3(softened.r*0.91+r.r*0.09,
                    softened.g*0.95+l.g*0.05,
                    mix(softened.b*0.93+r.b*0.07,softened.b*0.93+l.b*0.07,phase));
    gl_FragColor=vec4(clamp(color,0.0,1.0),1.0);
}
