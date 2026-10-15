# =====================================================================
# rslib-consumer-rules.pro
# ProGuard rules for a plugin built on RsLib that is obfuscated (for example
# with RsOfuscator). RsLib itself is not processed: it is a separate plugin
# (plugins/RsLib-<version>.jar), given to ProGuard as a library jar with the
# rest of the provided dependencies, and never travels inside the plugin.
#
# What these rules protect is what RsLib, Bukkit, PlaceholderAPI or Velocity
# find by name or by reflection in the plugin. Everything else may be renamed
# and repackaged. Add them to the plugin's own .pro (or -include this file).
# =====================================================================

# RsLib does not travel inside the plugin.
-dontwarn com.rs.lib.**

# Annotations (@EventHandler, @Subscribe, @Inject), generics and inner classes
# are read at runtime.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# --- Entry point: the class that extends RsPlugin -------------------------
# The class may be renamed (adapt plugin.yml so main: follows it), but Bukkit
# creates it through its no-argument constructor and RsLib calls these hooks
# by their names.
-adaptresourcefilecontents plugin.yml
-keepclassmembers public class * extends com.rs.lib.RsPlugin {
    public <init>();
    protected int requiredApiLevel();
    protected boolean onPreEnable();
    protected com.rs.lib.RsSpec buildSpec();
    protected void onInnerEnable();
    protected void onInnerDisable();
}

# --- Listeners and events -------------------------------------------------
# Commands, menus, items, actions, reloadables and cron jobs are registered
# with RsLib by instance, so their classes may be renamed. Bukkit only needs
# the @EventHandler methods, and every event (RsEvent and RsPlayerEvent
# subclasses included) needs its static getHandlerList().
-keepclassmembers class * {
    @org.bukkit.event.EventHandler <methods>;
}
-keepclassmembers class * extends org.bukkit.event.Event {
    public static org.bukkit.event.HandlerList getHandlerList();
    public org.bukkit.event.HandlerList getHandlers();
}

# --- PlaceholderAPI -------------------------------------------------------
# Expansions made with rs().papi().expansion(...) live in RsLib and need
# nothing; a plugin that extends PlaceholderExpansion itself keeps it whole.
-keep class * extends me.clip.placeholderapi.expansion.PlaceholderExpansion { *; }
-dontwarn me.clip.placeholderapi.**
-dontwarn net.milkbowl.vault.**

# --- Enums read from YAML or a database by name ---------------------------
# RsYml.getEnum and saved data use the constant names.
-keepclassmembers enum * {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Public API of the plugin ---------------------------------------------
# By convention the .api package is what other plugins link against.
-keep public class **.api.** {
    public protected *;
}

# --- Velocity plugins (the proxy side of RsLib, com.rs.lib.velocity) ------
# Velocity reads the main class from velocity-plugin.json, builds it through
# its @Inject constructor and calls the @Subscribe methods.
-adaptresourcefilecontents velocity-plugin.json
-keepclassmembers class * {
    @com.google.inject.Inject <init>(...);
    @javax.inject.Inject <init>(...);
    @com.velocitypowered.api.event.Subscribe <methods>;
}
-dontwarn com.velocitypowered.**
