// flagger.cpp — tamper/state flag bitset implementation
#include "flagger.hpp"

namespace aether {

std::atomic<uint32_t> Flagger::s_flags{0};

void Flagger::mark(Flag f)   { s_flags.fetch_or(static_cast<uint32_t>(f), std::memory_order_relaxed); }
void Flagger::clear(Flag f)  { s_flags.fetch_and(~static_cast<uint32_t>(f), std::memory_order_relaxed); }
bool Flagger::test(Flag f)   { return (s_flags.load(std::memory_order_relaxed) & static_cast<uint32_t>(f)) != 0; }
uint32_t Flagger::snapshot() { return s_flags.load(std::memory_order_relaxed); }

void Flagger::probeSdk(int apiLevel) {
    if (apiLevel >= 29) mark(FLAG_SCOPED_STORE);
}

} // namespace aether
