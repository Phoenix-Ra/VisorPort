#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

out vec3 pos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    // PORT-1.21.11: IViewRotMat no longer exists in any of the shipped include GLSLs, and
    // vanilla's own end portal shader passes the raw position through here as well.
    pos = Position;
}
