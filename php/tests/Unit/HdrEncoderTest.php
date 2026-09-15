<?php

declare(strict_types=1);

namespace RespBench\Tests\Unit;

use PHPUnit\Framework\TestCase;
use RespBench\Metrics\HdrEncoder;
use RespBench\Metrics\HdrHistogram;

final class HdrEncoderTest extends TestCase
{
    private const COMPRESSED_COOKIE = 0x1c849314;
    private const V2_COOKIE = 0x1c849313;

    public function testEmptyHistogramSummaries(): void
    {
        $h = new HdrHistogram(1, 600_000_000, 3);
        self::assertSame(0, $h->totalCount());
        self::assertSame(0, $h->min());
        self::assertSame(0, $h->max());
        self::assertSame(0, $h->valueAtPercentile(50));
    }

    public function testRecordsAndComputesPercentiles(): void
    {
        $h = new HdrHistogram(1, 600_000_000, 3);
        for ($i = 1; $i <= 1000; $i++) {
            $h->record($i);
        }

        self::assertSame(1000, $h->totalCount());
        self::assertSame(1, $h->min());
        // Values are quantized; assert within HdrHistogram equivalence tolerance.
        self::assertEqualsWithDelta(500, $h->valueAtPercentile(50), 5);
        self::assertEqualsWithDelta(990, $h->valueAtPercentile(99), 10);
        self::assertGreaterThanOrEqual(1000, $h->max());
    }

    public function testCompressedEncodingStructure(): void
    {
        $h = new HdrHistogram(1, 600_000_000, 3);
        foreach ([100, 150, 150, 200, 5000, 12345, 250, 250, 250] as $v) {
            $h->record($v);
        }

        $raw = HdrEncoder::encodeCompressed($h);

        /** @var array{cookie:int,len:int} $wrapper */
        $wrapper = unpack('Ncookie/Nlen', substr($raw, 0, 8));
        self::assertSame(self::COMPRESSED_COOKIE, $wrapper['cookie']);
        self::assertSame(strlen($raw) - 8, $wrapper['len']);

        $payload = gzuncompress(substr($raw, 8));
        self::assertIsString($payload);

        /** @var array{cookie:int,plen:int,norm:int,sig:int} $v2 */
        $v2 = unpack('Ncookie/Nplen/Nnorm/Nsig', substr($payload, 0, 16));
        self::assertSame(self::V2_COOKIE, $v2['cookie']);
        self::assertSame(0, $v2['norm']);
        self::assertSame(3, $v2['sig']);

        /** @var array{lowest:int,highest:int,ratio:int} $fields */
        $fields = unpack('Jlowest/Jhighest/Jratio', substr($payload, 16, 24));
        self::assertSame(1, $fields['lowest']);
        self::assertSame(600_000_000, $fields['highest']);
        // IEEE754 bits for 1.0
        self::assertSame(4607182418800017408, $fields['ratio']);
    }

    public function testBase64Encoding(): void
    {
        $h = new HdrHistogram(1, 600_000_000, 3);
        $h->record(42);

        $b64 = HdrEncoder::encodeCompressedBase64($h);
        $decoded = base64_decode($b64, true);
        self::assertIsString($decoded);

        /** @var array{cookie:int} $wrapper */
        $wrapper = unpack('Ncookie', substr($decoded, 0, 4));
        self::assertSame(self::COMPRESSED_COOKIE, $wrapper['cookie']);
    }
}
