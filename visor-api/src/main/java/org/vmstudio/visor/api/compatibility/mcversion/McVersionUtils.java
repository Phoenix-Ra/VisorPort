package org.vmstudio.visor.api.compatibility.mcversion;


import net.minecraft.resources.Identifier;
import net.minecraft.util.StringUtil;


public class McVersionUtils {
    private McVersionUtils() {
        throw new UnsupportedOperationException("This is an utility class and cannot be instantiated");
    }
    public static Identifier newResourceLoc(String namespace,
                                                  String path){
        return Identifier.fromNamespaceAndPath(namespace, path);
    }
    public static Identifier newResourceLoc(String location){
        return Identifier.parse(location);
    }

    //---------- chat text helpers (moved from SharedConstants to StringUtil in 1.20.3) ----------

    public static String filterText(String text, boolean allowLineBreaks){
        return StringUtil.filterText(text, allowLineBreaks);
    }

    public static boolean isAllowedChatCharacter(char character){
        return StringUtil.isAllowedChatCharacter(character);
    }

}
