#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 FragColor;

uniform sampler2D uSampler;
uniform float uNoiseGrowth;

// Compensates for the platform zooming the wallpaper surface for parallax. 1.0 is
// a no-op; above 1.0 samples wider so the effect survives the crop. Deliberately
// applied here, on the last pass, so nothing upstream -- least of all the palette --
// ever sees a padded image.
uniform float uCounterScale;

float random (vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453123);
}

void main() {
    vec2 uv = (vTexCoord - 0.5) * uCounterScale + 0.5;
    float noise = random(uv);
    vec3 color = texture(uSampler, uv).rgb;
    FragColor = vec4(mix(color, vec3(noise), uNoiseGrowth), 1.0);
}
