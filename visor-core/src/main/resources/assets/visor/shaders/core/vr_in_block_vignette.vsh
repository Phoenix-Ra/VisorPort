#version 330

in vec3 Position;
in vec2 UV0;

out vec2 texCoordinates;

void main() {
    // PORT-1.21.11: passthrough. The old shader multiplied already-NDC vertices by the
    // ambient ProjMat * ModelViewMat; the quad is now supplied in NDC directly.
    gl_Position = vec4(Position, 1.0);
    texCoordinates = UV0;
}
