package com.aether.engine.proxy

import java.lang.reflect.Method
import java.lang.reflect.Constructor

/**
 * MethodUtils — JNI reflection helpers (AetherEngine — portable signature generators)
 *
 * 7 methods (portable to any Android ART):
 *   - getDeclaringClass(Method)  → String (class descriptor for JNI)
 *   - getDesc(Class)              → String (JNI type descriptor)
 *   - getDesc(Method)             → String (JNI method descriptor)
 *   - getMethodName(Method)       → String
 *   - getPrimitiveLetter(Class)   → String (Z, B, C, S, I, J, F, D, V)
 *   - getType(Class)              → String (JNI type descriptor, supports arrays + primitives)
 *
 * ใช้สำหรับ:
 *   - สร้าง JNI signature ที่ runtime (wire to Native.update)
 *   - log method descriptor สำหรับ ART method hook
 */
object MethodUtils {

    /** Get declaring class descriptor (Lcom/foo/Bar;) */
    fun getDeclaringClass(method: Method): String =
        "L" + method.declaringClass.name.replace('.', '/') + ";"

    /** Get JNI type descriptor for a Class (Z, B, C, S, I, J, F, D, V, or Lpkg/Cls;, or [type) */
    fun getDesc(cls: Class<*>): String {
        if (cls.isPrimitive) return getPrimitiveLetter(cls)
        if (cls.isArray) return "[" + getDesc(cls.componentType)
        return "L" + cls.name.replace('.', '/') + ";"
    }

    /** Get JNI method descriptor: (arg1;arg2;)returnType */
    fun getDesc(method: Method): String {
        val params = method.parameterTypes.joinToString("") { getDesc(it) }
        val ret = getDesc(method.returnType)
        return "($params)$ret"
    }

    /** Get JNI method descriptor for a Constructor */
    fun getDesc(ctor: Constructor<*>): String {
        val params = ctor.parameterTypes.joinToString("") { getDesc(it) }
        // Constructors return void
        return "($params)V"
    }

    /** Get method name (truncated) */
    fun getMethodName(method: Method): String = method.name

    /** Get primitive type letter (JVM → JNI) */
    fun getPrimitiveLetter(cls: Class<*>): String = when (cls) {
        java.lang.Boolean.TYPE -> "Z"
        java.lang.Byte.TYPE -> "B"
        java.lang.Character.TYPE -> "C"
        java.lang.Short.TYPE -> "S"
        java.lang.Integer.TYPE -> "I"
        java.lang.Long.TYPE -> "J"
        java.lang.Float.TYPE -> "F"
        java.lang.Double.TYPE -> "D"
        java.lang.Void.TYPE -> "V"
        else -> "L" + cls.name.replace('.', '/') + ";"
    }

    /** Get JNI type descriptor for a Class (alias of getDesc) */
    fun getType(cls: Class<*>): String = getDesc(cls)
}
