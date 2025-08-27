package com.example.adblocker.filter

import org.junit.Assert.assertTrue
import org.junit.Test

class FilterCompilerTest {
    @Test
    fun matchesSimplePattern() {
        val comp = FilterCompiler()
        comp.add("ads.example")
        comp.build()
        assertTrue(comp.matches("http://ads.example/track.js"))
        assertTrue(!comp.matches("http://good.example/"))
    }
}
