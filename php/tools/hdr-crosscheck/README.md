# HDR Cross-Check

Verifies that the PHP engine's HdrHistogram V2 compressed payload is byte-compatible
with the canonical Java HdrHistogram library — the definitive cross-language latency
parity gate.

## Why

The PHP `HdrEncoder` produces the same binary V2 compressed format as Java's
`Histogram.encodeIntoCompressedByteBuffer()`. The unit tests assert the payload's
*structure* (cookies, header fields, IEEE754 conversion ratio), but the strongest
check is decoding a real PHP payload with Java and confirming the percentiles match.

## Run

```bash
# 1) Emit a payload + PHP-computed percentiles from a fixed sample set.
php php/tools/hdr-crosscheck/emit.php > /tmp/hdr.txt

# 2) Decode with Java's HdrHistogram and print its percentiles.
#    Get the jar, e.g. from Maven Central: org.hdrhistogram:HdrHistogram
javac -cp HdrHistogram.jar php/tools/hdr-crosscheck/HdrCrossCheck.java -d /tmp
java  -cp /tmp:HdrHistogram.jar HdrCrossCheck /tmp/hdr.txt
```

## Interpret

The tool prints two lines:

```
php:  {"count":1005,"min":1,"max":123519,"p50":...,"p90":...,"p95":...,"p99":...,"p999":...}
java: {"count":1005,"min":1,"max":123519,"p50":...,...}
```

- `count`, `min`, `max` must match exactly.
- Percentiles must match within HdrHistogram's value-equivalence range (values in the
  same bucket are considered equal). Small differences at the bucket boundary are
  expected and acceptable; large differences indicate an encoding mismatch.

A clean match confirms PHP latency data is directly comparable to the Java, Ruby,
and C# engines.
