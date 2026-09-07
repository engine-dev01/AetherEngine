package com.aether

import com.aether.engine.proxy.MethodUtils
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit test for MethodUtils (JNI reflection helpers)
 */
class MethodUtilsTest {

    @Test
    fun getDeclaringClass_returns_JNI_descriptor() {
        val method = String::class.java.getMethod("length")
        val desc = MethodUtils.getDeclaringClass(method)
        assertEquals("Ljava/lang/String;", desc)
    }

    @Test
    fun getDesc_primitive_int() {
        assertEquals("I", MethodUtils.getDesc(Int::class.javaPrimitiveType!!))
    }

    @Test
    fun getDesc_primitive_void() {
        assertEquals("V", MethodUtils.getDesc(Void.TYPE))
    }

    @Test
    fun getDesc_array_int() {
        assertEquals("[I", MethodUtils.getDesc(IntArray::class.java))
    }

    @Test
    fun getDesc_object() {
        assertEquals("Ljava/lang/String;", MethodUtils.getDesc(String::class.java))
    }

    @Test
    fun getDesc_method_signature() {
        val method = String::class.java.getMethod("substring", Int::class.javaPrimitiveType)
        val desc = MethodUtils.getDesc(method)
        assertEquals("(I)Ljava/lang/String;", desc)
    }

    @Test
    fun getPrimitiveLetter_all_primitives() {
        assertEquals("Z", MethodUtils.getPrimitiveLetter(java.lang.Boolean.TYPE))
        assertEquals("B", MethodUtils.getPrimitiveLetter(java.lang.Byte.TYPE))
        assertEquals("C", MethodUtils.getPrimitiveLetter(java.lang.Character.TYPE))
        assertEquals("S", MethodUtils.getPrimitiveLetter(java.lang.Short.TYPE))
        assertEquals("I", MethodUtils.getPrimitiveLetter(java.lang.Integer.TYPE))
        assertEquals("J", MethodUtils.getPrimitiveLetter(java.lang.Long.TYPE))
        assertEquals("F", MethodUtils.getPrimitiveLetter(java.lang.Float.TYPE))
        assertEquals("D", MethodUtils.getPrimitiveLetter(java.lang.Double.TYPE))
        assertEquals("V", MethodUtils.getPrimitiveLetter(java.lang.Void.TYPE))
    }

    @Test
    fun getType_alias_of_getDesc() {
        val cls = String::class.java
        assertEquals(MethodUtils.getDesc(cls), MethodUtils.getType(cls))
    }

    @Test
    fun getMethodName_returns_name() {
        val method = String::class.java.getMethod("length")
        assertEquals("length", MethodUtils.getMethodName(method))
    }
}
