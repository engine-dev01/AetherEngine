#pragma once
#include <string>

namespace aether::l1 {
class RootSpoof {
public:
    bool hideFrom(const std::string& packageName);
    bool patchProp(const std::string& key, const std::string& value);
    bool isHidden(const std::string& packageName) const;
};
} // namespace aether::l1
