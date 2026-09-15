<?php

declare(strict_types=1);

namespace RespBench\Tests\Unit;

use PHPUnit\Framework\TestCase;
use RespBench\Config\KeyspaceConfig;
use RespBench\Engine\KeyGenerator;

final class KeyGeneratorTest extends TestCase
{
    public function testGeneratesSequentialKeys(): void
    {
        $config = new KeyspaceConfig(keysCount: 100, keyPrefix: 'test:');
        $gen = new KeyGenerator($config);

        $key1 = $gen->nextKey();
        $key2 = $gen->nextKey();

        self::assertStringStartsWith('test:', $key1);
        self::assertStringStartsWith('test:', $key2);
        self::assertNotSame($key1, $key2);
    }

    public function testSequentialKeysWrapAround(): void
    {
        $config = new KeyspaceConfig(keysCount: 3, keyPrefix: 'test:');
        $gen = new KeyGenerator($config);

        $keys = [];
        for ($i = 0; $i < 6; $i++) {
            $keys[] = $gen->nextKey();
        }

        // Keys should wrap: 0, 1, 2, 0, 1, 2
        self::assertSame($keys[0], $keys[3]);
        self::assertSame($keys[1], $keys[4]);
        self::assertSame($keys[2], $keys[5]);
    }

    public function testGeneratesUniformRandomKeys(): void
    {
        $config = new KeyspaceConfig(
            keysCount: 1000,
            keyPrefix: 'rand:',
            generationAlg: 'uniform_rand',
            seed: 12345,
        );
        $gen = new KeyGenerator($config);

        $keys = [];
        for ($i = 0; $i < 100; $i++) {
            $keys[] = $gen->nextKey();
        }

        $unique = array_unique($keys);
        self::assertGreaterThan(50, count($unique));
    }

    public function testResetRestartsSequentialCounter(): void
    {
        $config = new KeyspaceConfig(keysCount: 100, keyPrefix: 'test:');
        $gen = new KeyGenerator($config);

        $first1 = $gen->nextKey();
        $gen->nextKey();
        $gen->reset();
        $first2 = $gen->nextKey();

        self::assertSame($first1, $first2);
    }

    public function testResetRestartsRandomSequence(): void
    {
        $config = new KeyspaceConfig(
            keysCount: 1000,
            keyPrefix: 'rand:',
            generationAlg: 'uniform_rand',
            seed: 12345,
        );
        $gen = new KeyGenerator($config);

        $first = [];
        for ($i = 0; $i < 10; $i++) {
            $first[] = $gen->nextKey();
        }
        $gen->reset();
        $second = [];
        for ($i = 0; $i < 10; $i++) {
            $second[] = $gen->nextKey();
        }

        self::assertSame($first, $second);
    }

    public function testKeyFormatZeroPadsToKeySize(): void
    {
        $config = new KeyspaceConfig(
            keysCount: 100,
            keySizeBytes: 16,
            keyPrefix: 'bench:',
        );
        $gen = new KeyGenerator($config);

        $key = $gen->nextKey();

        // prefix "bench:" (6) + padding width max(16-6,1)=10 -> "bench:0000000000"
        self::assertSame('bench:0000000000', $key);
        self::assertSame(16, strlen($key));
    }

    public function testUniformRandMatchesJavaRandomSequence(): void
    {
        // With uniform_rand + seed, keys are prefix + JavaRandom.nextInt(keysCount).
        $config = new KeyspaceConfig(
            keysCount: 1000,
            keySizeBytes: 16,
            keyPrefix: 'bench:',
            generationAlg: 'uniform_rand',
            seed: 0,
        );
        $gen = new KeyGenerator($config);

        $first = $gen->nextKey();
        // JavaRandom(0).nextInt(1000) == 360 (verified against Java canonical LCG).
        self::assertSame('bench:0000000360', $first);
    }
}
