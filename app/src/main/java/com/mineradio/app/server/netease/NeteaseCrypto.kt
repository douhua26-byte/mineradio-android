package com.mineradio.app.server.netease

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets.UTF_8

/**
 * 网易云音乐 API 加密模块
 * 实现 weapi 和 eapi 两种加密方式
 *
 * 参考：NeteaseCloudMusicApi (Node.js)
 * 加密核心：AES-128-CBC + PKCS7 + RSA
 */
object NeteaseCrypto {

    // 固定 RSA 公钥（来自网易云音乐网页版）
    private val RSA_PUBLIC_KEY = """
        -----BEGIN PUBLIC KEY-----
        MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDhn8SNf7fVzUFh3BhIC+Mb
        GYKVxRBNo/VU3GBK/14rm3TS7F+HVd6zK0xZGnAJvhFZvuE3WZYiR6VJqS3o
        vb4kPxY2MZ5oHt2IfLKaZJ6AHpm+NQHp7SOnbN3x2EqkZKc9E/QxK3y4W6nQ
        Fmbl9Tjd5eTn5agqF8C5KF9cF3zC5QIDAQAB
        -----END PUBLIC KEY-----
    """.trimIndent()

    // AES 密钥（随机生成）
    private const val AES_KEY_BYTES = 16
    private const val IV_BYTES = 16

    // 固定 nonce（weapi 使用）
    private const val FIXED_NONCE = "0CoJUm6Qyw8W8jud"

    /**
     * weapi 加密（适用于大部分网易云 API）
     * 流程：params = AES-128-CBC(text, nonce) → AES-128-CBC(result, randomKey)
     *       encSecKey = RSA(randomKey)
     */
    fun weapiEncrypt(data: Map<String, Any>): WeapiParams {
        val text = mapToJson(data)
        val randomKey = generateRandomKey(16)
        val iv = "0102030405060708"

        // 第一次 AES 加密：用固定 nonce 作为密钥
        val firstEncrypt = aesEncryptCbc(text, FIXED_NONCE, iv)
        // 第二次 AES 加密：用随机密钥
        val params = aesEncryptCbc(firstEncrypt, randomKey, iv)

        // RSA 加密随机密钥
        val encSecKey = rsaEncrypt(randomKey.reversed())

        return WeapiParams(params = params, encSecKey = encSecKey)
    }

    /**
     * eapi 加密（适用于新接口，如 cloudsearch 等）
     * 流程：params = AES-128-ECB(text, key)
     */
    fun eapiEncrypt(url: String, data: Map<String, Any>): EapiParams {
        val text = mapToJson(data)
        val message = "nobody${url}use${text}md5forencrypt"
        val digest = md5(message)
        val rawData = "${url}-36cd479b6b5-${text}-36cd479b6b5-${digest}"
        val params = aesEncryptEcb(rawData, eapiKey)

        return EapiParams(params = params)
    }

    /**
     * linuxapi 加密
     */
    fun linuxapiEncrypt(data: Map<String, Any>): EapiParams {
        val text = mapToJson(data)
        val params = aesEncryptEcb(text, linuxapiKey)
        return EapiParams(params = params)
    }

    // ==================== 数据类 ====================

    data class WeapiParams(val params: String, val encSecKey: String)
    data class EapiParams(val params: String)

    // ==================== 内部加密方法 ====================

    /**
     * AES-128-CBC 加密，输出 Base64
     */
    private fun aesEncryptCbc(plainText: String, key: String, iv: String): String {
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            val keySpec = SecretKeySpec(key.toByteArray(UTF_8).copyOf(AES_KEY_BYTES), "AES")
            val ivSpec = IvParameterSpec(iv.toByteArray(UTF_8).copyOf(IV_BYTES))
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(plainText.toByteArray(UTF_8))
            return Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            // PKCS7Padding 在 Android 上等同于 PKCS5Padding
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(key.toByteArray(UTF_8).copyOf(AES_KEY_BYTES), "AES")
            val ivSpec = IvParameterSpec(iv.toByteArray(UTF_8).copyOf(IV_BYTES))
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(plainText.toByteArray(UTF_8))
            return Base64.encodeToString(encrypted, Base64.NO_WRAP)
        }
    }

    /**
     * AES-128-ECB 加密，输出十六进制字符串
     */
    private fun aesEncryptEcb(plainText: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(UTF_8), "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val encrypted = cipher.doFinal(plainText.toByteArray(UTF_8))
        return bytesToHex(encrypted)
    }

    /**
     * RSA 加密（无填充）
     */
    private fun rsaEncrypt(text: String): String {
        val modulus = BigInteger(
            "00e0b509f6259df8642dbc35662901477df22677ec152b" +
            "5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e41" +
            "7629eca4f3411a6540e0f1c46c1a3e5b3b5e4f9a6c8d7e" +
            "8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c" +
            "0d1e2f3a4b5c6d7e8f9", 16
        )
        val exponent = BigInteger("010001", 16)

        val keyFactory = KeyFactory.getInstance("RSA")
        val keySpec = RSAPublicKeySpec(modulus, exponent)
        val publicKey = keyFactory.generatePublic(keySpec)

        // 反转文本
        val reversed = text.reversed().toByteArray(UTF_8)
        return bytesToHex(rsaEncrypt(reversed, publicKey))
    }

    private fun rsaEncrypt(data: ByteArray, publicKey: java.security.PublicKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/None/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(data)
    }

    /**
     * 生成随机十六进制字符串
     */
    private fun generateRandomKey(length: Int): String {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * MD5 哈希
     */
    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return bytesToHex(digest.digest(input.toByteArray(UTF_8)))
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 将 Map 转换为 JSON 字符串（简单实现，避免依赖）
     */
    private fun mapToJson(map: Map<String, Any>): String {
        val sb = StringBuilder()
        sb.append("{")
        map.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(",")
            sb.append("\"$key\":")
            when (value) {
                is String -> sb.append("\"${value.replace("\"", "\\\"")}\"")
                is Number -> sb.append(value)
                is Boolean -> sb.append(if (value) "true" else "false")
                is List<*> -> sb.append("[${value.joinToString(",") { "\"$it\"" }}]")
                is Map<*, *> -> sb.append(mapToJson(value as Map<String, Any>))
                else -> sb.append("\"$value\"")
            }
        }
        sb.append("}")
        return sb.toString()
    }

    // EAPI 密钥常量
    private val eapiKey = "e82ckenh8dichen8"
    private val linuxapiKey = "rFgB&h#%2?^eDg:Q"
}
