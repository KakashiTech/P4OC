#pragma once

#include <atomic>
#include <memory>
#include <cstdint>

namespace buffer {

/**
 * Lock-free MPMC queue using Michael-Scott algorithm.
 * Suitable for high-concurrency message buffering.
 */
template<typename T>
class LockFreeQueue {
private:
    struct Node {
        std::atomic<Node*> next{nullptr};
        T data;
        
        Node() = default;
        explicit Node(T&& d) : data(std::move(d)) {}
    };

    std::atomic<Node*> head_;
    std::atomic<Node*> tail_;
    std::atomic<size_t> size_{0};

public:
    LockFreeQueue() {
        Node* sentinel = new Node();
        head_.store(sentinel, std::memory_order_relaxed);
        tail_.store(sentinel, std::memory_order_relaxed);
    }

    ~LockFreeQueue() {
        // Drain and delete all nodes
        T tmp;
        while (pop(tmp)) {}
        delete head_.load(std::memory_order_relaxed);
    }

    // Non-copyable
    LockFreeQueue(const LockFreeQueue&) = delete;
    LockFreeQueue& operator=(const LockFreeQueue&) = delete;

    /**
     * Push an element. Always succeeds (unbounded queue).
     */
    void push(T&& value) {
        Node* node = new Node(std::move(value));
        Node* prev_tail = tail_.exchange(node, std::memory_order_acq_rel);
        prev_tail->next.store(node, std::memory_order_release);
        size_.fetch_add(1, std::memory_order_relaxed);
    }

    void push(const T& value) {
        T copy = value;
        push(std::move(copy));
    }

    /**
     * Pop front element. Returns false if empty.
     */
    bool pop(T& out) {
        while (true) {
            Node* head = head_.load(std::memory_order_acquire);
            Node* next = head->next.load(std::memory_order_acquire);
            if (next == nullptr) return false; // empty

            if (head_.compare_exchange_weak(head, next, std::memory_order_release,
                                            std::memory_order_relaxed)) {
                out = std::move(next->data);
                size_.fetch_sub(1, std::memory_order_relaxed);
                delete head;
                return true;
            }
        }
    }

    /**
     * Peek front element without removing. NOT thread-safe standalone.
     */
    bool peek(T& out) const {
        Node* head = head_.load(std::memory_order_acquire);
        Node* next = head->next.load(std::memory_order_acquire);
        if (next == nullptr) return false;
        out = next->data;
        return true;
    }

    size_t size() const {
        return size_.load(std::memory_order_relaxed);
    }

    bool empty() const {
        return size() == 0;
    }
};

} // namespace buffer
