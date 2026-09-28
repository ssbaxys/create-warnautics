#version 150
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
in vec2 cloudUv;
in vec4 tint;
in float viewDistance;
out vec4 fragColor;
float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float noise(vec2 p){
    vec2 i=floor(p),f=fract(p);f=f*f*(3-2*f);
    return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),mix(hash(i+vec2(0,1)),hash(i+vec2(1,1)),f.x),f.y);
}
void main(){
    vec2 p=cloudUv*2-1;
    float n=noise(p*3.6+7)*.72+noise(p*8.1-3)*.28;
    float r=length(p);
    float edge=1-smoothstep(.42,.98,r+(n-.5)*.19);
    float column=sqrt(max(0,1-r*r));
    float density=edge*(.63+.55*n)*column;
    float alpha=(1-exp(-density*2.2))*tint.a*ColorModulator.a;
    // No vanilla alpha<0.1 cutout: thin outer wisps must fade continuously.
    if(alpha<.002)discard;
    float lighting=.69+.25*column+.16*noise(p*3.6+vec2(6.7,7.4));
    vec3 color=tint.rgb*ColorModulator.rgb*lighting;
    float fog=FogEnd>FogStart?smoothstep(FogStart,FogEnd,viewDistance):0;
    fragColor=vec4(mix(color,FogColor.rgb,fog),alpha*(1-fog));
}
