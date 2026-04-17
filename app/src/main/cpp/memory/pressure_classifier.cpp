#include "memory/pressure_classifier.h"
#include <cstdlib>
#include <new>
#include <cstring>

namespace memory {

// ── PressureClassifier ────────────────────────────────────────────────────────

PressureLevel PressureClassifier::classify(int64_t totalBytes, int64_t availableBytes) {
    if (totalBytes <= 0) return PressureLevel::LOW;
    // Multiply to avoid floating point — compare ratios as integers
    // CRITICAL : available * 20 < total  (< 5%)
    // HIGH     : available * 100 < total * 15
    // MEDIUM   : available * 100 < total * 30
    if (availableBytes * 20LL < totalBytes)           return PressureLevel::CRITICAL;
    if (availableBytes * 100LL < totalBytes * 15LL)   return PressureLevel::HIGH;
    if (availableBytes * 100LL < totalBytes * 30LL)   return PressureLevel::MEDIUM;
    return PressureLevel::LOW;
}

PressureLevel PressureClassifier::classifyWithHysteresis(
    int64_t totalBytes, int64_t availableBytes, int minCycles)
{
    PressureLevel current = classify(totalBytes, availableBytes);
    PressureLevel last = lastLevel_.load(std::memory_order_relaxed);

    if (current > last) {
        int c = consecutiveCycles_.fetch_add(1, std::memory_order_relaxed) + 1;
        if (c >= minCycles) {
            lastLevel_.store(current, std::memory_order_relaxed);
            consecutiveCycles_.store(0, std::memory_order_relaxed);
            return current;
        }
        // Not enough consecutive cycles — stay at last level
        return last;
    } else {
        consecutiveCycles_.store(0, std::memory_order_relaxed);
        lastLevel_.store(current, std::memory_order_relaxed);
        return current;
    }
}

void PressureClassifier::reset() {
    consecutiveCycles_.store(0, std::memory_order_relaxed);
    lastLevel_.store(PressureLevel::LOW, std::memory_order_relaxed);
}

// ── SlabPool ──────────────────────────────────────────────────────────────────

SlabPool::SlabPool(int slabSize, int poolSize)
    : slabSize_(slabSize), poolSize_(poolSize)
{
    // Single allocation for nodes and slab memory
    nodes_ = new Node[poolSize];
    slabs_ = new char[static_cast<size_t>(slabSize) * poolSize];

    // Build free list in order (push all onto the stack)
    Node* top = nullptr;
    for (int i = poolSize - 1; i >= 0; i--) {
        nodes_[i].data = slabs_ + (static_cast<size_t>(slabSize) * i);
        nodes_[i].next = top;
        top = &nodes_[i];
    }
    freeList_.store(top, std::memory_order_release);
}

SlabPool::~SlabPool() {
    delete[] nodes_;
    delete[] slabs_;
}

char* SlabPool::acquire() {
    // Lock-free pop from free list (Treiber stack)
    Node* head = freeList_.load(std::memory_order_acquire);
    while (head) {
        if (freeList_.compare_exchange_weak(head, head->next,
                std::memory_order_release, std::memory_order_relaxed)) {
            totalAcquired_.fetch_add(1, std::memory_order_relaxed);
            return head->data;
        }
    }
    return nullptr; // pool exhausted
}

void SlabPool::release(char* slab) {
    if (!slab) return;
    // Compute node index from pointer arithmetic
    ptrdiff_t offset = slab - slabs_;
    int idx = static_cast<int>(offset / slabSize_);
    if (idx < 0 || idx >= poolSize_) return; // out-of-pool pointer, ignore

    Node* node = &nodes_[idx];
    // Lock-free push onto free list (Treiber stack)
    Node* head = freeList_.load(std::memory_order_relaxed);
    do {
        node->next = head;
    } while (!freeList_.compare_exchange_weak(head, node,
        std::memory_order_release, std::memory_order_relaxed));

    totalReleased_.fetch_add(1, std::memory_order_relaxed);
}

int SlabPool::available() const {
    // Walk free list — acceptable for a stats call (not hot path)
    int count = 0;
    const Node* n = freeList_.load(std::memory_order_acquire);
    while (n) { n = n->next; count++; }
    return count;
}

int64_t SlabPool::totalAcquired() const {
    return totalAcquired_.load(std::memory_order_relaxed);
}

int64_t SlabPool::totalReleased() const {
    return totalReleased_.load(std::memory_order_relaxed);
}

} // namespace memory
