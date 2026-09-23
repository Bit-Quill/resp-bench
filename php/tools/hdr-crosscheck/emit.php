<?php

declare(strict_types=1);

/**
 * HDR cross-check emitter.
 *
 * Records a fixed set of latency values into the PHP HdrHistogram, prints the
 * PHP-computed percentiles, and emits the Java-compatible V2 compressed base64
 * payload. Feed the payload to HdrCrossCheck.java to confirm Java decodes it and
 * computes matching percentiles — the definitive cross-language parity gate.
 *
 * Usage:
 *   php php/tools/hdr-crosscheck/emit.php > /tmp/hdr_payload.txt
 */

$autoload = __DIR__ . '/../../vendor/autoload.php';
if (!is_file($autoload)) {
    fwrite(STDERR, "Autoloader not found. Run composer install in php/.\n");
    exit(2);
}
require $autoload;

use RespBench\Metrics\HdrEncoder;
use RespBench\Metrics\HdrHistogram;

// Fixed, reproducible sample set (microseconds).
$values = [];
for ($i = 1; $i <= 1000; $i++) {
    $values[] = $i;          // 1..1000 linear
}
foreach ([2500, 5000, 10000, 50000, 123456] as $tail) {
    $values[] = $tail;       // long tail
}

$h = new HdrHistogram(1, 600_000_000, 3);
foreach ($values as $v) {
    $h->record($v);
}

$percentiles = [50.0, 90.0, 95.0, 99.0, 99.9];
$phpSummary = [
    'count' => $h->totalCount(),
    'min' => $h->min(),
    'max' => $h->max(),
];
foreach ($percentiles as $p) {
    $phpSummary['p' . str_replace('.', '', (string) $p)] = $h->valueAtPercentile($p);
}

$payload = HdrEncoder::encodeCompressedBase64($h);

// Machine-readable output: first line = JSON summary, second line = base64 payload.
echo json_encode($phpSummary, JSON_THROW_ON_ERROR) . "\n";
echo $payload . "\n";
