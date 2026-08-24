#version 300 es
precision mediump float;
out vec4 FragColor;
uniform vec3 uColor;
uniform float uAlpha;
// Flat fill, every soft edge comes from the blur pass and the magnify off the 72px raster.
void main() {
    FragColor = vec4(uColor, uAlpha);
}
