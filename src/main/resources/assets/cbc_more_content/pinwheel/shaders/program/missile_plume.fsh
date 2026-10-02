#version 150
uniform mat4 PlumeView;
uniform mat4 PlumeProjection;
uniform vec3 CameraLocal;
uniform float Time;
uniform float Strength;
uniform int Samples;
uniform vec2 FogRange;
uniform float MinimumRadius;
uniform float Interceptor;
in vec3 localPosition;
out vec4 fragColor;

float hash(vec3 p) {
    p = fract(p * .1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}
float noise(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i),hash(i+vec3(1,0,0)),f.x),
                   mix(hash(i+vec3(0,1,0)),hash(i+vec3(1,1,0)),f.x),f.y),
               mix(mix(hash(i+vec3(0,0,1)),hash(i+vec3(1,0,1)),f.x),
                   mix(hash(i+vec3(0,1,1)),hash(i+vec3(1,1,1)),f.x),f.y),f.z);
}
void main() {
    if (gl_FrontFacing) discard;
    vec3 ray = normalize(localPosition - CameraLocal);
    vec3 safeRay = mix(vec3(.00001), ray, greaterThan(abs(ray), vec3(.00001)));
    vec3 a = (vec3(0,-1.2,-1.2) - CameraLocal) / safeRay;
    vec3 b = (vec3(6.2,1.2,1.2) - CameraLocal) / safeRay;
    vec3 lo = min(a,b), hi = max(a,b);
    float start = max(max(max(lo.x,lo.y),lo.z),0.0);
    float end = min(min(hi.x,hi.y),hi.z);
    if (end <= start) discard;
    float stepSize = (end-start) / float(Samples);
    vec3 radiance = vec3(0);
    float first = -1.0;
    float transmission = 1.0;
    for (int i=0; i<36; i++) {
        if (i >= Samples) break;
        float d = start + (float(i)+.5)*stepSize;
        vec3 p = CameraLocal + ray*d;
        float x = p.x;
        // Noise advects away from the nozzle; the pressure cells stay attached to it.
        vec3 flow = vec3(x*3.4-Time*mix(9.0,16.0,Interceptor),p.yz*13.0);
        float turbulence = noise(flow)*.7 + noise(flow*2.03)*.3;
        float breakup = smoothstep(2.0,5.8,x);
        vec2 bend = vec2(sin(x*3.8-Time*8.0),cos(x*4.3-Time*6.3)) * (.018*x+.14*breakup);
        float r = length(p.yz-bend);
        float width = .18 + .075*x + .22*breakup;
        float tail = 1.0-smoothstep(2.6,6.05,x+(turbulence-.5)*2.0);
        float envelope = exp(-r*r/(width*width*(.6+.9*turbulence))) * tail;
        float core = exp(-r*r/(.013+.004*x+MinimumRadius*MinimumRadius)) * exp(-x*.6) * tail;
        float cells = pow(.5+.5*cos((x-.15)*9.0),8.0)*exp(-x*.6);
        float wisps = smoothstep(mix(.18,.38,breakup),.76,turbulence);
        float density = envelope * wisps * mix(1.15,.68,breakup) + core*(.7+cells*.8);
        if (density < .025) continue;
        if (first < 0.0) first=d;
        float heat = clamp(.95-.085*x-r/width*.35+core*.2+cells*.12,0.0,1.0);
        vec3 color = mix(vec3(1.0,.24,.035),vec3(1.0,.75,.38),smoothstep(.15,.65,heat));
        color = mix(color,vec3(1.0,.97,.82),smoothstep(.65,.95,heat));
        color = mix(color,vec3(.32,.53,1.0),exp(-x*13.0)*.65);
        // A hot white core and closely spaced orange pressure cells distinguish the AIM-9 motor.
        color = mix(color, mix(vec3(1.0,.38,.09),vec3(1.0,.98,.92),smoothstep(.45,.9,heat)), Interceptor);
        float opacity = 1.0-exp(-density*stepSize*2.7);
        radiance += transmission*color*opacity*1.6;
        transmission *= 1.0-opacity*.55;
    }
    if (first < 0.0) discard;
    vec4 viewPoint = PlumeView * vec4(CameraLocal + ray*first,1.0);
    vec4 clip = PlumeProjection * viewPoint;
    gl_FragDepth = clip.z/clip.w*.5+.5;
    // setupNoFog uses Float.MAX_VALUE. Equal endpoints make smoothstep undefined on some drivers.
    float fog = FogRange.y > FogRange.x
        ? 1.0-smoothstep(FogRange.x,FogRange.y,length(viewPoint.xyz)) : 1.0;
    fragColor = vec4(radiance*Strength*fog,1.0);
}
