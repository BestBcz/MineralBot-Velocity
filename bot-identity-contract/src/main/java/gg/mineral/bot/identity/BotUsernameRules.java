package gg.mineral.bot.identity;

/** Historical formats are candidate evidence only; live requests still match their exact token. */
public final class BotUsernameRules {
    private BotUsernameRules() { }
    public static boolean matchesCurrent(String name) {
        return name != null && name.matches("_[A-Z0-9]{6}");
    }
    public static boolean matchesLegacy(String name, String kit) {
        if (name==null || kit==null) return false;
        String sanitized=kit.replaceAll("[^A-Za-z0-9]", "");
        if (sanitized.isEmpty()) sanitized="Bot";
        // The earliest proxy generated these names with a mixed-case three-character suffix.
        if (matchesPrefix(name, "BOT_" + kit, 3, "[A-Za-z0-9]{3}")
                || matchesPrefix(name, sanitized, 3, "[A-Za-z0-9]{3}")) return true;
        for (int suffix : new int[]{4,6}) {
            String prefix=sanitized.substring(0,Math.min(sanitized.length(),16-suffix));
            if (name.length()==prefix.length()+suffix && name.regionMatches(true,0,prefix,0,prefix.length())
                    && name.substring(prefix.length()).matches("[A-Z0-9]{"+suffix+"}")) return true;
        }
        return false;
    }

    private static boolean matchesPrefix(String name, String prefix, int suffix, String pattern) {
        return name.length() == prefix.length() + suffix
                && name.regionMatches(true, 0, prefix, 0, prefix.length())
                && name.substring(prefix.length()).matches(pattern);
    }
}
