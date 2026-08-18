#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec2 UV0;

out vec2 texCoordinates;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // PORT-1.21.11: the quad now carries real UVs (POSITION_TEX) instead of deriving them
    // from gl_VertexID, which only worked by accident of the draw order.
    texCoordinates = UV0;
}
