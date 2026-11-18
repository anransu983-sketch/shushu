# The daemon is started by name through app_process.
-keep class com.suanran.shushu.probe.ProbeMain { public static void main(java.lang.String[]); }
-keep class com.suanran.shushu.probe.IProbeService { *; }
-keep class com.suanran.shushu.probe.IProbeService$* { *; }
