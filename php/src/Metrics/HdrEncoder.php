<?php

declare(strict_types=1);

namespace RespBench\Metrics;

use RuntimeException;

/**
 * Encoder for the HdrHistogram V2 compressed binary format, byte-compatible with
 * Java's Histogram.encodeIntoCompressedByteBuffer() (and the Ruby engine's encoder).
 *
 * All integers are big-endian (network byte order).
 *
 * Compressed wrapper:
 *   int32  compressed_encoding_cookie   (0x1c849314)
 *   int32  compressed_payload_length
 *   byte[] zlib-deflated V2 payload
 *
 * V2 payload:
 *   int32  v2_encoding_cookie           (0x1c849313)
 *   int32  payload_length
 *   int32  normalizing_index_offset     (0)
 *   int32  number_of_significant_digits
 *   int64  lowest_trackable_value
 *   int64  highest_trackable_value
 *   int64  integer_to_double_conversion_ratio  (IEEE754 double bits; 1.0)
 *   byte[] counts (ZigZag LEB128, negative = zero-run)
 */
final class HdrEncoder
{
    private const V2_ENCODING_COOKIE_BASE = 0x1c849303;
    private const V2_COMPRESSED_ENCODING_COOKIE_BASE = 0x1c849304;
    private const WORD_SIZE_FLAG = 0x10;

    private const V2_ENCODING_COOKIE = self::V2_ENCODING_COOKIE_BASE | self::WORD_SIZE_FLAG; // 0x1c849313
    private const V2_COMPRESSED_ENCODING_COOKIE = self::V2_COMPRESSED_ENCODING_COOKIE_BASE | self::WORD_SIZE_FLAG; // 0x1c849314

    /**
     * Encode a histogram into the V2 compressed binary format, base64-encoded.
     */
    public static function encodeCompressedBase64(HdrHistogram $histogram): string
    {
        return base64_encode(self::encodeCompressed($histogram));
    }

    public static function encodeCompressed(HdrHistogram $histogram): string
    {
        $v2Payload = self::encodeV2($histogram);
        $compressed = gzcompress($v2Payload, 6);
        if ($compressed === false) {
            throw new RuntimeException('Failed to zlib-compress HDR payload');
        }

        // int32 cookie + int32 compressed length + compressed bytes
        return pack('N2', self::V2_COMPRESSED_ENCODING_COOKIE, strlen($compressed)) . $compressed;
    }

    private static function encodeV2(HdrHistogram $histogram): string
    {
        $normalizingOffset = 0;
        $sigFigs = $histogram->significantFigures;
        $lowest = $histogram->lowestTrackableValue;
        $highest = $histogram->highestTrackableValue;
        $conversionRatioBits = self::doubleToLongBits(1.0);

        $countsBytes = self::encodeCounts($histogram);

        // payload_len = everything after the payload_len field itself:
        // normalizing(4) + sigfigs(4) + lowest(8) + highest(8) + ratio(8) + counts
        $payloadLen = 4 + 4 + 8 + 8 + 8 + strlen($countsBytes);

        $header = pack('N4', self::V2_ENCODING_COOKIE, $payloadLen, $normalizingOffset, $sigFigs);
        $int64Fields = self::packInt64BE($lowest)
            . self::packInt64BE($highest)
            . self::packInt64BE($conversionRatioBits);

        return $header . $int64Fields . $countsBytes;
    }

    private static function encodeCounts(HdrHistogram $histogram): string
    {
        $relevantLength = $histogram->relevantLength();
        $result = '';
        $index = 0;

        while ($index < $relevantLength) {
            $count = $histogram->rawCountAt($index);
            if ($count === 0) {
                $zeros = 1;
                while (($index + $zeros) < $relevantLength && $histogram->rawCountAt($index + $zeros) === 0) {
                    $zeros++;
                }
                $result .= self::encodeZigZag(-$zeros);
                $index += $zeros;
            } else {
                $result .= self::encodeZigZag($count);
                $index++;
            }
        }

        return $result;
    }

    /**
     * ZigZag + LEB128 encode a signed 64-bit integer.
     */
    private static function encodeZigZag(int $value): string
    {
        // ZigZag: (value << 1) ^ (value >> 63), using arithmetic shift semantics.
        $zz = ($value << 1) ^ ($value >> 63);

        $result = '';
        // Process as unsigned 64-bit via logical right shift.
        while (true) {
            if (($zz & ~0x7F) === 0) {
                $result .= chr($zz & 0x7F);
                break;
            }
            $result .= chr(($zz & 0x7F) | 0x80);
            $zz = self::logicalShiftRight($zz, 7);
        }

        return $result;
    }

    /**
     * Logical (unsigned) right shift for 64-bit ints in PHP.
     */
    private static function logicalShiftRight(int $value, int $bits): int
    {
        if ($bits === 0) {
            return $value;
        }

        // Shift then clear the top `bits` sign-extended bits.
        return ($value >> $bits) & (PHP_INT_MAX >> ($bits - 1));
    }

    private static function doubleToLongBits(float $value): int
    {
        // Pack as big-endian double, unpack as signed big-endian int64.
        $packed = pack('E', $value); // 'E' = big-endian double
        /** @var array{1:int} $unpacked */
        $unpacked = unpack('J', $packed); // 'J' = big-endian unsigned int64

        return $unpacked[1];
    }

    private static function packInt64BE(int $value): string
    {
        // 'J' handles the full 64-bit range; PHP ints are 64-bit signed.
        return pack('J', $value);
    }
}
