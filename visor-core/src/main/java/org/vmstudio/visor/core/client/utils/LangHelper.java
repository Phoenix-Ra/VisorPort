package org.vmstudio.visor.core.client.utils;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.locale.Language;

public class LangHelper {


    public static final String YES_KEY = "visor.options.common.yes";
    public static final String NO_KEY = "visor.options.common.no";
    public static final String ON_KEY = "options.on";
    public static final String OFF_KEY = "options.off";

    public static String getText(String langKey, Object ... params){
        return I18n.get(langKey, params);
    }
    public static boolean existsLangKey(String langKey){
        return Language.getInstance().has(langKey);
    }


    public static String getYes() {
        return LangHelper.getText(YES_KEY);
    }
    public static String getNo() {
        return LangHelper.getText(NO_KEY);
    }
    public static String getOn() {
        return LangHelper.getText(ON_KEY);
    }
    public static String getOff() {
        return LangHelper.getText(OFF_KEY);
    }


}
