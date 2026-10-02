#version 150
uniform int Mode;
uniform float Time;
uniform vec2 FogRange;
uniform vec4 FogColor;
in vec2 uv;
in vec4 tint;
in float viewDistance;
out vec4 fragColor;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p); f = f*f*(3.0-2.0*f);
    return mix(mix(hash(i), hash(i+vec2(1,0)), f.x), mix(hash(i+vec2(0,1)), hash(i+vec2(1,1)), f.x), f.y);
}
void main() {
    float alpha, light;
    if (Mode == 0) {
        // Broken white crests and a softer blue trough move around each expanding ring.
        float n = noise(vec2(uv.y*70.0, uv.x*6.0-Time*1.8));
        float crest = exp(-pow((uv.x-.48)*5.8, 2.0));
        alpha = crest * (.30+.70*n) * smoothstep(.02,.16,uv.x) * (1.0-smoothstep(.84,.98,uv.x));
        light = .58+.42*smoothstep(.36,.74,n);
    } else {
        vec2 p = uv*2.0-1.0;
        float r = length(p), dome = sqrt(max(0.0,1.0-r*r));
        float rim = exp(-pow((r-.78)*12.0,2.0));
        float glint = exp(-dot(p-vec2(-.28,.32),p-vec2(-.28,.32))*34.0);
        if (Mode == 1) {
            float n = noise(p*5.0+Time*.8);
            alpha = (1.0-smoothstep(.28,1.0,r+(n-.5)*.18))*(.38+.62*dome);
            light = .70+.25*dome+.5*glint;
        } else {
            // Transparent interiors keep bubbles readable under water without a solid white sheet.
            alpha = (rim*.65+glint*.8+dome*.07)*(1.0-smoothstep(.94,1.0,r));
            light = .55+.45*dome+.65*glint;
        }
    }
    float fog = FogRange.y > FogRange.x ? smoothstep(FogRange.x,FogRange.y,viewDistance) : 0.0;
    alpha *= tint.a*(1.0-fog);
    if (alpha < .002) discard;
    fragColor = vec4(mix(tint.rgb*light,FogColor.rgb,fog),alpha);
}
