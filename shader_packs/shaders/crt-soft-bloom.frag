#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D texture;
uniform vec2 textureSize;
varying highp vec2 coords;
void main() {
    vec2 d = 1.0/max(textureSize,vec2(1.0));
    vec3 center = texture2D(texture,coords).rgb;
    vec3 h = texture2D(texture,coords+vec2(d.x,0.0)).rgb
           + texture2D(texture,coords-vec2(d.x,0.0)).rgb;
    vec3 v = texture2D(texture,coords+vec2(0.0,d.y)).rgb
           + texture2D(texture,coords-vec2(0.0,d.y)).rgb;
    vec3 diagonal = texture2D(texture,coords+d).rgb
                  + texture2D(texture,coords-d).rgb
                  + texture2D(texture,coords+vec2(d.x,-d.y)).rgb
                  + texture2D(texture,coords+vec2(-d.x,d.y)).rgb;
    vec3 bloom = max((h+v)*0.13+diagonal*0.06-vec3(0.28),vec3(0.0));
    float scan = 0.85+0.15*cos(coords.y*textureSize.y*6.2831853);
    gl_FragColor = vec4(clamp(center*scan+bloom*0.32,0.0,1.0),1.0);
}
