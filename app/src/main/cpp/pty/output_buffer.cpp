#include "pty/output_buffer.h"
#include <algorithm>
#include <cstring>

namespace pty {

OutputBuffer::OutputBuffer(int capacity)
    : capacity_(capacity), slots_(capacity) {}

OutputBuffer::~OutputBuffer() {
    clear();
}

bool OutputBuffer::push(const char* data, size_t len) {
    totalReceived_.fetch_add(1, std::memory_order_relaxed);

    int head = head_.load(std::memory_order_relaxed);
    int next = (head + 1) % capacity_;

    // Check if full
    if (size_.load(std::memory_order_acquire) >= static_cast<size_t>(capacity_)) {
        totalDropped_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }

    Slot& slot = slots_[head];
    slot.data.assign(data, len);
    slot.ready.store(true, std::memory_order_release);

    head_.store(next, std::memory_order_release);
    size_.fetch_add(1, std::memory_order_release);
    return true;
}

bool OutputBuffer::push(const std::string& text) {
    return push(text.data(), text.size());
}

int OutputBuffer::drain(std::vector<std::string>& out, int max) {
    std::lock_guard<std::mutex> lock(drainMutex_);
    int drained = 0;
    int tail = tail_.load(std::memory_order_relaxed);

    while (drained < max && size_.load(std::memory_order_acquire) > 0) {
        Slot& slot = slots_[tail];
        if (!slot.ready.load(std::memory_order_acquire)) break;

        out.push_back(std::move(slot.data));
        slot.ready.store(false, std::memory_order_release);

        tail = (tail + 1) % capacity_;
        tail_.store(tail, std::memory_order_release);
        size_.fetch_sub(1, std::memory_order_release);
        drained++;
    }
    return drained;
}

void OutputBuffer::clear() {
    std::lock_guard<std::mutex> lock(drainMutex_);
    head_.store(0, std::memory_order_relaxed);
    tail_.store(0, std::memory_order_relaxed);
    size_.store(0, std::memory_order_relaxed);
    for (auto& slot : slots_) {
        slot.ready.store(false, std::memory_order_relaxed);
        slot.data.clear();
    }
}

} // namespace pty
