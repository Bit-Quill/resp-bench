# frozen_string_literal: true

require_relative "lib/resp_bench/version"

Gem::Specification.new do |spec|
  spec.name = "resp_bench"
  spec.version = RespBench::VERSION
  spec.authors = ["resp-bench contributors"]
  spec.email = [""]

  spec.summary = "Multi-language benchmark suite for RESP protocol databases"
  spec.description = "Ruby implementation of resp-bench - a unified benchmark suite for Redis/Valkey compatible databases and client libraries"
  spec.homepage = "https://github.com/ikolomi/resp-bench"
  spec.license = "Apache-2.0"
  spec.required_ruby_version = ">= 3.0.0"

  spec.metadata["homepage_uri"] = spec.homepage
  spec.metadata["source_code_uri"] = spec.homepage
  spec.metadata["changelog_uri"] = "#{spec.homepage}/blob/main/ruby/CHANGELOG.md"

  # Specify which files should be added to the gem when it is released.
  spec.files = Dir.glob("{bin,lib}/**/*") + %w[README.md LICENSE]
  spec.bindir = "bin"
  spec.executables = ["resp-bench"]
  spec.require_paths = ["lib"]

  spec.add_dependency "redis", "~> 5.0"
  # valkey-glide-ruby publishes as "valkey-glide-rb"; the exact revision is
  # pinned in the Gemfile (the "valkey" gem on RubyGems is a different project).
  spec.add_dependency "valkey-glide-rb", "~> 1.0"
  spec.add_dependency "async", "~> 2.6"
  spec.add_dependency "async-redis", "~> 0.8"
  spec.add_dependency "HDRHistogram", "~> 0.1"
  spec.add_dependency "oj", "~> 3.16"
  spec.add_dependency "concurrent-ruby", "~> 1.2"
end
