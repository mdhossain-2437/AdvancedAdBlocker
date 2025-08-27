package com.example.adblocker.filter

data class RequestContext(
    val domain: String,
    val url: String,
    val resourceType: ResourceType,
    val headers: Map<String, String> = emptyMap()
)

enum class ResourceType { DOCUMENT, SCRIPT, IMAGE, XHR, OTHER }

sealed class FilterDecision {
    object Block : FilterDecision()
    object Allow : FilterDecision()
    data class Redirect(val location: String) : FilterDecision()
    data class Modify(val modifiedBody: ByteArray) : FilterDecision()
}

interface FilterEngine {
    fun loadFilterLists(lists: List<String>)
    fun decide(context: RequestContext): FilterDecision
    fun getStats(): Map<String, Long>
}
