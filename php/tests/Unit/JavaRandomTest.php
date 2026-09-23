<?php

declare(strict_types=1);

namespace RespBench\Tests\Unit;

use InvalidArgumentException;
use PHPUnit\Framework\TestCase;
use RespBench\Engine\JavaRandom;

final class JavaRandomTest extends TestCase
{
    public function testProducesDeterministicSequence(): void
    {
        $rng1 = new JavaRandom(12345);
        $rng2 = new JavaRandom(12345);

        $values1 = [];
        $values2 = [];
        for ($i = 0; $i < 10; $i++) {
            $values1[] = $rng1->nextInt(1000);
            $values2[] = $rng2->nextInt(1000);
        }

        self::assertSame($values1, $values2);
    }

    public function testDifferentSeedsProduceDifferentSequences(): void
    {
        $rng1 = new JavaRandom(12345);
        $rng2 = new JavaRandom(54321);

        $values1 = [];
        $values2 = [];
        for ($i = 0; $i < 10; $i++) {
            $values1[] = $rng1->nextInt(1000);
            $values2[] = $rng2->nextInt(1000);
        }

        self::assertNotSame($values1, $values2);
    }

    public function testSetSeedResetsSequence(): void
    {
        $rng = new JavaRandom(12345);

        $first = [];
        for ($i = 0; $i < 5; $i++) {
            $first[] = $rng->nextInt(1000);
        }

        $rng->setSeed(12345);

        $second = [];
        for ($i = 0; $i < 5; $i++) {
            $second[] = $rng->nextInt(1000);
        }

        self::assertSame($first, $second);
    }

    public function testBoundMustBePositive(): void
    {
        $rng = new JavaRandom(12345);
        $this->expectException(InvalidArgumentException::class);
        $rng->nextInt(0);
    }

    public function testValuesAreWithinBound(): void
    {
        $rng = new JavaRandom(12345);
        $bound = 100;
        for ($i = 0; $i < 100; $i++) {
            $value = $rng->nextInt($bound);
            self::assertGreaterThanOrEqual(0, $value);
            self::assertLessThan($bound, $value);
        }
    }

    public function testPowerOfTwoBounds(): void
    {
        $rng = new JavaRandom(12345);
        foreach ([2, 4, 8, 16, 32, 64, 128, 256, 512, 1024] as $bound) {
            for ($i = 0; $i < 100; $i++) {
                $value = $rng->nextInt($bound);
                self::assertGreaterThanOrEqual(0, $value);
                self::assertLessThan($bound, $value);
            }
        }
    }

    /**
     * Cross-language anchor: the first 10 outputs of Java's
     * `new Random(0).nextInt(1000)`. These are engine-independent facts about
     * java.util.Random and are shared by the Ruby/Python/Node ports.
     *
     * Verified: the underlying LCG reproduces Java's canonical
     * `new Random(0).nextInt()` signed-int sequence
     * [-1155484576, -723955400, 1033096058, -1690734402, -1557280266, ...].
     */
    public function testMatchesJavaSeedZeroAnchor(): void
    {
        $rng = new JavaRandom(0);
        $actual = [];
        for ($i = 0; $i < 10; $i++) {
            $actual[] = $rng->nextInt(1000);
        }

        $expected = [360, 948, 29, 447, 515, 53, 491, 761, 719, 854];
        self::assertSame($expected, $actual);
    }
}
