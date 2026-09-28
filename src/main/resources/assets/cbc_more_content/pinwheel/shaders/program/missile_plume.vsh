#version 150
in vec3 Position;
uniform mat4 PlumeView;
uniform mat4 PlumeProjection;
out vec3 localPosition;
void main() {
    localPosition = Position;
    gl_Position = PlumeProjection * PlumeView * vec4(Position, 1.0);
}
