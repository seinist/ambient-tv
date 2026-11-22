#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D texture;
uniform vec2 textureSize;
varying highp vec2 coords;
void main() {
    vec2 d=1.0/max(textureSize,vec2(1.0));
    vec4 c=texture2D(texture,coords);
    vec3 n=texture2D(texture,coords+vec2(0.0,d.y)).rgb;
    vec3 s=texture2D(texture,coords-vec2(0.0,d.y)).rgb;
    vec3 e=texture2D(texture,coords+vec2(d.x,0.0)).rgb;
    vec3 w=texture2D(texture,coords-vec2(d.x,0.0)).rgb;
    vec3 mean=(n+s+e+w)*0.25;
    vec3 low=min(c.rgb,min(min(n,s),min(e,w)));
    vec3 high=max(c.rgb,max(max(n,s),max(e,w)));
    float edge=smoothstep(0.03,0.27,dot(high-low,vec3(0.299,0.587,0.114)));
    vec3 outColor=mix(c.rgb,c.rgb+0.47*(c.rgb-mean),edge);
    gl_FragColor=vec4(clamp(outColor,max(vec3(0.0),low-vec3(0.035)),
                           min(vec3(1.0),high+vec3(0.035))),c.a);
}
