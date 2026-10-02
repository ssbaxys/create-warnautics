#version 150
#extension GL_ARB_explicit_attrib_location : require
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
uniform mat4 WaterView;
uniform mat4 WaterProjection;
out vec2 uv;
out vec4 tint;
out float viewDistance;
void main() {
    vec4 view = WaterView * vec4(Position, 1.0);
    gl_Position = WaterProjection * view;
    uv = UV0;
    tint = Color;
    viewDistance = length(view.xyz);
}
