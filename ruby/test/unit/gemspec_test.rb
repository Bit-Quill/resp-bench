# frozen_string_literal: true

require_relative "../test_helper"

class GemspecTest < Minitest::Test
  def test_declares_required_standard_library_dependencies
    gemspec_path = File.expand_path("../../resp_bench.gemspec", __dir__)
    dependency_names = Gem::Specification.load(gemspec_path).runtime_dependencies.map(&:name)

    assert_includes dependency_names, "base64"
    assert_includes dependency_names, "logger"
  end
end
