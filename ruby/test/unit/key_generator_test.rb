# frozen_string_literal: true

require_relative "../test_helper"

class KeyGeneratorTest < Minitest::Test
  include TestHelper

  def test_generates_sequential_keys
    config = create_keyspace_config(keys_count: 100, key_prefix: "test:")
    generator = RespBench::Engine::KeyGenerator.new(config)

    key1 = generator.next_key
    key2 = generator.next_key

    assert key1.start_with?("test:")
    assert key2.start_with?("test:")
    refute_equal key1, key2
  end

  def test_sequential_keys_wrap_around
    config = create_keyspace_config(keys_count: 3, key_prefix: "test:")
    generator = RespBench::Engine::KeyGenerator.new(config)

    keys = 6.times.map { generator.next_key }

    # Keys should wrap: 0, 1, 2, 0, 1, 2
    assert_equal keys[0], keys[3]
    assert_equal keys[1], keys[4]
    assert_equal keys[2], keys[5]
  end

  def test_generates_uniform_random_keys
    config = create_keyspace_config(
      keys_count: 1000,
      key_prefix: "rand:",
      generation_alg: "uniform_rand",
      seed: 12345
    )
    generator = RespBench::Engine::KeyGenerator.new(config)

    keys = 100.times.map { generator.next_key }

    # Should have multiple unique keys (randomness)
    unique_keys = keys.uniq
    assert unique_keys.size > 50, "Expected more than 50 unique keys, got #{unique_keys.size}"
  end

  def test_reset_restarts_sequential_counter
    config = create_keyspace_config(keys_count: 100, key_prefix: "test:")
    generator = RespBench::Engine::KeyGenerator.new(config)

    first1 = generator.next_key
    generator.next_key
    generator.reset
    first2 = generator.next_key

    assert_equal first1, first2
  end

  def test_reset_restarts_random_sequence
    config = create_keyspace_config(
      keys_count: 1000,
      key_prefix: "rand:",
      generation_alg: "uniform_rand",
      seed: 12345
    )
    generator = RespBench::Engine::KeyGenerator.new(config)

    first_sequence = 10.times.map { generator.next_key }
    generator.reset
    second_sequence = 10.times.map { generator.next_key }

    assert_equal first_sequence, second_sequence
  end

  def test_key_format_matches_expected_pattern
    config = create_keyspace_config(
      keys_count: 100,
      key_size_bytes: 16,
      key_prefix: "bench:"
    )
    generator = RespBench::Engine::KeyGenerator.new(config)

    key = generator.next_key

    # Key should be prefix + zero-padded number
    assert key.start_with?("bench:")
    # Total key size should be approximately key_size_bytes
    assert key.length >= 6 # At least "bench:" + "0"
  end

  def test_factory_method_creates_instance
    config = create_keyspace_config
    generator = RespBench::Engine::KeyGenerator.create(config)

    assert_instance_of RespBench::Engine::KeyGenerator, generator
  end
end
