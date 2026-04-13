package com.example.nightlibrary.security


import java.security.MessageDigest
import java.util.*

object PinHasher {

    private const val SALT = "NightLibrary_Secure_Salt"

    fun hash(pin: String): String {
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest((pin + SALT).toByteArray())

        return bytes.joinToString("") { "%02x".format(it) }
    }
}