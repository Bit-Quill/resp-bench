"""Enable ``python -m resp_bench``."""

import sys

from .cli import main

if __name__ == "__main__":
    sys.exit(main())
