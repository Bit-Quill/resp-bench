"""Pytest configuration — adds scripts/ and scripts/tests/ to sys.path."""
import sys
from pathlib import Path

# Add scripts/ to path so we can import generate_interactive_graphs and run_benchmark_matrix
scripts_dir = Path(__file__).parent.parent
if str(scripts_dir) not in sys.path:
    sys.path.insert(0, str(scripts_dir))

# Add scripts/tests/ to path so we can import fixtures
tests_dir = Path(__file__).parent
if str(tests_dir) not in sys.path:
    sys.path.insert(0, str(tests_dir))
