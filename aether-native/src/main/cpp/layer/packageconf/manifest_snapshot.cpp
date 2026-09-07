#include "manifest_snapshot.hpp"
#include <fstream>
#include <cstring>

namespace aether::l1 {

// ── legacy text ──
bool ManifestSnapshot::serialize(const std::string& outPath) {
    std::ofstream out(outPath, std::ios::binary);
    if (!out) return false;
    out << "PKGN=" << packageName << "\n";
    out << "VER=" << versionName << "\n";
    out << "VCODE=" << versionCode << "\n";
    out << "ACTS=" << activities.size() << "\n";
    for (auto& a : activities) out << a << "\n";
    out << "SVCS=" << services.size() << "\n";
    for (auto& s : services) out << s << "\n";
    out << "PROVS=" << providers.size() << "\n";
    for (auto& p : providers) out << p << "\n";
    out << "PERMS=" << permissions.size() << "\n";
    for (auto& pr : permissions) out << pr << "\n";
    return out.good();
}

bool ManifestSnapshot::deserialize(const std::string& inPath) {
    std::ifstream in(inPath);
    if (!in) return false;
    std::string line;
    while (std::getline(in, line)) {
        if (line.rfind("PKGN=",0)==0) packageName=line.substr(5);
        else if (line.rfind("VER=",0)==0) versionName=line.substr(4);
        else if (line.rfind("VCODE=",0)==0) versionCode=std::stoi(line.substr(6));
        else if (line.rfind("ACTS=",0)==0) { int n=std::stoi(line.substr(5)); activities.clear(); for(int i=0;i<n&&std::getline(in,line);++i) activities.push_back(line); }
        else if (line.rfind("SVCS=",0)==0) { int n=std::stoi(line.substr(5)); services.clear(); for(int i=0;i<n&&std::getline(in,line);++i) services.push_back(line); }
        else if (line.rfind("PROVS=",0)==0){ int n=std::stoi(line.substr(6)); providers.clear(); for(int i=0;i<n&&std::getline(in,line);++i) providers.push_back(line); }
        else if (line.rfind("PERMS=",0)==0){ int n=std::stoi(line.substr(6)); permissions.clear(); for(int i=0;i<n&&std::getline(in,line);++i) permissions.push_back(line); }
    }
    return true;
}

// ── binary: [len:4 LE][UTF-16LE chars] per entry ──
static void writeEntry(std::ofstream& out, const std::string& s) {
    // encode as UTF-16LE: ASCII subset only in prototype -> simple
    uint32_t len = (uint32_t)s.size();
    out.write((char*)&len, 4);
    for (char c : s) {
        uint16_t ch = (uint16_t)(unsigned char)c;
        out.write((char*)&ch, 2);
    }
    uint16_t term = 0;
    out.write((char*)&term, 2);
}

bool ManifestSnapshot::serializeBinary(const std::string& outPath) {
    std::ofstream out(outPath, std::ios::binary);
    if (!out) return false;
    // header
    writeEntry(out, packageName);
    writeEntry(out, versionName);
    uint32_t v = (uint32_t)versionCode;
    out.write((char*)&v, 4);
    uint32_t n = (uint32_t)activities.size();
    out.write((char*)&n, 4);
    for (auto& a : activities) writeEntry(out, a);
    n = (uint32_t)services.size();
    out.write((char*)&n, 4);
    for (auto& s : services) writeEntry(out, s);
    n = (uint32_t)providers.size();
    out.write((char*)&n, 4);
    for (auto& p : providers) writeEntry(out, p);
    return out.good();
}

bool ManifestSnapshot::deserializeBinary(const std::string& inPath) {
    std::ifstream in(inPath, std::ios::binary);
    if (!in) return false;
    auto readEntry=[&](std::string& out)->bool{
        uint32_t len=0;
        if(!in.read((char*)&len,4)) return false;
        out.clear();
        for(uint32_t i=0;i<len;++i){ uint16_t ch=0; in.read((char*)&ch,2); out.push_back((char)(ch & 0xFF)); }
        uint16_t term=0; in.read((char*)&term,2);
        return true;
    };
    if(!readEntry(packageName)) return false;
    if(!readEntry(versionName)) return false;
    uint32_t v=0; in.read((char*)&v,4); versionCode=(int)v;
    uint32_t n=0;
    in.read((char*)&n,4); activities.clear();
    for(uint32_t i=0;i<n;++i){ std::string s; readEntry(s); activities.push_back(s); }
    in.read((char*)&n,4); services.clear();
    for(uint32_t i=0;i<n;++i){ std::string s; readEntry(s); services.push_back(s); }
    in.read((char*)&n,4); providers.clear();
    for(uint32_t i=0;i<n;++i){ std::string s; readEntry(s); providers.push_back(s); }
    return true;
}

void ManifestSnapshot::populatePrototype(const std::string& pkg) {
    packageName = pkg;
    // DATA_DUMP §3.1 — 18 activities
    activities = {
        pkg + ".EightBallPoolActivity",
        "com.facebook.LoginActivity",
        "com.unity3d.services.ads.adunit.AdUnitActivity",
        "com.unity3d.services.ads.adunit.AdUnitTransparentActivity",
        "com.unity3d.services.ads.adunit.AdUnitSoftwareActivity",
        "com.unity3d.services.ads.adunit.AdUnitTransparentSoftwareActivity",
        "com.ironsource.sdk.controller.ControllerActivity",
        "com.ironsource.sdk.controller.InterstitialActivity",
        "com.ironsource.sdk.controller.OpenUrlActivity",
        "com.android.billingclient.api.ProxyBillingActivity",
        "com.android.billingclient.api.ProxyBillingActivityV2",
        "com.google.android.gms.games.GamesResolutionActivity",
        "com.google.android.gms.games.PlayGamesAppShortcutsActivity",
        "com.inmobi.ads.AdActivity",
        "com.google.android.gms.auth.api.credentials.HiddenActivity",
        "com.google.android.gms.auth.api.signin.internal.SignInHubActivity",
        "com.applovin.impl.adview.activity.FullscreenActivity",
        "com.applovin.impl.adview.activity.MaxDebuggerActivity"
    };
    // §3.2 — 14 services (subset representative)
    services = {
        "com.android.billingclient.api.BillingService",
        "com.google.firebase.messaging.FirebaseMessagingService",
        "com.google.firebase.components.ComponentDiscoveryService",
        "com.google.android.gms.analytics.AnalyticsService",
        "com.applovin.impl.adview.service.FullscreenAdService",
        "com.bytedance.sdk.openadsdk.core.BinderPoolService",
        "androidx.work.impl.foreground.SystemForegroundService",
        "androidx.work.impl.background.systemalarm.SystemAlarmService",
        "androidx.work.impl.background.systemjob.SystemJobService",
        "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
        "com.google.firebase.sessions.SessionLifecycleService",
        "com.google.android.gms.measurement.AppMeasurementService",
        "androidx.room.MultiInstanceInvalidationService",
        "com.google.firebase.messaging.MCFCMIntentService"
    };
    // §3.3 — 12 providers
    providers = {
        "com.google.firebase.provider.FirebaseInitProvider",
        "com.applovin.provider.AppLovinInitProvider",
        "com.mads.MAdsContentProvider",
        "com.bidmachine.BidMachineInitProvider",
        "com.google.android.gms.ads.MobileAdsInitProvider",
        "com.facebook.internal.FacebookInitProvider",
        "com.vungle.VungleProvider",
        "com.ironsource.lifecycle.IronSourceLifecycleProvider",
        "com.unity3d.services.core.configuration.LevelPlayActivityLifecycleProvider",
        "com.squareup.picasso.PicassoProvider",
        "com.google.android.gms.games.PlayGamesInitProvider",
        "com.facebook.ads.AudienceNetworkContentProvider"
    };
}

} // namespace aether::l1
