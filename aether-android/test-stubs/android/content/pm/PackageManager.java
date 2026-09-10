package android.content.pm;

public class PackageManager {
    public PackageInfo getPackageInfo(String pkg, int flags) {
        return new PackageInfo();
    }
    public ApplicationInfo getApplicationInfo(String pkg, int flags) {
        return new ApplicationInfo();
    }
    public static final int GET_ACTIVITIES = 1;
}
