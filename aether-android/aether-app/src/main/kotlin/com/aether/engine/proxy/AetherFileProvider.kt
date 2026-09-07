package com.aether.engine.proxy

import androidx.core.content.FileProvider

/**
 * AetherFileProvider — Content URI provider for file sharing (FileProvider)
 *
 * ใช้สำหรับแชร์ไฟล์ระหว่าง processes และ expose ไปยัง external apps
 * ผ่าน content:// URIs แทน file:// URIs (Android 7.0+ requirement)
 */
class AetherFileProvider : FileProvider() {
    companion object {
        const val AUTHORITY = "com.aether.proxy.fileprovider"
    }
}
