#version 330

uniform sampler2D SamplerColor;
uniform sampler2D SamplerDepth;

// 1.21.9 dropped loose uniforms; everything custom travels in one std140 block.
// vec3 is padded to 16 bytes in std140, so these are declared vec4 to keep the
// Java-side put order and the GLSL layout trivially in agreement. bool is not a
// legal std140 member either, hence the ints.
layout(std140) uniform VisorMixedReality {
    mat4 uInverseProjectionView;
    vec4 uHmdViewPositionPad;
    vec4 uHmdPlaneNormalPad;
    vec4 uKeyColorPad;
    int  uAsGrid2x2;
    int  uAlphaMode;
};

#define uHmdViewPosition (uHmdViewPositionPad.xyz)
#define uHmdPlaneNormal  (uHmdPlaneNormalPad.xyz)
#define uKeyColor        (uKeyColorPad.rgb)


in vec2 texCoordinates;
out vec4 fragColor;


vec3 avoidKeyColor(in vec3 color) {
    // mask = 1.0 if all |color−keyColor| < ε
    const float eps = 0.004;
    bvec3 close = lessThanEqual(abs(color - uKeyColor), vec3(eps));
    float mask = float(all(close));

    // if keyColor≈black use +eps, otherwise −eps
    vec3 adjust = mix(vec3(-eps), vec3(eps),
                      float(all(lessThanEqual(uKeyColor, vec3(eps)))));

    return color + mask * adjust;
}

vec3 getFragmentPosition(in vec2 uv) {
    // 26.2: the depth buffer is reversed and the clip volume is zero-to-one wherever the
    // device supports it, so the raw depth value IS the clip-space z. On a device still on
    // the -1..1 convention the Java side bakes the z remap into uInverseProjectionView.
    float z = texture(SamplerDepth, uv).r;
    vec4 clip = vec4(uv * 2.0 - 1.0, z, 1.0);
    vec4 world = uInverseProjectionView * clip;
    return world.xyz / world.w;
}


void main(void) {

    // default fill = keyColor
    fragColor = vec4(uKeyColor, 1.0);

    if (uAsGrid2x2 != 0) {
        // --- 2×2 GRID ---
        vec2 sampleUV = fract(texCoordinates * 2.0);

        if (texCoordinates.x < 0.5 && texCoordinates.y < 0.5) {
            // bottom-left quadrant = full third-person
            fragColor.rgb = texture(SamplerColor, sampleUV).rgb;

        } else if (texCoordinates.y >= 0.5) {
            // top half = front-view pass
            vec3 fragPos = getFragmentPosition(sampleUV);

            if (dot(fragPos - uHmdViewPosition, uHmdPlaneNormal) >= 0.0) {
                // left-top = color (+ possible key-avoid)
                if (texCoordinates.x < 0.5) {
                    vec3 col = texture(SamplerColor, sampleUV).rgb;
                    if (uAlphaMode == 0) col = avoidKeyColor(col);
                    fragColor.rgb = col;

                } else if (uAlphaMode != 0) {
                    // right-top = white mask
                    fragColor.rgb = vec3(1.0);
                }
            }
        }

    } else {
        // --- SIDE-BY-SIDE LAYOUT ---
        vec2 sampleUV = fract(texCoordinates * vec2(2.0, 1.0));


        if (texCoordinates.x >= 0.5) {
            // right half = full third-person
            fragColor.rgb = texture(SamplerColor, sampleUV).rgb;

        } else {
            // left half = front-view + key-avoid
            vec3 fragPos = getFragmentPosition(sampleUV);
            if (dot(fragPos - uHmdViewPosition, uHmdPlaneNormal) >= 0.0) {
                vec3 col = texture(SamplerColor, sampleUV).rgb;
                fragColor.rgb = avoidKeyColor(col);
            }
        }
    }
}
