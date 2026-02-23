# frozen_string_literal: true

require "zlib"

module RespBench
  module Metrics
    # Pure-Ruby encoder/decoder for HdrHistogram V2 compressed format.
    #
    # Produces output byte-for-byte compatible with Java's
    #   Histogram.encodeIntoCompressedByteBuffer()
    # and decodable by Java's
    #   Histogram.decodeFromCompressedByteBuffer()
    #
    # Binary format (all integers are big-endian / network byte order):
    #
    # Compressed wrapper:
    #   int32  compressed_encoding_cookie   (0x1c849314)
    #   int32  compressed_payload_length
    #   byte[] zlib-deflated V2 payload
    #
    # V2 payload (inside the zlib stream):
    #   int32  v2_encoding_cookie           (0x1c849313)
    #   int32  payload_length               (bytes after this field)
    #   int32  normalizing_index_offset
    #   int32  number_of_significant_digits
    #   int64  lowest_trackable_value
    #   int64  highest_trackable_value
    #   int64  integer_to_double_conversion_ratio  (IEEE 754 double as raw bits)
    #   byte[] counts array (ZigZag LEB128 encoded)
    #
    # Reference: https://github.com/HdrHistogram/HdrHistogram (Java)
    module HdrHistogramEncoder
      # Java HdrHistogram V2 format constants
      V2_ENCODING_COOKIE_BASE            = 0x1c849303
      V2_COMPRESSED_ENCODING_COOKIE_BASE = 0x1c849304
      WORD_SIZE_FLAG                     = 0x10  # 8-byte (int64) word size

      V2_ENCODING_COOKIE            = V2_ENCODING_COOKIE_BASE | WORD_SIZE_FLAG            # 0x1c849313
      V2_COMPRESSED_ENCODING_COOKIE = V2_COMPRESSED_ENCODING_COOKIE_BASE | WORD_SIZE_FLAG  # 0x1c849314

      class << self
        # Encode an HDRHistogram instance into the Java-compatible V2 compressed
        # binary format. Returns a binary String.
        #
        # @param histogram [HDRHistogram] histogram object (from the HDRHistogram gem)
        # @return [String] binary string containing V2 compressed encoding
        def encode_compressed(histogram)
          v2_payload = encode_v2(histogram)
          compressed = Zlib::Deflate.deflate(v2_payload, Zlib::DEFAULT_COMPRESSION)

          # Compressed wrapper: cookie (int32) + length (int32) + compressed data
          [V2_COMPRESSED_ENCODING_COOKIE, compressed.bytesize].pack("N2") + compressed
        end

        # Decode a V2 compressed binary payload into a new HDRHistogram instance.
        #
        # @param data [String] binary string from encode_compressed (or Java's equivalent)
        # @return [HDRHistogram] a new histogram populated with the decoded data
        def decode_compressed(data)
          buf = data.dup.force_encoding(Encoding::BINARY)
          cookie, compressed_length = buf[0, 8].unpack("N2")

          # Validate cookie
          cookie_base = cookie & ~0xF0
          unless cookie_base == V2_COMPRESSED_ENCODING_COOKIE_BASE
            raise "Invalid compressed encoding cookie: 0x#{cookie.to_s(16)} " \
                  "(expected base 0x#{V2_COMPRESSED_ENCODING_COOKIE_BASE.to_s(16)})"
          end

          compressed_data = buf[8, compressed_length]
          v2_payload = Zlib::Inflate.inflate(compressed_data)

          decode_v2(v2_payload)
        end

        private

        # Encode histogram into V2 (uncompressed) payload.
        def encode_v2(histogram)
          # Read histogram internals via the private accessors the gem exposes
          normalizing_offset = histogram.send(:normalizing_index_offset)
          sig_figs           = histogram.send(:significant_figures)
          lowest             = histogram.send(:lowest_trackable_value)
          highest            = histogram.send(:highest_trackable_value)
          conversion_ratio   = histogram.send(:conversion_ratio)
          counts_len         = histogram.send(:counts_len)

          # Find the relevant length (last non-zero count index + 1)
          relevant_length = 0
          (counts_len - 1).downto(0) do |i|
            if histogram.send(:get_raw_count, i) != 0
              relevant_length = i + 1
              break
            end
          end

          # Encode counts with ZigZag LEB128
          counts_bytes = encode_counts(histogram, relevant_length)

          # V2 header: cookie + payload_len + normalizing + sigfigs + lowest + highest + conversion_ratio
          # payload_len = everything after the payload_len field itself
          payload_len = 4 + 4 + 8 + 8 + 8 + counts_bytes.bytesize  # normalizing + sigfigs + 3×int64 + counts
          header = [
            V2_ENCODING_COOKIE,
            payload_len,
            normalizing_offset,
            sig_figs
          ].pack("N4")

          # int64 fields (big-endian): lowest, highest, conversion_ratio (as double bits)
          int64_fields = [lowest, highest, double_to_long_bits(conversion_ratio)].pack("q>3")

          header + int64_fields + counts_bytes
        end

        # Decode V2 (uncompressed) payload into a new HDRHistogram.
        def decode_v2(data)
          buf = data.dup.force_encoding(Encoding::BINARY)

          # Parse header
          cookie, payload_len, normalizing_offset, sig_figs = buf[0, 16].unpack("N4")

          cookie_base = cookie & ~0xF0
          unless cookie_base == V2_ENCODING_COOKIE_BASE
            raise "Invalid V2 encoding cookie: 0x#{cookie.to_s(16)}"
          end

          lowest, highest, conversion_ratio_bits = buf[16, 24].unpack("q>3")
          _conversion_ratio = long_bits_to_double(conversion_ratio_bits)

          # Create a new histogram with the decoded parameters
          # Suppress the T_DATA allocator warning on Ruby 3.2+
          old_verbose = $VERBOSE
          $VERBOSE = nil
          histogram = HDRHistogram.new(lowest, highest, sig_figs)
          $VERBOSE = old_verbose

          # Decode counts from ZigZag LEB128
          counts_data = buf[40..-1]  # After 40 bytes of header
          offset = 0
          index = 0
          while offset < counts_data.bytesize
            value, bytes_read = decode_zigzag_long(counts_data, offset)
            break if bytes_read == 0

            if value < 0
              # Negative values represent zero-count runs (skip)
              index += (-value).to_i
            else
              # Record the count at this index
              if value > 0
                histogram.send(:set_raw_count, index, value)
              end
              index += 1
            end
            offset += bytes_read
          end

          # Recalculate totals by re-reading all counts
          recalculate_internals(histogram)

          histogram
        end

        # Encode the counts array using ZigZag LEB128 with zero-run compression.
        def encode_counts(histogram, relevant_length)
          result = String.new(encoding: Encoding::BINARY)
          index = 0
          while index < relevant_length
            count = histogram.send(:get_raw_count, index)
            if count == 0
              # Count consecutive zeros for run-length encoding
              zeros_count = 1
              while (index + zeros_count) < relevant_length &&
                    histogram.send(:get_raw_count, index + zeros_count) == 0
                zeros_count += 1
              end
              # Encode as negative value (zero run)
              result << encode_zigzag_long(-zeros_count)
              index += zeros_count
            else
              result << encode_zigzag_long(count)
              index += 1
            end
          end
          result
        end

        # ZigZag + LEB128 encode a signed 64-bit integer.
        def encode_zigzag_long(value)
          # ZigZag transform: map signed to unsigned
          zz = (value << 1) ^ (value >> 63)
          zz &= 0xFFFFFFFFFFFFFFFF  # ensure unsigned 64-bit

          result = String.new(encoding: Encoding::BINARY)
          loop do
            if (zz & ~0x7F) == 0
              result << (zz & 0xFF).chr
              break
            else
              result << ((zz & 0x7F) | 0x80).chr
              zz >>= 7
            end
          end
          result
        end

        # ZigZag + LEB128 decode a signed 64-bit integer from binary data at offset.
        # Returns [value, bytes_consumed].
        def decode_zigzag_long(data, offset)
          zz = 0
          shift = 0
          bytes_read = 0

          loop do
            return [0, 0] if offset + bytes_read >= data.bytesize

            byte = data.getbyte(offset + bytes_read)
            bytes_read += 1
            zz |= (byte & 0x7F) << shift
            break if (byte & 0x80) == 0
            shift += 7
          end

          # ZigZag decode: map unsigned back to signed
          value = (zz >> 1) ^ -(zz & 1)
          [value, bytes_read]
        end

        # Convert a Ruby Float to its IEEE 754 double-precision raw bit representation (int64).
        def double_to_long_bits(double_val)
          [double_val].pack("G").unpack1("q>")
        end

        # Convert raw IEEE 754 double bits (int64) back to a Ruby Float.
        def long_bits_to_double(long_bits)
          [long_bits].pack("q>").unpack1("G")
        end

        # Recalculate total_count, min_value, max_value from the counts array.
        def recalculate_internals(histogram)
          counts_len = histogram.send(:counts_len)
          total = 0

          counts_len.times do |i|
            c = histogram.send(:get_raw_count, i)
            total += c if c > 0
          end

          histogram.send(:total_count=, total)

          if total > 0
            # raw_min/raw_max read from cached min_value/max_value fields which
            # aren't updated by set_raw_count. Use percentile() instead, which
            # iterates through counts buckets via hdr_value_at_percentile().
            min_val = histogram.percentile(0)
            max_val = histogram.percentile(100)
            histogram.send(:min_value=, min_val)
            histogram.send(:max_value=, max_val)
          end
        end
      end
    end
  end
end
