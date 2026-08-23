#version 300 es
precision mediump float;
in vec3 ourColor;
out vec4 FragColor;
uniform float uAlpha;
// Flat fill, every soft edge comes from the blur pass and the magnify off the 72px raster.
void main() {
    FragColor = vec4(ourColor, uAlpha);
}
