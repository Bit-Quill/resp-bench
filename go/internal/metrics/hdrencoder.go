package metrics

import (
	"bytes"
	"compress/zlib"
	"encoding/base64"
	"encoding/binary"
	"math"
)

// HdrHistogram V2 encoding constants (Java-compatible).
const (
	v2EncodingCookieBase           = 0x1c849303
	v2CompressedEncodingCookieBase = 0x1c849304
	wordSizeFlag                   = 0x10

	v2EncodingCookie           = v2EncodingCookieBase | wordSizeFlag           // 0x1c849313
	v2CompressedEncodingCookie = v2CompressedEncodingCookieBase | wordSizeFlag // 0x1c849314
)

// EncodeCompressedBase64 encodes a histogram into the V2 compressed binary
// format and base64-encodes it, byte-compatible with Java's
// Histogram.encodeIntoCompressedByteBuffer().
func EncodeCompressedBase64(h *HdrHistogram) (string, error) {
	compressed, err := EncodeCompressed(h)
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(compressed), nil
}

// EncodeCompressed produces the compressed wrapper around the V2 payload.
func EncodeCompressed(h *HdrHistogram) ([]byte, error) {
	payload := encodeV2(h)

	var buf bytes.Buffer
	zw, err := zlib.NewWriterLevel(&buf, zlib.DefaultCompression)
	if err != nil {
		return nil, err
	}
	if _, err := zw.Write(payload); err != nil {
		return nil, err
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	compressed := buf.Bytes()

	out := make([]byte, 8+len(compressed))
	binary.BigEndian.PutUint32(out[0:4], uint32(v2CompressedEncodingCookie))
	binary.BigEndian.PutUint32(out[4:8], uint32(len(compressed)))
	copy(out[8:], compressed)
	return out, nil
}

// encodeV2 builds the uncompressed V2 payload.
func encodeV2(h *HdrHistogram) []byte {
	countsBytes := encodeCounts(h)

	// Java's V2 payload_length is the counts-array length ONLY — written as
	// `buffer.position() - payloadStartPosition`, measured after the 40-byte
	// header. It must NOT include the 32 bytes of header fields that follow the
	// length field, or Java's decoder throws "The buffer does not contain the
	// indicated payload amount".
	payloadLen := len(countsBytes)

	buf := make([]byte, 0, 40+len(countsBytes))
	buf = binary.BigEndian.AppendUint32(buf, uint32(v2EncodingCookie))
	buf = binary.BigEndian.AppendUint32(buf, uint32(payloadLen))
	buf = binary.BigEndian.AppendUint32(buf, 0) // normalizing_index_offset
	buf = binary.BigEndian.AppendUint32(buf, uint32(h.SignificantFigures))
	buf = binary.BigEndian.AppendUint64(buf, uint64(h.LowestTrackableValue))
	buf = binary.BigEndian.AppendUint64(buf, uint64(h.HighestTrackableValue))
	buf = binary.BigEndian.AppendUint64(buf, math.Float64bits(1.0)) // conversion ratio
	buf = append(buf, countsBytes...)
	return buf
}

// encodeCounts serializes the counts array using ZigZag LEB128 with zero-run
// compression (negative values encode a run of zeros).
func encodeCounts(h *HdrHistogram) []byte {
	relevant := h.RelevantLength()
	var out []byte
	i := 0
	for i < relevant {
		count := h.RawCountAt(i)
		if count == 0 {
			zeros := 1
			for i+zeros < relevant && h.RawCountAt(i+zeros) == 0 {
				zeros++
			}
			out = appendZigZag(out, int64(-zeros))
			i += zeros
		} else {
			out = appendZigZag(out, count)
			i++
		}
	}
	return out
}

// appendZigZag appends a ZigZag+LEB128-encoded signed 64-bit integer.
func appendZigZag(dst []byte, value int64) []byte {
	// ZigZag transform, then treat as unsigned for LEB128.
	zz := uint64((value << 1) ^ (value >> 63))
	for {
		if zz&^0x7F == 0 {
			return append(dst, byte(zz&0x7F))
		}
		dst = append(dst, byte((zz&0x7F)|0x80))
		zz >>= 7
	}
}
