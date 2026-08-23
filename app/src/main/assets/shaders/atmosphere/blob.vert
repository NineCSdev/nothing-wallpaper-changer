#version 300 es
layout (location = 0) in vec2 aPos;
layout (location = 1) in vec3 aColor;
out vec3 ourColor;
uniform mat4 uMVPMatrix;
void main() {
    gl_Position = uMVPMatrix * vec4(aPos, 0.0, 1.0);
    ourColor = aColor;
}
