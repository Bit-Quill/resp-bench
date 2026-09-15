// HDR cross-check decoder (Java side).
//
// Reads the two-line output of emit.php (JSON summary on line 1, base64 V2
// compressed payload on line 2), decodes the payload with the canonical Java
// HdrHistogram library, and prints Java-computed percentiles so they can be
// compared against the PHP summary. Matching values prove the PHP encoder is
// byte-compatible with Java's Histogram.encodeIntoCompressedByteBuffer().
//
// Requires org.hdrhistogram:HdrHistogram on the classpath. Example:
//   PHP: php php/tools/hdr-crosscheck/emit.php > /tmp/hdr.txt
//   javac -cp HdrHistogram.jar HdrCrossCheck.java
//   java  -cp .:HdrHistogram.jar HdrCrossCheck /tmp/hdr.txt
//
// Compare the "java:" percentile line against the JSON on line 1 of /tmp/hdr.txt.

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.HdrHistogram.Histogram;

public class HdrCrossCheck {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java HdrCrossCheck <emit-output-file>");
            System.exit(2);
        }

        List<String> lines = Files.readAllLines(Path.of(args[0]));
        if (lines.size() < 2) {
            System.err.println("Expected two lines (summary JSON, base64 payload)");
            System.exit(2);
        }

        String phpSummary = lines.get(0);
        String base64Payload = lines.get(1).trim();

        byte[] compressed = Base64.getDecoder().decode(base64Payload);
        Histogram h = Histogram.decodeFromCompressedByteBuffer(ByteBuffer.wrap(compressed), 0);

        System.out.println("php:  " + phpSummary);
        System.out.printf(
            "java: {\"count\":%d,\"min\":%d,\"max\":%d,\"p50\":%d,\"p90\":%d,\"p95\":%d,\"p99\":%d,\"p999\":%d}%n",
            h.getTotalCount(),
            h.getMinValue(),
            h.getMaxValue(),
            h.getValueAtPercentile(50.0),
            h.getValueAtPercentile(90.0),
            h.getValueAtPercentile(95.0),
            h.getValueAtPercentile(99.0),
            h.getValueAtPercentile(99.9)
        );
        System.out.println("If php/java percentiles match (within HdrHistogram equivalence), the encoding is compatible.");
    }
}
