using FluentAssertions;
using RespBench.Config;
using RespBench.Engine;
using Xunit;

namespace RespBench.Tests.Unit;

public class KeyGeneratorTest
{
    private static KeyspaceConfig CreateConfig(int keysCount, int keySizeBytes, string keyPrefix,
                                                string generationAlg, long? seed = null)
    {
        return new KeyspaceConfig
        {
            KeysCount = keysCount,
            KeySizeBytes = keySizeBytes,
            KeyPrefix = keyPrefix,
            GenerationAlg = generationAlg,
            Seed = seed
        };
    }

    [Fact]
    public void ShouldGenerateSequentialKeys()
    {
        var config = CreateConfig(100, 16, "test:", "sequential_int");
        var generator = KeyGenerator.Create(config);

        var key1 = System.Text.Encoding.UTF8.GetString(generator.NextKey());
        var key2 = System.Text.Encoding.UTF8.GetString(generator.NextKey());

        key1.Should().StartWith("test:");
        key2.Should().StartWith("test:");
        key1.Should().NotBe(key2);
    }

    [Fact]
    public void ShouldGenerateUniformRandomKeys()
    {
        var config = CreateConfig(1000, 16, "rand:", "uniform_rand", 12345);
        var generator = KeyGenerator.Create(config);

        var keys = new HashSet<string>();
        for (int i = 0; i < 100; i++)
            keys.Add(System.Text.Encoding.UTF8.GetString(generator.NextKey()));

        keys.Count.Should().BeGreaterThan(50);
    }

    [Fact]
    public void ShouldResetSequentialCounter()
    {
        var config = CreateConfig(100, 16, "test:", "sequential_int");
        var generator = KeyGenerator.Create(config);

        var first1 = generator.NextKey();
        generator.NextKey();
        generator.Reset();
        var first2 = generator.NextKey();

        first1.Should().Equal(first2);
    }

    [Fact]
    public void ForkedGeneratorSharesSequentialCounter()
    {
        var config = CreateConfig(1000, 16, "seq:", "sequential_int");
        var generator = KeyGenerator.Create(config);
        var forked = generator.ForkForThread(1);

        var key0 = System.Text.Encoding.UTF8.GetString(generator.NextKey()); // 0
        var key1 = System.Text.Encoding.UTF8.GetString(forked.NextKey());    // 1
        var key2 = System.Text.Encoding.UTF8.GetString(generator.NextKey()); // 2

        key0.Should().Contain("0");
        key1.Should().Contain("1");
        key2.Should().Contain("2");
    }
}
