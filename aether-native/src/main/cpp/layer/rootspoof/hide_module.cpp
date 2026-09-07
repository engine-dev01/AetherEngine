#include "hide_module.hpp"
#include <fstream>

namespace aether::l1 {

bool RootSpoof::hideFrom(const std::string& packageName) {
    // Write to Magisk denylist (requires root)
    std::ofstream denylist("/data/adb/magisk/denylist", std::ios::app);
    if (denylist) {
        denylist << packageName << "\n";
        return true;
    }
    // Fallback: prop patch
    return patchProp("ro.build.version.sdk", "30");
}

bool RootSpoof::patchProp(const std::string& key, const std::string& value) {
    std::ofstream prop("/data/local/tmp/aether.prop", std::ios::app);
    if (!prop) return false;
    prop << key << "=" << value << "\n";
    return true;
}

bool RootSpoof::isHidden(const std::string& packageName) const {
    std::ifstream denylist("/data/adb/magisk/denylist");
    std::string line;
    while (std::getline(denylist, line)) {
        if (line == packageName) return true;
    }
    return false;
}

} // namespace aether::l1
