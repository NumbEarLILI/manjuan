package com.numbear.manjuan.core

internal fun tinyGif(): ByteArray = byteArrayOf(
    0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
    0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x02,
    0x02, 0x44, 0x01, 0x00, 0x3B,
)

internal fun tinyPng(): ByteArray = byteArrayOf(
    137.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0,
    144.toByte(), 119, 83, 222.toByte(), 0, 0, 0, 12, 73, 68, 65, 84, 120, 156.toByte(), 99, 248.toByte(), 207.toByte(),
    192.toByte(), 0, 0, 3, 1, 1, 0, 201.toByte(), 254.toByte(), 146.toByte(), 239.toByte(), 0, 0, 0, 0, 73, 69, 78, 68,
    174.toByte(), 66, 96, 130.toByte(),
)
