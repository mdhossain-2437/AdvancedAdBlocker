package com.example.adblocker.filter

// Simple Aho-Corasick implementation to match multiple substrings against a target string.
// This is optimized for URL / pattern matching but intentionally simplified for clarity.
// For production you should replace this with a highly-optimized native or well-tested implementation.

class FilterCompiler {
    private val root = Node()

    fun add(pattern: String) {
        var node = root
        for (ch in pattern) {
            node = node.children.computeIfAbsent(ch) { Node() }
        }
        node.output = true
    }

    fun build() {
        val queue = ArrayDeque<Node>()
        for (child in root.children.values) {
            child.fail = root
            queue.addLast(child)
        }
        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            for ((ch, u) in r.children) {
                queue.addLast(u)
                var v = r.fail
                while (v != null && !v.children.containsKey(ch)) {
                    v = v.fail
                }
                u.fail = v?.children?.get(ch) ?: root
                u.output = u.output || (u.fail?.output ?: false)
            }
        }
    }

    fun matches(text: String): Boolean {
        var node: Node? = root
        for (ch in text) {
            while (node != null && !node.children.containsKey(ch)) {
                node = node.fail
            }
            node = node?.children?.get(ch) ?: root
            if (node?.output == true) return true
        }
        return false
    }

    private class Node(
        val children: MutableMap<Char, Node> = HashMap(),
        var fail: Node? = null,
        var output: Boolean = false
    )
}
