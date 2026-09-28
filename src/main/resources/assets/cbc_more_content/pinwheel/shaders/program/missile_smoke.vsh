#version 150
in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;
uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
out vec2 cloudUv;
out vec4 tint;
out float viewDistance;
void main(){
    gl_Position = ProjMat * ModelViewMat * vec4(Position,1);
    cloudUv = UV0;
    tint = Color * texelFetch(Sampler2,UV2/16,0);
    viewDistance = FogShape==0 ? length(Position) : max(length(Position.xz),abs(Position.y));
}
