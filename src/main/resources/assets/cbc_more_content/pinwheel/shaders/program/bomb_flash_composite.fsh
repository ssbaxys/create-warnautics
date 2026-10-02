uniform sampler2D DiffuseSampler0;
uniform float Exposure;
uniform float AmbientGlow;
uniform float Proximity;
uniform vec4 Source0;
uniform vec4 Source1;
uniform vec4 Source2;
uniform vec4 Source3;
uniform vec4 Source4;
uniform vec4 Source5;
uniform vec4 Source6;
uniform vec4 Source7;
uniform vec2 OutSize;
in vec2 texCoord;
out vec4 fragColor;

vec3 burst(vec4 source, float aspect) {
    if (source.z <= .001) return vec3(0);
    vec2 d = (texCoord - source.xy) * vec2(aspect, 1.0);
    float radius = max(.012, source.w);
    float r2 = dot(d,d) / (radius*radius);
    float core = exp(-r2*4.8);
    float envelope = exp(-r2*.65);
    float halo = exp(-r2*.07);
    float streak = exp(-abs(d.y)/max(.002, radius*.065)) * exp(-abs(d.x)/max(.02,radius*3.2));
    vec3 color = mix(vec3(1.0,.34,.075), vec3(1.0,.97,.87), core);
    return color * (core*2.3 + envelope*.64 + halo*.075 + streak*.10) * source.z;
}

void main() {
    vec4 scene = texture(DiffuseSampler0, texCoord);
    float aspect = OutSize.x / max(OutSize.y,1.0);
    vec3 light = burst(Source0,aspect) + burst(Source1,aspect) + burst(Source2,aspect) + burst(Source3,aspect) + burst(Source4,aspect) + burst(Source5,aspect) + burst(Source6,aspect) + burst(Source7,aspect);
    // Bounded combined energy, not a white sheet from a stack of explosions.
    light = light / (vec3(1) + light*.32);
    float adaptation = clamp(Exposure,0.0,1.0);
    vec3 warm = vec3(1.0,.89,.69);
    vec3 result = scene.rgb * (1.0 + AmbientGlow*1.5);
    result += light;
    result = mix(result, max(result,warm), adaptation * (.12 + .28*Proximity));
    // A subtle loss of contrast lingers as the eyes recover, independently of camera direction.
    result += warm * adaptation * .055;
    fragColor = vec4(min(result,vec3(3.2)),scene.a);
}
